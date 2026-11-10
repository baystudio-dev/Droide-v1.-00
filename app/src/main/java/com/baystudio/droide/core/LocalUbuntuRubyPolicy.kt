package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class LocalUbuntuGemPin(val name: String, val version: String, val sha256: String, val sizeBytes: Long) {
    val fileName: String get() = "$name-$version.gem"
    fun artifact() = TrustedArtifactSpec("ruby-$name-$version", "https://rubygems.org/downloads/$fileName", sha256, fileName, sizeBytes, sizeBytes)
}

data class LocalUbuntuRubyRecipe(val familyId: String, val version: String, val command: String, val gems: List<LocalUbuntuGemPin>)

/** Original ruby-platform gems. Only Prism and RBS build their reviewed native extensions. */
object LocalUbuntuRubyCatalog {
    val rubyLsp = LocalUbuntuRubyRecipe("lsp.ruby-lsp", "0.26.11", "ruby-lsp", listOf(
        LocalUbuntuGemPin("language_server-protocol", "3.17.0.6", "5ef2c0c138f8267e1bc631d3328347d354f96724b0af22f2c79516120443b7f0", 121_856),
        LocalUbuntuGemPin("logger", "1.7.0", "196edec7cc44b66cfb40f9755ce11b392f21f7967696af15d274dde7edff0203", 16_896),
        LocalUbuntuGemPin("prism", "1.9.0", "7b530c6a9f92c24300014919c9dcbc055bf4cdf51ec30aed099b06cd6674ef85", 650_240),
        LocalUbuntuGemPin("rbs", "4.1.3", "0c4474a9751cdc14364bfad0b3e53678323bbdc2c31683b0445932867dbab8c4", 1_284_608),
        LocalUbuntuGemPin("ruby-lsp", "0.26.11", "4cc8f1587237ff8c9031680b5491df901a82559eeaa1ae96863ffe700b32583d", 151_552),
        LocalUbuntuGemPin("tsort", "0.2.0", "9650a793f6859a43b6641671278f79cfead60ac714148aabe4e3f0060480089f", 10_240),
    ))
    fun find(familyId: String, version: String? = null): LocalUbuntuRubyRecipe? = rubyLsp.takeIf {
        it.familyId == familyId && (version == null || it.version == version)
    }
}

internal object LocalUbuntuRubyPolicy {
    val packages = listOf("ruby", "ruby-dev", "ruby-bundler", "build-essential", "nodejs")
    const val PROVENANCE = "https://github.com/Shopify/ruby-lsp/tree/v0.26.11"
    const val RUBY_ABI = "3.2.0"
    val gemfile = "source 'https://rubygems.org'\ngem 'ruby-lsp', '= 0.26.11'\n"
    // Locked by original Ubuntu Bundler 2.4.20. Ruby-platform artifacts compile for the local CPU.
    val lock = """
        GEM
          remote: https://rubygems.org/
          specs:
            language_server-protocol (3.17.0.6)
            logger (1.7.0)
            prism (1.9.0)
            rbs (4.1.3)
              logger
              prism (>= 1.6.0)
              tsort
            ruby-lsp (0.26.11)
              language_server-protocol (~> 3.17.0)
              prism (>= 1.2, < 2.0)
              rbs (>= 3, < 5)
            tsort (0.2.0)

        PLATFORMS
          ruby

        DEPENDENCIES
          ruby-lsp (= 0.26.11)

        BUNDLED WITH
           2.4.20
    """.trimIndent() + "\n"
    val lockSha256 = MessageDigest.getInstance("SHA-256").digest(lock.toByteArray()).joinToString("") { "%02x".format(it) }
    val nativePaths = listOf("prism-1.9.0/lib/prism/prism.so", "rbs-4.1.3/lib/rbs_extension.so")
    fun requireReviewed(recipe: LocalUbuntuRubyRecipe) {
        require(recipe == LocalUbuntuRubyCatalog.find(recipe.familyId, recipe.version)) { "Unreviewed local Ruby recipe" }
    }
    fun review(recipe: LocalUbuntuRubyRecipe) = buildJsonObject {
        requireReviewed(recipe)
        put("schema", 1); put("familyId", recipe.familyId); put("version", recipe.version); put("command", recipe.command)
        put("rubyAbi", RUBY_ABI); put("bundler", "2.4.20"); put("provenance", PROVENANCE)
        put("gems", buildJsonArray { recipe.gems.forEach { gem -> add(buildJsonObject {
            put("name", gem.name); put("version", gem.version); put("sha256", gem.sha256); put("bytes", gem.sizeBytes)
        }) } })
    }

    fun environment(guestRoot: String): List<String> {
        require(guestRoot.startsWith('/') && guestRoot.none { it.code < 32 || it.code == 127 } &&
            guestRoot.split('/').none { it == "." || it == ".." }) { "Unsafe Ruby package path" }
        return listOf("/usr/bin/env") + listOf("GEM_HOME", "GEM_PATH", "RUBYOPT", "RUBYLIB", "BUNDLER_VERSION",
            "BUNDLE_BIN", "BUNDLE_WITH", "BUNDLE_WITHOUT", "BUNDLE_DEPLOYMENT", "BUNDLE_FORCE_RUBY_PLATFORM",
            "BUNDLE_GEMFILE", "BUNDLE_PATH", "BUNDLE_APP_CONFIG", "BUNDLE_USER_HOME").flatMap { listOf("-u", it) } +
            listOf("PATH=/usr/bin:/bin", "BUNDLE_IGNORE_CONFIG=true", "BUNDLE_FROZEN=true", "BUNDLE_DISABLE_SHARED_GEMS=true",
                "BUNDLE_GEMFILE=$guestRoot/payload/ruby/Gemfile", "BUNDLE_PATH=$guestRoot/payload/ruby/gems",
                "BUNDLE_APP_CONFIG=/root/.cache/droide/ruby-lsp/0.26.11/bundle", "BUNDLE_USER_HOME=/root/.cache/droide/ruby-lsp/0.26.11/user")
    }
    fun server(guestRoot: String): List<String> = environment(guestRoot) + listOf("/usr/bin/bundle", "exec", "ruby-lsp")
    fun health(guestRoot: String): List<String> = listOf("/usr/bin/node", "$guestRoot/payload/${LocalUbuntuNpmLspHealth.FILE_NAME}") + server(guestRoot)
    fun guestWrapper(guestRoot: String) = buildString {
        append("#!/bin/sh\nset -eu\n")
        append("if [ \"${'$'}{1-}\" = --droide-health ] && [ \"${'$'}#\" -eq 1 ]; then exec ")
            .append(health(guestRoot).joinToString(" ", transform = ::q)).append("; fi\n")
        append("exec ").append(server(guestRoot).joinToString(" ", transform = ::q)).append(" \"${'$'}@\"\n")
    }
    fun hostWrapper(launcher: String, guestRoot: String) =
        "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(launcher)} /bin/sh ${q("$guestRoot/payload/bin/ruby-lsp")} \"${'$'}@\"\n"

    suspend fun verifyFiles(root: File, recipe: LocalUbuntuRubyRecipe): Boolean {
        requireReviewed(recipe)
        fun original(relative: String, expected: String): Boolean {
            val file = File(root, relative)
            return file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() == expected.toByteArray().size.toLong() && file.readText() == expected
        }
        if (!original("payload/ruby/Gemfile", gemfile) || !original("payload/ruby/Gemfile.lock", lock) ||
            !original("payload/${LocalUbuntuNpmLspHealth.FILE_NAME}", LocalUbuntuNpmLspHealth.script) ||
            !original("RECIPE_LOCK.json", review(recipe).toString())) return false
        for (gem in recipe.gems) {
            val archive = File(root, "payload/ruby/vendor/cache/${gem.fileName}")
            if (!archive.isFile || PathSecurity.isSymbolicLink(archive) || archive.length() != gem.sizeBytes ||
                LocalUbuntuNpmPolicy.sha256(archive) != gem.sha256) return false
        }
        return true
    }
    fun admitNativeLibraries(root: File) {
        nativePaths.forEach { LinuxArm64ElfAdmission.requireAarch64SharedObject(File(root, "payload/ruby/gems/ruby/$RUBY_ABI/gems/$it")) }
    }

    fun verifyIntegration(record: ManagedPackageRecord, appRoot: String): Boolean = runCatching {
        val recipe = requireNotNull(LocalUbuntuRubyCatalog.find(record.familyId, record.version))
        val final = "$appRoot/packages/${recipe.familyId}/${recipe.version}/arm64-v8a"
        require(record.installRoot == final && record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
            record.environment.isEmpty() && record.artifactSha256 == lockSha256 && record.pathEntries == listOf("$final/payload/host-bin") &&
            record.commands == mapOf(recipe.command to "$final/payload/host-bin/${recipe.command}") &&
            record.healthChecks == listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}", listOf("--droide-health"))) &&
            record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.LIBC_KEY] == DeclarativeLibcCompatibility.GLIBC.name)
        val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final, "$appRoot/packages/"))
        val launcher = "$appRoot/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
        fun wrapper(relative: String, expected: String): Boolean {
            val file = File(final, relative)
            return file.isFile && !PathSecurity.isSymbolicLink(file) && file.canExecute() &&
                file.length() == expected.toByteArray().size.toLong() && file.readText() == expected
        }
        wrapper("payload/bin/${recipe.command}", guestWrapper(guest)) &&
            wrapper("payload/host-bin/${recipe.command}", hostWrapper(launcher, guest))
    }.getOrDefault(false)
    private fun q(value: String) = LocalExecutionSubstrate.shellQuote(value)
}
