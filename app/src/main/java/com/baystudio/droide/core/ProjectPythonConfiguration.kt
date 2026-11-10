package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

/** Explicit IDE-only selection. Loading this file never creates, activates, or installs anything. */
@ConsistentCopyVisibility
internal data class ProjectPythonConfiguration private constructor(
    /** Literal guest argv[0], not a host-resolved executable or a shell command. */
    val interpreter: String,
    /** Exact file bytes, including comments, participate in the approval identity. */
    val configSha256: String,
) {
    fun runArgv(scriptPath: String): List<String> {
        require(scriptPath.isNotEmpty() && scriptPath.none(::isControl)) { "Invalid Python source path" }
        // A filename beginning with '-' must not become an interpreter option.
        val source = if (scriptPath.startsWith('-')) "./$scriptPath" else scriptPath
        return listOf(interpreter, source)
    }

    fun pytestArgv(): List<String> = listOf(interpreter, "-m", "pytest")

    companion object {
        const val CONFIG_PATH = ".droide/python.json"
        const val MAX_INTERPRETER_LENGTH = 4_000
        private val bareCommand = Regex("[A-Za-z0-9_][A-Za-z0-9_.+-]*")

        /** Only an absent configuration permits the caller's default interpreter. */
        fun load(root: File): ProjectPythonConfiguration? {
            val bytes = try {

                PathSecurity.resolveWithin(root, ".droide")
                val directory = File(root, ".droide")
                val directoryAttributes = attributesOrAbsent(directory) ?: return null
                require(directoryAttributes.isDirectory) { ".droide must be a directory" }
                val config = PathSecurity.resolveWithin(root, CONFIG_PATH)
                val attributes = attributesOrAbsent(File(root, CONFIG_PATH)) ?: return null
                require(attributes.isRegularFile) { "configuration must be a regular file" }
                require(attributes.size() in 1..WorkspaceJsonConfig.MAX_BYTES.toLong()) {
                    "configuration must contain 1..${WorkspaceJsonConfig.MAX_BYTES} bytes"
                }
                readBounded(config)
            } catch (failure: Exception) {
                throw invalid("Cannot read a regular configuration inside the project (maximum ${WorkspaceJsonConfig.MAX_BYTES} bytes)", failure)
            }

            val interpreter = try {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                val config = WorkspaceJsonConfig.parseObject(text)
                require(config.keys == setOf("interpreter")) { "Expected only the interpreter field" }
                val value = (config["interpreter"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("interpreter must be a string")
                literalInterpreter(value)
            } catch (failure: Exception) {
                throw invalid("Expected a JSON object with one literal interpreter string, for example {\"interpreter\":\".venv/bin/python\"}", failure)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return ProjectPythonConfiguration(interpreter, digest)
        }

        private fun attributesOrAbsent(file: File): BasicFileAttributes? = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        } catch (missing: NoSuchFileException) {
            if (Files.isSymbolicLink(file.toPath())) throw missing
            null
        }

        private fun readBounded(file: File): ByteArray {

            val bytes = ByteArray(WorkspaceJsonConfig.MAX_BYTES + 1)
            var count = 0
            Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) {
                        val next = input.read()
                        if (next < 0) break
                        bytes[count++] = next.toByte()
                    } else count += read
                }
            }
            require(count in 1..WorkspaceJsonConfig.MAX_BYTES) { "Configuration size changed or exceeded its limit" }
            return bytes.copyOf(count)
        }

        private fun literalInterpreter(value: String): String {
            require(value.length in 1..MAX_INTERPRETER_LENGTH && value.none(::isControl)) { "Invalid interpreter length or control character" }

            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
            require(!value.startsWith('-')) { "Interpreter must not start with an option" }
            if ('/' !in value) {
                require(bareCommand.matches(value)) { "Interpreter must be one command name or a literal path" }
                return value
            }
            require(value.substringAfterLast('/') !in setOf("", ".", "..")) { "Interpreter must name a file" }
            if (value.startsWith('/')) return value
            require(value.split('/').none { it == ".." }) { "Relative interpreter must stay inside the project" }
            // Do not canonicalize or check canExecute(): a guest venv may contain symlinks to
            // /usr/bin/python3 which are perfectly valid in Ubuntu but absent on the host.
            return if (value.startsWith("./")) value else "./$value"
        }

        private fun isControl(character: Char): Boolean = Character.isISOControl(character) ||
            Character.getType(character) == Character.FORMAT.toInt() ||
            character == '\u2028' || character == '\u2029'

        private fun invalid(reason: String, cause: Exception): IllegalArgumentException =
            IllegalArgumentException("$CONFIG_PATH: $reason. Fix this file or remove it to use the default Python interpreter.", cause)
    }
}
