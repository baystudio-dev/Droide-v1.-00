package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored built-in-only peers. These tests never install or import Prisma/npm/WASM. */
class LocalUbuntuPrismaLspHealthTest {
    @Test fun sourceIdentityAndDeadlineArePinned() {
        assertEquals("prisma-lsp-health.cjs", LocalUbuntuPrismaLspHealth.FILE_NAME)
        assertEquals(45_000L, LocalUbuntuPrismaLspHealth.TIMEOUT_MS)
        assertEquals(sha(LocalUbuntuPrismaLspHealth.script), LocalUbuntuPrismaLspHealth.SOURCE_SHA256)
        assertEquals("6c2805fd4a874d03d89aaceb931c1ff13376ffab6284127cad1fd062fcb3356c", LocalUbuntuPrismaLspHealth.CONTRACT_SHA256)
        assertTrue(LocalUbuntuPrismaLspHealth.script.contains("process.execArgv.length === 0"))
        assertTrue(LocalUbuntuPrismaLspHealth.script.contains("process.arch === 'arm64'"))
        assertFalse(LocalUbuntuPrismaLspHealth.script.contains("DROIDE_TEST"))
    }

    @Test fun exactTranscriptPrivateEnvironmentAndFragmentedUtf8Succeed() {
        for (mode in listOf("responsive", "fragmented", "logs", "private-file", "private-symlink", "trailing-stderr"))
            withProbe(mode) { probe ->
                val result = probe.run()
                assertEquals("$mode: ${result.output}", 0, result.exitCode)
                assertTrue(result.output.contains("Prisma zero-document initialize/shutdown OK"))
                assertEquals(listOf("initialize", "initialized", "shutdown", "exit"), probe.observation()!!["methods"]!!.jsonArray.map { it.jsonObject["method"]!!.jsonPrimitive.content })
                probe.assertClean()
            }
    }

    @Test fun malformedFramingDuplicateKeysAndInvalidUtf8FailClosed() {
        for (mode in listOf("duplicate-length", "unknown-header", "duplicate-type", "wrong-type", "negative-length",
            "zero-length", "leading-zero", "oversized-length", "bare-lf", "ascii-control", "non-ascii-header", "duplicate-json",
            "escaped-duplicate-json", "duplicate-nested-json", "invalid-utf8", "utf8-bom", "batch-json", "deep-json", "value-flood",
            "stdout-log", "header-flood")) withProbe(mode) { probe ->
            val result = probe.run(); assertNotEquals("$mode: ${result.output}", 0, result.exitCode); probe.assertClean()
        }
    }

    @Test fun capabilitiesCallbacksErrorsAndTrailingMessagesCannotPass() {
        for (mode in listOf("premature-shutdown", "callback", "unknown-notification", "bad-notification", "error-log", "error-response", "wrong-id",
            "missing-capability", "extra-capability", "empty-capabilities", "mixed-envelope", "duplicate-response", "bad-shutdown",
            "trailing-frame", "trailing-partial", "nonzero-exit", "close-early", "close-input", "stderr-flood", "message-flood"))
            withProbe(mode) { probe ->
                val result = probe.run(); assertNotEquals("$mode: ${result.output}", 0, result.exitCode)
                assertFalse(result.output.contains("SECRET-MUST-NOT-LEAK")); probe.assertClean()
            }
    }

    @Test fun timeoutsAndCancellationRetireOwnedServerAndPrivateContext() {
        for (mode in listOf("initialize-hang", "shutdown-hang", "exit-hang")) withProbe(mode) { probe ->
            val started = System.nanoTime(); val result = probe.run()
            assertNotEquals("$mode: ${result.output}", 0, result.exitCode)
            assertTrue(result.output.contains("timed out"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 37_000)
            probe.assertClean()
        }
        withProbe("initialize-hang") { probe ->
            val process = probe.start()
            try {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (probe.observation() == null && System.nanoTime() < until) Thread.sleep(10)
                assertNotNull("Authored child did not start", probe.observation())
                process.destroy(); assertTrue(process.waitFor(8, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue())
                probe.assertClean()
            } finally { process.destroyForcibly() }
        }
    }

    @Test fun successfulHandshakeKillsTermIgnoringOwnedWorker() = withProbe("worker") { probe ->
        val result = probe.run(); assertEquals(result.output, 0, result.exitCode); probe.assertClean()
    }

    @Test fun unsafeOrOverBudgetPrivateCleanupPreservesResidueAndFails() {
        for (mode in listOf("replace-private-directory", "cleanup-flood")) withProbe(mode) { probe ->
            val result = probe.run(); assertNotEquals(result.output, 0, result.exitCode)
            assertTrue(probe.privateRoot()!!.exists()); assertEquals("do not change", File(probe.root, "outside-marker").readText())
            assertFalse(isRunning(probe.observation()!!["pid"]!!.jsonPrimitive.long))
        }
    }

    @Test fun preflightRejectsStaleAuthorityRecipeHashMarkerAndSealBeforeSpawn() {
        val edits: List<(Probe) -> Unit> = listOf(
            { it.authority.writeText(it.authority.readText().dropLast(1) + ",\"unexpected\":true}") },
            { it.authority.writeText(it.authority.readText().replace("\"schema\":1", "\"schema\":1,\"schema\":1")) },
            { it.authority.writeText(it.authority.readText().replace("prisma-managed-public-root-launch-v1", "unreviewed")) },
            { it.authority.writeText(it.authority.readText().replace("\"nodeDependencyKeyOrNull\":null", "\"nodeDependencyKeyOrNull\":\"runtime.node@0.0.0\""))
              it.receipt.writeText(it.receipt.readText().replace("\"nodeDependencyKeyOrNull\":null", "\"nodeDependencyKeyOrNull\":\"runtime.node@0.0.0\"")) },
            { File(it.root, "DROIDE_ACTIVATION_ID").writeText("00000000-0000-0000-0000-000000000000") },
            { it.receipt.writeText(it.receipt.readText().replace("\"allowLifecycleScripts\":false", "\"allowLifecycleScripts\":true")) },
            { it.receipt.writeText(it.receipt.readText().replace("\"magicast\":\"0.5.4\"", "\"magicast\":\"0.5.5\"")) },
            { it.receipt.writeText(it.receipt.readText().replace("prisma-zero-document-source-path-health-v1", "older-health")) },
            { it.launcher.appendText("\n// tampered\n") },
            { it.helper.appendText("\n// tampered\n") },
            { File(it.root, "payload/npm/package-lock.json").appendText(" ") },
            { File(it.root, "NPM_TREE_SEAL.json").writeText("{}") },
            { Files.createSymbolicLink(File(it.root, "NPM_TREE_SEAL.json").toPath(), File(it.root, "missing").toPath()) },
            { val target = File(it.root, "authority-copy.json"); it.authority.renameTo(target); Files.createSymbolicLink(it.authority.toPath(), target.toPath()) },
        )
        for (edit in edits) withProbe("responsive") { probe ->
            edit(probe); val result = probe.run(); assertNotEquals(result.output, 0, result.exitCode); assertNull(probe.observation())
        }
    }

    @Test fun npmBuildMetadataIsAdmittedWithoutRelaxingRuntimeIdentity() = withProbe("responsive") { probe ->
        probe.receipt.writeText(probe.receipt.readText().replace("10.9.0", "10.9.0+authored.fixture"))
        val result = probe.run(); assertEquals(result.output, 0, result.exitCode); probe.assertClean()
    }

    @Test fun directUnchangedHelperRejectsForeignArchitectureAndRuntimeFlags() {
        if (architecture != "arm64") withProbe("responsive", hostArchitectureFixture = false) { probe ->
            val result = probe.run(); assertNotEquals(result.output, 0, result.exitCode); assertNull(probe.observation())
        }
        withProbe("responsive") { probe ->
            val process = probe.start(nodeFlags = listOf("--no-warnings"))
            try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue()); assertNull(probe.observation()) }
            finally { process.destroyForcibly() }
        }
    }

    private data class Result(val exitCode: Int, val output: String)
    private class Probe(val root: File, mode: String, hostArchitectureFixture: Boolean) {
        val helper = File(root, "payload/prisma-lsp-health.cjs")
        val launcher = File(root, "payload/npm/droide-prisma-legacy.cjs")
        val authority = File(root, "PRISMA_LAUNCH_AUTHORITY.json")
        val receipt = File(root, "RECIPE_LOCK.json")
        init {
            check(launcher.parentFile.mkdirs())
            // Host-only fixture translation changes one architecture predicate in a COPY. No production bypass exists.
            val script = if (hostArchitectureFixture) LocalUbuntuPrismaLspHealth.script.replace(
                "process.arch === 'arm64'", "process.arch === '$architecture'") else LocalUbuntuPrismaLspHealth.script
            helper.writeText(script); launcher.writeText(FIXTURE.replace("__MODE__", JsonPrimitive(mode).toString()))
            val lock = File(root, "payload/npm/package-lock.json").apply { writeText("{\"authoredFixtureOnly\":true}") }
            File(root, "DROIDE_ACTIVATION_ID").writeText(ACTIVATION)
            File(root, "outside-marker").writeText("do not change")
            val profile = buildJsonObject {
                put("revision", POLICY); put("closureContractSha256", CLOSURE)
                put("launcherRelativePath", "payload/npm/droide-prisma-legacy.cjs"); put("launcherSha256", sha(launcher.readText()))
                put("healthRelativePath", "payload/prisma-lsp-health.cjs"); put("healthRevision", LocalUbuntuPrismaLspHealth.REVISION)
                put("healthContractSha256", LocalUbuntuPrismaLspHealth.CONTRACT_SHA256); put("healthSourceSha256", sha(script))
                put("nodeCommand", node); put("nodeVersion", nodeVersion); put("nodeDependencyKeyOrNull", JsonNull)
            }
            authority.writeText(buildJsonObject {
                put("schema", 1); put("familyId", "lsp.prisma-language-server"); put("version", "31.13.0")
                put("policyRevision", POLICY); put("closureContractSha256", CLOSURE)
                put("finalGuestRoot", root.path + "-final"); put("stageGuestRoot", root.path); put("activationId", ACTIVATION)
                put("nodeCommand", node); put("nodeVersion", nodeVersion); put("nodeDependencyKeyOrNull", JsonNull)
                put("launcherSha256", sha(launcher.readText())); put("healthSourceSha256", sha(script)); put("lockSha256", sha(lock.readText()))
            }.toString())
            val dependencies = LocalUbuntuPrismaLspHealth.script.substringAfter("const DEPENDENCIES = ").substringBefore(";\n")
            receipt.writeText(buildJsonObject {
                put("schema", 1); put("familyId", "lsp.prisma-language-server"); put("version", "31.13.0")
                put("packageName", "@prisma/language-server"); put("packageVersion", "31.13.0")
                put("registryIntegrity", "sha512-3ATe7MSnR4mFBCaiDyvNDrjWtfbz15y0guzGQ8oXOzJDBnHE4AzVxGobP7PgrWxsqry1CCY0x7/FfExZzAHKAA==")
                put("nodeMajor", nodeVersion.substringBefore('.').toInt()); put("npmVersion", "10.9.0")
                put("provenanceUrl", "https://github.com/prisma/language-tools"); put("nodeCommand", node)
                put("additionalDependencies", Json.parseToJsonElement(dependencies)); put("allowLifecycleScripts", false); put("launchProfile", profile)
            }.toString())
        }
        fun start(nodeFlags: List<String> = emptyList()): Process = ProcessBuilder(listOf(node) + nodeFlags + listOf(helper.path, node, launcher.path, "--stdio"))
            .directory(root).redirectErrorStream(true).apply {
                environment()["HOME"] = "/authored-secret-home"; environment()["NODE_PATH"] = "/authored-poison-path"
                environment().remove("NODE_OPTIONS"); environment()["PRIVATE_SECRET"] = "authored-canary"
                environment()["DROIDE_PROCESS_LEASE"] = "0123456789abcdef0123"
            }.start()
        fun run(): Result {
            val process = start()
            try { assertTrue("Probe exceeded outer deadline", process.waitFor(45, TimeUnit.SECONDS))
                return Result(process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8)) }
            finally { process.destroyForcibly() }
        }
        fun observation(): JsonObject? = File(root, "observations.json").takeIf { it.isFile }?.let {
            runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
        fun privateRoot(): File? = observation()?.get("cwd")?.jsonPrimitive?.content?.let { File(it).parentFile }
        fun assertClean() {
            val seen = observation() ?: return
            assertFalse("Owned root server survived", isRunning(seen["pid"]!!.jsonPrimitive.long))
            seen["workerPid"]?.jsonPrimitive?.long?.let { assertFalse("Owned worker survived", isRunning(it)) }
            assertFalse("Private directory was retained", privateRoot()!!.exists())
            assertEquals("do not change", File(root, "outside-marker").readText())
        }
    }
    private fun withProbe(mode: String, hostArchitectureFixture: Boolean = true, action: (Probe) -> Unit) {
        val root = Files.createTempDirectory("droide-prisma-fixture-").toFile()
        val probe = Probe(root, mode, hostArchitectureFixture)
        try { action(probe) } finally {
            // Test-only removal of deliberately preserved authored residue, following no symbolic links.
            probe.privateRoot()?.takeIf { it.path.startsWith("/tmp/droide-prisma-health-") }?.let(::removeFixture)
            removeFixture(root)
        }
    }
    private companion object {
        const val ACTIVATION = "12345678-1234-1234-1234-123456789abc"
        const val POLICY = "prisma-managed-public-root-launch-v1"
        const val CLOSURE = "6de1f7e4710d74447f40b56e700e2f6d72fec0e7daad211f72eaede4ef0d202d"
        val node: String by lazy { queryNode("process.execPath") }
        val nodeVersion: String by lazy { queryNode("process.versions.node") }
        val architecture: String by lazy { queryNode("process.arch") }
        fun queryNode(expression: String): String {
            val process = ProcessBuilder(System.getProperty("droide.test.node") ?: "node", "-p", expression).start()
            assertTrue("Trusted Node fixture runtime unavailable", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue()); return process.inputStream.bufferedReader().readText().trim()
        }
        fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun isRunning(pid: Long): Boolean = File("/proc/$pid/stat").let { it.isFile && it.readText().substringAfterLast(") ").first() != 'Z' }
        fun removeFixture(file: File) {
            if (Files.isSymbolicLink(file.toPath())) Files.deleteIfExists(file.toPath())
            else if (file.isDirectory) { file.listFiles()?.forEach(::removeFixture); check(file.delete()) }
            else if (file.exists()) check(file.delete())
        }
        val FIXTURE = """
            'use strict';
            // Authored protocol fixture only. It never imports Prisma or any npm package.
            const fs = require('node:fs'), path = require('node:path');
            const { spawn } = require('node:child_process');
            const { pathToFileURL } = require('node:url');
            const root = path.resolve(__dirname, '../..'), mode = __MODE__;
            const authority = JSON.parse(fs.readFileSync(root + '/PRISMA_LAUNCH_AUTHORITY.json'));
            const observations = { pid: process.pid, cwd: process.cwd(), env: process.env, argv: process.argv, methods: [] };
            const privateRoot = path.dirname(process.cwd());
            const save = () => fs.writeFileSync(root + '/observations.json', JSON.stringify(observations));
            const assert = (v, m) => { if (!v) { process.stderr.write('Fixture assertion: ' + m + '\n'); process.exit(8); } };
            assert(JSON.stringify(process.argv.slice(2)) === JSON.stringify(['--droide-prisma-health-child',
              root + '/payload/prisma-lsp-health.cjs', authority.activationId, '--stdio']), 'child argv');
            assert(process.cwd() === privateRoot + '/cwd' && privateRoot.startsWith('/tmp/droide-prisma-health-'), 'private cwd');
            assert(fs.readdirSync(process.cwd()).length === 0, 'empty cwd');
            const envKeys = ['HOME','XDG_CACHE_HOME','XDG_CONFIG_HOME','XDG_DATA_HOME','XDG_STATE_HOME','XDG_RUNTIME_DIR',
              'TMPDIR','TMP','TEMP','PATH','LANG','LC_ALL','TERM'];
            if ('DROIDE_PROCESS_LEASE' in process.env) envKeys.push('DROIDE_PROCESS_LEASE');
            assert(JSON.stringify(Object.keys(process.env).sort()) === JSON.stringify(envKeys.sort()), 'private environment keys');
            assert(process.env.HOME === privateRoot + '/home' && process.env.PATH === '' && process.env.LANG === 'C.UTF-8' &&
              process.env.LC_ALL === 'C.UTF-8' && process.env.TERM === 'dumb', 'private environment values');
            for (const name of ['', '/cwd','/home','/xdg-cache','/xdg-config','/xdg-data','/xdg-state','/xdg-runtime','/tmp'])
              assert((fs.statSync(privateRoot + name).mode & 0o777) === 0o700, 'private directory mode');
            save();
            let buffer = Buffer.alloc(0), sequence = 0, outChain = Promise.resolve();
            const capabilities = { textDocumentSync: 2, definitionProvider: true, documentFormattingProvider: true,
              completionProvider: { resolveProvider: true, triggerCharacters: ['@','"','.'] }, hoverProvider: true,
              renameProvider: true, documentSymbolProvider: true, referencesProvider: true };
            function raw(body, headers) {
              const bytes = Buffer.isBuffer(body) ? body : Buffer.from(body);
              const frame = Buffer.concat([Buffer.from(headers || 'Content-Length: ' + bytes.length + '\r\n\r\n'), bytes]);
              if (mode === 'fragmented') {
                outChain = outChain.then(() => new Promise(resolve => {
                  let offset = 0; const tick = setInterval(() => { process.stdout.write(frame.subarray(offset, ++offset));
                    if (offset === frame.length) { clearInterval(tick); resolve(); } }, 1);
                }));
              } else process.stdout.write(frame);
            }
            const send = value => raw(JSON.stringify(value));
            function malformed() {
              const body = JSON.stringify({jsonrpc:'2.0',id:1,result:{capabilities}});
              const n = Buffer.byteLength(body);
              const cases = {
                'duplicate-length': 'Content-Length: '+n+'\r\ncontent-length: '+n+'\r\n\r\n',
                'unknown-header': 'Content-Length: '+n+'\r\nX-Other: x\r\n\r\n',
                'duplicate-type': 'Content-Length: '+n+'\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\ncontent-type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n',
                'wrong-type': 'Content-Length: '+n+'\r\nContent-Type: text/plain\r\n\r\n',
                'negative-length':'Content-Length: -1\r\n\r\n','zero-length':'Content-Length: 0\r\n\r\n',
                'leading-zero':'Content-Length: 0'+n+'\r\n\r\n','oversized-length':'Content-Length: 1048577\r\n\r\n',
                'bare-lf':'Content-Length: '+n+'\n\n','ascii-control':'Content-Length: '+n+'\r\nX:\x00\r\n\r\n',
                'non-ascii-header':'Content-Length: '+n+'\r\nX: é\r\n\r\n'
              };
              if (cases[mode]) { raw(body, cases[mode]); if (mode === 'bare-lf') process.exit(0); return true; }
              if (mode === 'duplicate-json') { raw(body.replace('"id":1','"id":1,"id":1')); return true; }
              if (mode === 'escaped-duplicate-json') { raw(body.replace('"id":1','"id":1,"\\u0069d":1')); return true; }
              if (mode === 'duplicate-nested-json') { raw(body.replace('"textDocumentSync":2','"textDocumentSync":2,"textDocumentSync":2')); return true; }
              if (mode === 'invalid-utf8') { raw(Buffer.from([0xff])); return true; }
              if (mode === 'utf8-bom') { raw(Buffer.concat([Buffer.from([239,187,191]),Buffer.from(body)])); return true; }
              if (mode === 'batch-json') { raw('['+body+']'); return true; }
              if (mode === 'deep-json') { raw('{"a":'+ '['.repeat(70)+'0'+']'.repeat(70)+'}'); return true; }
              if (mode === 'value-flood') { raw('{"a":['+'0,'.repeat(50000)+'0]}'); return true; }
              if (mode === 'stdout-log') { process.stdout.write('starting server\r\n\r\n'); return true; }
              if (mode === 'header-flood') { process.stdout.write('X'.repeat(8193)); return true; }
              if (mode === 'stderr-flood') { process.stderr.write(Buffer.alloc(4194305,65)); return true; }
              if (mode === 'message-flood') { for (let i=0;i<257;i++) send({jsonrpc:'2.0',method:'window/logMessage',params:{type:3,message:'hello'}}); return true; }
              return false;
            }
            async function handle(message) {
              observations.methods.push(message); save();
              if (sequence === 0) {
                assert(JSON.stringify(message) === JSON.stringify({jsonrpc:'2.0',id:1,method:'initialize',params:{processId:null,
                  rootUri:pathToFileURL(process.cwd()).href,workspaceFolders:null,capabilities:{general:{positionEncodings:['utf-16']},
                  workspace:{configuration:false,workspaceFolders:false},textDocument:{synchronization:{dynamicRegistration:false}}},
                  initializationOptions:{},trace:'off'}}), 'exact initialize');
                sequence++;
                if (mode === 'initialize-hang') { process.on('SIGTERM',()=>{}); setInterval(()=>{},1000); return; }
                if (mode === 'close-early') process.exit(0);
                if (mode === 'close-input') { process.stdin.destroy(); return; }
                if (malformed()) return;
                if (mode === 'callback') { send({jsonrpc:'2.0',id:7,method:'workspace/configuration',params:{items:[]}}); return; }
                if (mode === 'unknown-notification') { send({jsonrpc:'2.0',method:'window/showMessage',params:{type:3,message:'hello'}}); return; }
                if (mode === 'bad-notification') { send({jsonrpc:'2.0',method:'window/logMessage',params:{message:'hello'}}); return; }
                if (mode === 'error-log') { send({jsonrpc:'2.0',method:'window/logMessage',params:{type:1,message:'SECRET-MUST-NOT-LEAK'}}); return; }
                if (mode === 'logs' || mode === 'fragmented') send({jsonrpc:'2.0',method:'window/logMessage',params:{type:3,message:'日本語 ☀'}});
                if (mode === 'error-response') { send({jsonrpc:'2.0',id:1,error:{code:-32603,message:'SECRET-MUST-NOT-LEAK'}}); return; }
                if (mode === 'wrong-id') { send({jsonrpc:'2.0',id:'1',result:{capabilities}}); return; }
                if (mode === 'missing-capability') delete capabilities.hoverProvider;
                if (mode === 'extra-capability') capabilities.codeActionProvider = true;
                if (mode === 'empty-capabilities') { send({jsonrpc:'2.0',id:1,result:{capabilities:{}}}); return; }
                if (mode === 'mixed-envelope') { send({jsonrpc:'2.0',id:1,result:{capabilities},error:{code:0,message:'x'}}); return; }
                if (mode === 'private-file') fs.writeFileSync(privateRoot + '/tmp/file','authored fixture');
                if (mode === 'private-symlink') fs.symlinkSync(root + '/outside-marker',privateRoot + '/tmp/link');
                if (mode === 'replace-private-directory') { fs.rmdirSync(privateRoot+'/home'); fs.symlinkSync(root,privateRoot+'/home'); }
                if (mode === 'cleanup-flood') for(let i=0;i<4097;i++) fs.writeFileSync(privateRoot+'/tmp/f'+i,'');
                if (mode === 'worker') {
                  const worker = spawn(process.execPath,['-e',"process.on('SIGTERM',()=>{});process.send('ready');setInterval(()=>{},1000)"],{stdio:['ignore','ignore','ignore','ipc']});
                  observations.workerPid=worker.pid; save(); worker.unref();
                  await new Promise(resolve=>worker.once('message',()=>{worker.disconnect();resolve();}));
                }
                if (mode === 'premature-shutdown') {
                  const frames = [{jsonrpc:'2.0',id:1,result:{capabilities}},{jsonrpc:'2.0',id:2,result:null}].map(v=>{
                    const body=Buffer.from(JSON.stringify(v));return Buffer.concat([Buffer.from('Content-Length: '+body.length+'\r\n\r\n'),body]);
                  }); process.stdout.write(Buffer.concat(frames)); return;
                }
                send({jsonrpc:'2.0',id:1,result:{capabilities}});
              } else if (sequence === 1) {
                assert(JSON.stringify(message) === JSON.stringify({jsonrpc:'2.0',method:'initialized',params:{}}), 'exact initialized'); sequence++;
              } else if (sequence === 2) {
                assert(JSON.stringify(message) === JSON.stringify({jsonrpc:'2.0',id:2,method:'shutdown'}), 'exact shutdown'); sequence++;
                if(mode==='shutdown-hang'||mode==='premature-shutdown') return;
                send({jsonrpc:'2.0',id:mode==='duplicate-response'?1:2,result:mode==='bad-shutdown'?{}:null});
              } else if (sequence === 3) {
                assert(JSON.stringify(message) === JSON.stringify({jsonrpc:'2.0',method:'exit'}), 'exact exit'); sequence++;
                if (mode==='exit-hang') { process.on('SIGTERM',()=>{}); setInterval(()=>{},1000); return; }
                if (mode==='trailing-frame') send({jsonrpc:'2.0',method:'window/logMessage',params:{type:3,message:'bye'}});
                if (mode==='trailing-partial') process.stdout.write('Content-Length:');
                if (mode==='trailing-stderr') process.stderr.write('authored final stderr');
                process.stdin.on('end',()=>process.exit(mode==='nonzero-exit'?7:0));
              } else process.exit(9);
            }
            process.stdin.on('data',chunk=>{
              buffer=Buffer.concat([buffer,chunk]);
              while(true){const end=buffer.indexOf('\r\n\r\n');if(end<0)return;
                const length=Number(/Content-Length: (\d+)/.exec(buffer.subarray(0,end).toString())[1]);
                if(buffer.length<end+4+length)return;
                const message=JSON.parse(buffer.subarray(end+4,end+4+length));buffer=buffer.subarray(end+4+length);
                Promise.resolve(handle(message)).catch(()=>process.exit(10));
              }
            });
        """.trimIndent() + "\n"
    }
}
