package com.baystudio.droide.core

/** Reuse installed packages and authenticated indexes; never override native package locks. */
internal object FoundryUbuntuAptPolicy {
    const val OPTIONS = "--no-remove -o Acquire::Retries=1 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 " +
        "-o DPkg::Lock::Timeout=5 -o APT::Update::Error-Mode=any -o Acquire::Languages=none"

    fun installedPackagesCommand(packages: List<String>): List<String> {
        require(packages.isNotEmpty() && packages.all { it.matches(Regex("[a-z0-9][a-z0-9+.-]*")) })
        return listOf("dpkg-query", "-W", "-f=\${Package}\\t\${Architecture}\\t\${Status}\\n") + packages
    }

    /** One read-only dpkg query instead of one process per prerequisite during layer health. */
    fun installedPackagesHealthCommand(packages: List<String>): String {
        require(packages.distinct().size == packages.size)
        val query = installedPackagesCommand(packages).joinToString(" ", transform = ::quote)
        val validate = "NF != 3 || (\$2 != \"arm64\" && \$2 != \"all\") || \$3 != \"install ok installed\" { bad = 1 } " +
            "END { exit (bad || NR != expected) }"
        return "droide_package_status=\$($query) || exit 1; " +
            "printf '%s\\n' \"\$droide_package_status\" | awk -F '\\t' -v expected=${packages.size} ${quote(validate)}"
    }

    fun installedPackages(result: ExecResult, packages: List<String>): Set<String> = result.output.lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        fields.takeIf { it.size == 3 && it[0] in packages && it[1] in setOf("arm64", "all") && it[2] == "install ok installed" }?.first()
    }.toSet()

    fun lockBusy(result: ExecResult): Boolean = !result.timedOut && result.exitCode != 0 &&
        Regex("(?i)lock").containsMatchIn(result.output) &&
        Regex("(?i)resource temporarily unavailable|locked by [^\\r\\n]*\\bprocess\\b|held by process|is another process using it").containsMatchIn(result.output) &&
        !Regex("(?i)permission denied|read-only file system").containsMatchIn(result.output)

    fun staleArchiveIndex(result: ExecResult): Boolean = result.exitCode != 0 &&
        Regex("(?i)404.*not found|hash sum mismatch").containsMatchIn(result.output)

    fun planCommand(packages: List<String>, planDirectory: String, archivesDirectory: String = "$planDirectory/archives"): String {
        return buildPlan(packages, planDirectory, archivesDirectory, false)
    }

    fun repairPlanCommand(planDirectory: String, archivesDirectory: String): String = buildPlan(emptyList(), planDirectory, archivesDirectory, true)

    private fun buildPlan(packages: List<String>, planDirectory: String, archivesDirectory: String, repair: Boolean): String {
        require((packages.isNotEmpty() || repair) && packages.all { it.matches(Regex("[a-z0-9][a-z0-9+.-]*")) })
        val arguments = packages.joinToString(" ", transform = ::quote)
        val repairFlag = if (repair) "-f " else ""
        val plan = quote("$planDirectory/plan")
        val names = quote("$planDirectory/packages")
        val uris = quote("$planDirectory/uris")
        val archives = quote(archivesDirectory)
        val metadata = quote("$planDirectory/metadata")
        val architecture = quote("$planDirectory/native-architecture")
        return "set -eu; mkdir -p ${quote(planDirectory)} ${quote("$archivesDirectory/partial")}; " +
            "apt-get $OPTIONS ${repairFlag}-s --no-install-recommends install $arguments > $plan; " +
            "grep '^Inst ' $plan | cut -d' ' -f2 > $names; " +
            "apt-get $OPTIONS ${repairFlag}--print-uris -y --no-install-recommends -o Dir::Cache::archives=$archives install $arguments > $uris; " +
            "dl=\$(awk ${quote(DOWNLOAD_SIZE_AWK)} $uris); " +
            // Materialize metadata before awk so an apt-cache failure cannot disappear in a pipe.
            "xargs -r apt-cache show --no-all-versions < $names > $metadata; " +
            "apt-config shell droide_native_arch APT::Architecture > $architecture; " +
            "droide_native_arch=\$(sed -nE ${quote(ARCHITECTURE_SED)} $architecture); " +
            "case \"\$droide_native_arch\" in ''|*[!a-z0-9-]*) printf 'Cannot determine APT native architecture\\n' >&2; exit 1;; esac; " +
            "ik=\$(awk -v native_arch=\"\$droide_native_arch\" ${quote(INSTALLED_SIZE_AWK)} $names $metadata); " +
            "printf 'download=%s\\ninstalled_kib=%s\\n' \"\$dl\" \"\$ik\""
    }

    /** Only transaction-owned files are cleaned; terminal APT keeps the authenticated list cache. */
    fun cleanupCommand(transactionRoot: String): String = "rm -rf ${quote(transactionRoot)}"

    private fun quote(value: String): String {
        require('\u0000' !in value) { "NUL in Ubuntu APT path" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private val ARCHITECTURE_SED = """s/^droide_native_arch='([a-z0-9][a-z0-9-]*)'${'$'}/\1/p"""

    // Parse only APT's quoted URI records, never its human-readable package counts.
    // The caller's transaction cache determines which archives still need downloading.
    private val DOWNLOAD_SIZE_AWK = """
        /^'/ {
            if (NF != 4 || ${'$'}1 !~ /^'[^']+'${'$'}/ || ${'$'}3 !~ /^[0-9]+${'$'}/) { bad = 1; exit 1 }
            total += ${'$'}3
        }
        END { if (bad) exit 1; printf "%.0f\n", total }
    """.trimIndent()

    // Preserve the former per-package first-result behavior, including explicit multiarch names.
    // Architecture can occur after Installed-Size, so consume complete records before summing.
    private val INSTALLED_SIZE_AWK = """
        function add(key) {
            if (key in wanted && !(key in seen)) {
                if (malformed || (size != "" && size !~ /^[0-9]+${'$'}/)) { bad = 1; exit 1 }
                total += (size == "" ? 0 : size)
                seen[key] = 1
            }
        }
        function finish() {
            if (package != "") {
                count = split(requested[package], keys, " ")
                for (i = 1; i <= count; i++) {
                    split(keys[i], parts, ":")
                    wanted_arch = (parts[2] == "" ? native_arch : parts[2])
                    if (architecture == wanted_arch || architecture == "all") add(keys[i])
                }
            }
            package = ""; architecture = ""; size = ""; malformed = 0
        }
        NR == FNR {
            wanted[${'$'}1] = 1
            split(${'$'}1, parts, ":")
            requested[parts[1]] = requested[parts[1]] ${'$'}1 " "
            next
        }
        ${'$'}1 == "Package:" { finish(); package = ${'$'}2 }
        ${'$'}1 == "Installed-Size:" { size = ${'$'}2; malformed = NF > 2 }
        ${'$'}1 == "Architecture:" { architecture = ${'$'}2 }
        /^${'$'}/ { finish() }
        END { finish(); if (bad) exit 1; printf "%.0f\n", total }
    """.trimIndent()
}
