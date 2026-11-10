package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Original Eclipse Nexus bytes only. Metadata/structure checks do not execute or attest the JVM. */
internal object LocalUbuntuLemMinxPolicy {
    const val FAMILY = "lsp.lemminx"
    const val COMMAND = "lemminx"
    const val VERSION = "0.31.2"
    const val ASSET = "org.eclipse.lemminx-0.31.2-uber.jar"
    const val REPOSITORY = "lemminx-maven2-releases"
    const val GROUP = "org.eclipse.lemminx"
    const val COORDINATES = "org.eclipse.lemminx:org.eclipse.lemminx:0.31.2:uber"
    const val ASSET_PATH = "/org/eclipse/lemminx/org.eclipse.lemminx/0.31.2/" + ASSET
    const val ASSET_URL = "https://repo.eclipse.org/repository/" + REPOSITORY + ASSET_PATH
    const val ASSET_ID = "bGVtbWlueC1tYXZlbjItcmVsZWFzZXM6MWQ0Y2YwYTE"
    const val METADATA_URL = "https://repo.eclipse.org/service/rest/v1/assets/" + ASSET_ID
    const val SEARCH_URL = "https://repo.eclipse.org/service/rest/v1/search?repository=lemminx-maven2-releases&version=0.31.2"
    const val SHA256 = "f4fde164e785c635e5f86361dbf4b993bfe2c5f83fdb52891d058b7dfc9bcfc8"
    const val ARTIFACT_BYTES = 8_780_504L
    const val JAVA = "/usr/lib/jvm/java-21-openjdk-arm64/bin/java"
    const val MAIN_CLASS = "org.eclipse.lemminx.XMLServerLauncher"
    const val MANIFEST_BYTES = 65_536
    private const val MAX_JAR_ENTRIES = 32_768

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.version == VERSION && recipe.releaseTag == VERSION && recipe.assetName == ASSET &&
            recipe.assetUrl == ASSET_URL && recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES &&
            recipe.provenanceUrl == METADATA_URL) {
            "Only the exact reviewed LemMinX $VERSION original Eclipse Nexus JAR is supported; no version is substituted."
        }
    }

    fun publisherRecipe(definition: LocalUbuntuVendorDefinition, text: String): LocalUbuntuVendorRecipe {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "LemMinX publisher metadata exceeds its bound" }
        val asset = Json.parseToJsonElement(text) as? JsonObject ?: error("LemMinX publisher asset is not an object")
        fun JsonObject.string(key: String): String {
            val value = this[key] as? JsonPrimitive
            require(value != null && value.isString) { "LemMinX publisher field '$key' is not a string" }
            return value.content
        }
        require(asset.string("id") == ASSET_ID && asset.string("repository") == REPOSITORY &&
            asset.string("format") == "maven2" && asset.string("contentType") == "application/java-archive" &&
            asset.string("path") == ASSET_PATH && asset.string("downloadUrl") == ASSET_URL) {
            "LemMinX publisher asset differs from the exact reviewed original JAR"
        }
        val coordinates = asset["maven2"] as? JsonObject ?: error("LemMinX Maven coordinates are missing")
        require(coordinates.string("groupId") == GROUP && coordinates.string("artifactId") == GROUP &&
            coordinates.string("version") == VERSION && coordinates.string("classifier") == "uber" &&
            coordinates.string("extension") == "jar") { "LemMinX Maven coordinates changed" }
        val checksum = asset["checksum"] as? JsonObject ?: error("LemMinX publisher checksum is missing")
        require(checksum.string("sha256") == SHA256) { "LemMinX publisher digest changed" }
        val size = asset["fileSize"] as? JsonPrimitive
        require(size != null && !size.isString && size.content == ARTIFACT_BYTES.toString()) { "LemMinX publisher size changed" }
        return LocalUbuntuVendorRecipe(definition, VERSION, VERSION, ASSET, ASSET_URL, SHA256, ARTIFACT_BYTES, METADATA_URL)
            .also { it.validate() }
    }

    /** Production gate: never admit a synthetic/repacked JAR merely because its manifest looks right. */
    fun requireArtifact(file: File) {
        require(file.name == ASSET && file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() == ARTIFACT_BYTES) {
            "LemMinX original JAR size or entry changed"
        }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65_536)
            var total = 0L
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                total += count
                require(total <= ARTIFACT_BYTES) { "LemMinX JAR grew beyond its original size" }
                hash.update(bytes, 0, count)
            }
            require(total == ARTIFACT_BYTES) { "LemMinX JAR length changed" }
        }
        require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == SHA256) {
            "LemMinX original JAR digest changed"
        }
        requireJarStructure(file)
    }

    /** Structural parser seam for inert ZIP fixtures; callers must separately enforce the original payload pin. */
    internal fun requireJarStructure(file: File) {
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..ARTIFACT_BYTES) { "Invalid LemMinX JAR container" }
        requireRegularManifest(file)
        ZipFile(file).use { zip ->
            val names = HashSet<String>()
            var manifestCount = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.size < MAX_JAR_ENTRIES && names.add(entry.name)) { "LemMinX JAR has duplicate or excessive entries" }
                require(entry.name.isNotEmpty() && entry.name.length <= 4096 && entry.name.none { it.isISOControl() || it == '\\' } &&
                    !entry.name.startsWith('/') && entry.name.trimEnd('/').split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                    "LemMinX JAR has an unsafe entry name"
                }
                if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) manifestCount++
            }
            require(manifestCount == 1) { "LemMinX JAR must have one unambiguous manifest" }
            val manifest = requireNotNull(zip.getEntry("META-INF/MANIFEST.MF")) { "LemMinX JAR manifest is missing" }
            require(!manifest.isDirectory && manifest.size in 1L..MANIFEST_BYTES.toLong()) { "LemMinX JAR manifest exceeds its bound" }
            val bytes = zip.getInputStream(manifest).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MANIFEST_BYTES) { "LemMinX JAR manifest exceeds its decoded bound" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            require(bytes.size.toLong() == manifest.size) { "LemMinX JAR manifest length changed" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().replace("\r\n", "\n")
            require(text.none { it == '\r' || it == '\u0000' } && text.endsWith("\n\n")) { "Malformed LemMinX JAR manifest" }
            var main = true
            val section = linkedMapOf<String, String>()
            var previous: String? = null
            var mainClass: String? = null
            fun finishSection() {
                if (main) mainClass = section["main-class"]
                require(section["class-path"].isNullOrEmpty()) { "LemMinX JAR must not add a manifest Class-Path" }
                main = false
                section.clear()
                previous = null
            }
            for (line in text.split('\n')) {
                require(line.length <= MANIFEST_BYTES) { "LemMinX manifest line exceeds its bound" }
                when {
                    line.isEmpty() -> finishSection()
                    line.startsWith(' ') -> {
                        val key = requireNotNull(previous) { "Orphan LemMinX manifest continuation" }
                        section[key] = section.getValue(key) + line.substring(1)
                    }
                    else -> {
                        val colon = line.indexOf(": ")
                        require(colon in 1..70) { "Malformed LemMinX manifest attribute" }
                        val key = line.substring(0, colon).lowercase(Locale.ROOT)
                        require(key.matches(Regex("[a-z0-9][a-z0-9_-]*")) && section.size < 256 && !section.containsKey(key)) {
                            "Duplicate or invalid LemMinX manifest attribute"
                        }
                        section[key] = line.substring(colon + 2)
                        previous = key
                    }
                }
            }
            require(mainClass == MAIN_CLASS) { "LemMinX JAR has an unreviewed Main-Class" }
            require(zip.getEntry(MAIN_CLASS.replace('.', '/') + ".class")?.isDirectory == false) { "LemMinX JAR launcher class is missing" }
        }
    }

    /** Inspect ZIP central metadata without extraction: a directory/link cannot stand in for the manifest. */
    private fun requireRegularManifest(file: File) {
        java.io.RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            require(length in 22..ARTIFACT_BYTES) { "Invalid LemMinX ZIP length" }
            val tailLength = minOf(length, 65_557L).toInt()
            val tail = ByteArray(tailLength)
            input.seek(length - tailLength); input.readFully(tail)
            fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
            fun u32(bytes: ByteArray, at: Int) = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
            val endings = (0..tail.size - 22).filter { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size }
            val end = requireNotNull(endings.singleOrNull()) { "Missing or ambiguous LemMinX ZIP directory end" }
            val count = u16(tail, end + 10)
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 &&
                u16(tail, end + 8) == count && count in 1..MAX_JAR_ENTRIES) { "Unsupported LemMinX multi-disk or ZIP64 archive" }
            val start = u32(tail, end + 16)
            val size = u32(tail, end + 12)
            val limit = length - tailLength + end
            require(start + size == limit && start >= 0 && size >= 46) { "Invalid LemMinX ZIP directory bounds" }
            input.seek(start)
            var found = false
            repeat(count) {
                require(input.filePointer + 46 <= limit) { "Truncated LemMinX ZIP directory" }
                val header = ByteArray(46); input.readFully(header)
                require(u32(header, 0) == 0x02014b50L && u16(header, 34) == 0) { "Invalid LemMinX ZIP directory entry" }
                val nameLength = u16(header, 28)
                val remaining = u16(header, 30) + u16(header, 32)
                require(nameLength in 1..4096 && input.filePointer + nameLength + remaining <= limit) { "Invalid LemMinX ZIP entry bounds" }
                val name = ByteArray(nameLength); input.readFully(name)
                if (name.contentEquals("META-INF/MANIFEST.MF".toByteArray(Charsets.US_ASCII))) {
                    require(!found) { "Duplicate LemMinX ZIP manifest" }
                    found = true
                    val attributes = u32(header, 38)
                    val kind = (attributes ushr 16).toInt() and 0xf000
                    require(kind in setOf(0, 0x8000) && attributes and 0x10L == 0L && u16(header, 8) and 1 == 0 &&
                        u16(header, 10) in setOf(0, 8)) { "LemMinX ZIP manifest is not an unencrypted regular entry" }
                }
                input.seek(input.filePointer + remaining)
            }
            require(found && input.filePointer == limit) { "Invalid LemMinX ZIP manifest directory" }
        }
    }

}
