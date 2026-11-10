package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** SDK/Gradle consumes licenses/android-sdk-license, not Droide's DataStore receipt. */
internal object AndroidSdkLicenseProjection {
    @Synchronized fun directory(appRoot: File): File {
        val root = appRoot.canonicalFile
        var current = root
        for (part in listOf("android-sdk-consent", "licenses")) {
            current = File(current, part)
            check(!PathSecurity.isSymbolicLink(current)) { "SDK license directory must not be a symlink" }
            check(current.mkdirs() || current.isDirectory) { "Cannot prepare SDK license directory" }
            check(current.canonicalPath.startsWith(root.path + File.separator)) { "SDK license directory escaped app storage" }
        }
        return current
    }

    fun validate(license: AndroidSdkLicense) {
        require(license.text.isNotBlank() && digest(license.text, "SHA-256") == license.sha256)
        require(license.repositoryText.trim() == license.text && license.repositoryText.length <= 100_000) {
            "SDK repository license differs from reviewed terms"
        }
    }

    @Synchronized fun writeAccepted(appRoot: File, license: AndroidSdkLicense) {
        validate(license)
        val directory = directory(appRoot)
        val target = File(directory, "android-sdk-license")
        check(!PathSecurity.isSymbolicLink(target)) { "SDK license receipt must not be a symlink" }
        val expected = digest(license.repositoryText, "SHA-1") + "\n"
        if (target.isFile && target.length() == 41L && target.readText() == expected) return
        val temporary = File.createTempFile(".license-", ".tmp", directory)
        try {
            temporary.writeText(expected, Charsets.UTF_8)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    @Synchronized fun revoke(appRoot: File) {
        val target = File(directory(appRoot), "android-sdk-license")
        check(!PathSecurity.isSymbolicLink(target)) { "SDK license receipt must not be a symlink" }
        check(!target.exists() || target.delete()) { "Cannot remove SDK license receipt" }
    }

    private fun digest(text: String, algorithm: String) = MessageDigest.getInstance(algorithm)
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
