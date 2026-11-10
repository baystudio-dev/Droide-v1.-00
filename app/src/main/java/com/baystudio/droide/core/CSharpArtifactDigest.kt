package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64

/** Publisher digest and an optional original publisher representation. Never a fabricated SHA-256. */
data class CSharpArtifactDigest(val algorithm: Algorithm, val hex: String, val publisherBase64: String? = null) {
    enum class Algorithm(val jca: String, val bytes: Int) { SHA256("SHA-256", 32), SHA512("SHA-512", 64) }
    fun validate() {
        require(hex.matches(Regex("[0-9a-f]{${algorithm.bytes * 2}}")))
        publisherBase64?.let {
            require(algorithm == Algorithm.SHA512 && it.length == 88)
            val decoded = Base64.getDecoder().decode(it)
            require(decoded.size == 64 && Base64.getEncoder().encodeToString(decoded) == it && decoded.hex() == hex)
        }
    }
    fun verify(file: File, maximum: Long, expected: Long?, active: () -> Unit = {}): String {
        validate(); require(maximum in 1..1_073_741_824L)
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && !before.isSymbolicLink && before.size() in 1..maximum)
        require(expected == null || before.size() == expected)
        val publisher = MessageDigest.getInstance(algorithm.jca)
        val local = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(65536)
            while (true) { active(); val n = input.read(bytes); if (n < 0) break
                size = Math.addExact(size, n.toLong()); require(size <= maximum)
                publisher.update(bytes, 0, n); local.update(bytes, 0, n)
            }
        }
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(size == before.size() && before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
        require(publisher.digest().hex() == hex) { "Original C# publisher artifact digest mismatch" }
        return local.digest().hex() // Explicitly local post-authentication SHA-256, not publisher evidence.
    }
    companion object { internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) } }
}

enum class CSharpArtifactRole { MANAGED_NUPKG, ORIGINAL_SDK, PRIVATE_NODE_HELPER }

data class CSharpInstallRecipe(
    val familyId: String, val version: String, val command: String, val role: CSharpArtifactRole,
    val url: String, val fileName: String, val publisherDigest: CSharpArtifactDigest,
    val expectedBytes: Long?, val maxArchiveBytes: Long, val maxFiles: Int, val maxTreeBytes: Long,
    val entryPoint: String, val provenanceUrl: String,
) {
    fun validate() {
        require(this == LocalUbuntuCSharpPolicy.recipe(familyId)) { "Only exact original C# artifacts are admitted" }
        publisherDigest.validate(); NetworkSecurity.validatePublicHttpsUrl(url)
        require(maxFiles in 1..32768 && maxTreeBytes in 1..4_294_967_296L)
        require(maxArchiveBytes in 1..1_073_741_824L && (expectedBytes == null || expectedBytes in 1..maxArchiveBytes))
    }
    fun descriptor(): TrustedArtifactDescriptor {
        validate()
        val id = "csharp-$familyId-$version"
        return when (publisherDigest.algorithm) {
            CSharpArtifactDigest.Algorithm.SHA256 -> TrustedArtifactSpec(id, url, publisherDigest.hex, fileName, maxArchiveBytes, expectedBytes)
            CSharpArtifactDigest.Algorithm.SHA512 -> TrustedSha512ArtifactSpec(id, url, publisherDigest.hex, fileName, maxArchiveBytes, expectedBytes)
        }
    }
}
