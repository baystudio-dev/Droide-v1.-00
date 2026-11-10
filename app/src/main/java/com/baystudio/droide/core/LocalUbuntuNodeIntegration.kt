package com.baystudio.droide.core

import java.io.File

internal object LocalUbuntuNodeIntegration {
    fun verifyWrappers(record: ManagedPackageRecord, appRoot: String): Boolean = runCatching {
        require(record.familyId == "runtime.node" && record.commands.keys == setOf("node", "npm", "npx"))
        require(record.healthChecks == listOf("node", "npm", "npx").map { ManagedPackageHealthCheck("host-bin/$it", listOf("--version")) })
        val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(record.installRoot, "$appRoot/packages/"))
        val launcher = "$appRoot/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
        for (command in record.commands.keys) {
            fun read(relative: String): String {
                val file = File(record.installRoot, relative)
                require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.canExecute() && file.length() in 1..8_192)
                return file.readText()
            }
            require(read("payload/bin/$command") == LocalUbuntuNodeDistribution.guestWrapper(record.version, command, "$guest/payload/tree"))
            require(read("payload/host-bin/$command") == LocalUbuntuNodeDistribution.hostWrapper(launcher, "$guest/payload/bin/$command"))
        }
        true
    }.getOrDefault(false)
}
