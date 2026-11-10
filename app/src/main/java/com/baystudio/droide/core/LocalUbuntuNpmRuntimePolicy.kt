package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Evaluate the complete runtime version with npm's own semver implementation before transfer. */
internal object LocalUbuntuNpmRuntimePolicy {
    fun engine(metadata: JsonObject): String? = metadata["engines"]?.jsonObject?.get("node")?.jsonPrimitive?.contentOrNull
        ?.also { require(it.length in 1..2_000 && it.none { c -> c.code < 32 || c.code == 127 }) { "Invalid publisher Node.js engine constraint" } }

    fun command(minimumMajor: Int, engine: String?): List<String> {
        require(minimumMajor in 1..100)
        engine?.let { require(it.length in 1..2_000 && it.none { c -> c.code < 32 || c.code == 127 }) }
        return listOf("node", "-e", script, minimumMajor.toString(), engine.orEmpty())
    }

    fun compatible(output: String, requireArm64: Boolean = true): Boolean = runCatching {
        val runtime = Json.parseToJsonElement(output.trim()).jsonObject
        runtime["compatible"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull == true &&
            runtime["platform"]?.jsonPrimitive?.content == "linux" &&
            (!requireArm64 || runtime["arch"]?.jsonPrimitive?.content == "arm64")
    }.getOrDefault(false)

    /** Package engine compatibility is probed separately; a dependency must not be tied to Node22. */
    fun admitsDependency(recipe: WorkstationInstallRecipe, record: ManagedPackageRecord): Boolean =
        record.familyId == "runtime.node" && record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name &&
            record.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) &&
            record.version.substringBefore('.').toIntOrNull()?.let { it >= recipe.minimumNodeMajor } == true

    fun matchesManagedNode(record: ManagedPackageRecord, executable: String, appRoot: String): Boolean {
        if (record.familyId != "runtime.node" || record.scope != ExecutionScope.LOCAL_LINUX_ARM64.name) return false
        val expected = LocalLinuxPackageEnvironment.guestPath(
            "${record.installRoot}/payload/tree/node-v${record.version}-linux-arm64/bin/node", "$appRoot/packages/",
        ) ?: return false
        return executable == expected
    }

    val script = """
        'use strict';
        const fs = require('node:fs'), path = require('node:path');
        const { createRequire } = require('node:module');
        const [minimum, engine] = process.argv.slice(1);
        let compatible = Number.parseInt(process.versions.node, 10) >= Number(minimum);
        if (engine) {
          const candidates = [
            path.resolve(path.dirname(process.execPath), '..', 'lib/node_modules/npm/package.json'),
            '/usr/share/nodejs/npm/package.json'
          ];
          let semver;
          for (const entry of candidates) {
            if (!fs.existsSync(entry)) continue;
            try { semver = createRequire(entry)('semver'); break; } catch (_) {}
          }
          if (!semver || typeof semver.satisfies !== 'function') throw new Error('Cannot verify Node.js engine with installed npm');
          compatible = compatible && semver.satisfies(process.versions.node, engine);
        }
        console.log(JSON.stringify({version:process.versions.node,major:Number.parseInt(process.versions.node,10),platform:process.platform,arch:process.arch,compatible}));
    """.trimIndent().lineSequence().joinToString(" ")
}
