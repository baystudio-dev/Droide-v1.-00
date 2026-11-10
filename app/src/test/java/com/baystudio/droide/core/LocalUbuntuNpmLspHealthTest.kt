package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuNpmLspHealthTest {
    @Test fun originalServerMustInitializeShutdownAndExitWithFragmentedUtf8() = withProbe { probe ->
        val result = probe.run("responsive")
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("initialize/shutdown OK"))
    }

    @Test fun syntaxValidServerWithMissingRuntimeDependencyIsRejected() = withProbe { probe ->
        val result = probe.run("missing-dependency")
        assertNotEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("Cannot find module"))
        assertFalse(result.output.contains("initialize/shutdown OK"))
    }

    @Test fun originalServerMayFinishItsBoundedCleanupAfterShutdown() = withProbe { probe ->
        val result = probe.run("delayed-exit")
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("initialize/shutdown OK"))
    }

    @Test fun reviewedLuaShutdownExceptionIsExplicitAndLimitedToItsBooleanResponse() = withProbe { probe ->
        assertNotEquals(0, probe.run("bad-shutdown").exitCode)
        assertNotEquals(0, probe.run("bad-shutdown", reviewedLua = true).exitCode)
        assertNotEquals(0, probe.run("lua-shutdown").exitCode)
        assertEquals(0, probe.run("lua-shutdown", reviewedLua = true).exitCode)
        assertNotEquals(0, probe.run("nonempty-shutdown", reviewedLua = true).exitCode)
    }

    @Test fun reviewedJdtShutdownExceptionIsExplicitAndLimitedToItsEmptyObject() = withProbe { probe ->
        assertNotEquals(0, probe.run("bad-shutdown").exitCode)
        assertEquals(0, probe.run("bad-shutdown", reviewedJdt = true).exitCode)
        assertNotEquals(0, probe.run("lua-shutdown", reviewedJdt = true).exitCode)
        assertNotEquals(0, probe.run("nonempty-shutdown", reviewedJdt = true).exitCode)
    }

    @Test fun wrongIdentifiersErrorsAndMissingCapabilitiesCannotPublishReady() = withProbe { probe ->
        for (mode in listOf("wrong-id", "error", "no-capabilities", "array-capabilities", "bad-shutdown")) {
            val result = probe.run(mode)
            assertNotEquals("$mode: ${result.output}", 0, result.exitCode)
            assertFalse(result.output.contains("initialize/shutdown OK"))
        }
    }

    @Test fun invalidFramingUtf8AndSpuriousStdoutCannotPassAsLsp() = withProbe { probe ->
        for (mode in listOf("spurious-stdout", "duplicate-length", "negative-length", "oversized-length", "invalid-utf8")) {
            val result = probe.run(mode)
            assertNotEquals("$mode: ${result.output}", 0, result.exitCode)
            assertFalse(result.output.contains("initialize/shutdown OK"))
        }
    }

    @Test fun initializeHangIsBoundedAndItsOriginalProcessIsKilled() = withProbe { probe ->
        val before = System.nanoTime()
        val result = probe.run("hang")
        assertNotEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("initialize timed out"))
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 22)
        assertFalse("Timed-out server survived", isRunning(probe.pid("server")))
    }

    @Test fun shutdownAndExitHangsAreBoundedAndKilled() = withProbe { probe ->
        for (mode in listOf("shutdown-hang", "exit-hang")) {
            val result = probe.run(mode)
            assertNotEquals(result.output, 0, result.exitCode)
            assertTrue(result.output.contains("timed out"))
            assertFalse("$mode server survived", isRunning(probe.pid("server")))
        }
    }

    @Test fun successfulHandshakeAlsoCleansLanguageServerWorkerProcess() = withProbe { probe ->
        val result = probe.run("worker")
        assertEquals(result.output, 0, result.exitCode)
        assertFalse("Server worker survived health check", isRunning(probe.pid("worker")))
    }

    @Test fun canceledHealthClientCleansTheOriginalServer() = withProbe { probe ->
        val process = probe.start("hang")
        try {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!File(probe.root, "server.pid").isFile && System.nanoTime() < limit) Thread.sleep(10)
            assertTrue("Fixture never started", File(probe.root, "server.pid").isFile)
            process.destroy()
            assertTrue("Canceled probe did not terminate", process.waitFor(5, TimeUnit.SECONDS))
            assertNotEquals(0, process.exitValue())
            assertFalse("Canceled server survived", isRunning(probe.pid("server")))
        } finally { process.destroyForcibly() }
    }

    private data class Result(val exitCode: Int, val output: String)

    private class Probe(val root: File) {
        private val helper = File(root, "health.cjs").apply { writeText(LocalUbuntuNpmLspHealth.script) }
        private val server = File(root, "server.cjs").apply { writeText(FIXTURE) }
        private val node = System.getProperty("droide.test.node") ?: "node"
        fun start(mode: String, reviewedLua: Boolean = false, reviewedJdt: Boolean = false): Process = ProcessBuilder(listOf(node, helper.path) +
            (if (reviewedLua) listOf("--droide-reviewed-luals-shutdown") else if (reviewedJdt) listOf("--droide-reviewed-jdtls-shutdown") else emptyList()) + listOf(node, server.path, mode, root.path))
            .directory(root).redirectErrorStream(true).start()
        fun run(mode: String, reviewedLua: Boolean = false, reviewedJdt: Boolean = false): Result {
            val process = start(mode, reviewedLua, reviewedJdt)
            try {
                assertTrue("Health probe exceeded its outer timeout", process.waitFor(22, TimeUnit.SECONDS))
                return Result(process.exitValue(), process.inputStream.readBytes().toString(Charsets.UTF_8))
            } finally { process.destroyForcibly() }
        }
        fun pid(kind: String): Long = File(root, "$kind.pid").readText().trim().toLong()
    }

    private fun withProbe(action: (Probe) -> Unit) {
        val root = Files.createTempDirectory("droide-lsp-health-").toFile()
        try { action(Probe(root)) } finally { root.deleteRecursively() }
    }

    private fun isRunning(pid: Long): Boolean {
        // A killed orphan may briefly await the host init's reap; it cannot execute.
        val stat = File("/proc/$pid/stat")
        return stat.isFile && stat.readText().substringAfterLast(") ").first() != 'Z'
    }

    private companion object {
        val FIXTURE = """
            'use strict';
            const fs = require('node:fs');
            const {spawn} = require('node:child_process');
            const [mode, root] = process.argv.slice(2);
            fs.writeFileSync(root + '/server.pid', String(process.pid));
            if (mode === 'missing-dependency') require('droide-missing-dependency-health-fixture');
            if (mode === 'hang') { process.on('SIGTERM', () => {}); setInterval(() => {}, 1000); }
            let buffer = Buffer.alloc(0), initialized = false;
            function send(message, fragmented = false) {
              const body = Buffer.from(JSON.stringify(message));
              const frame = Buffer.concat([Buffer.from('Content-Length: ' + body.length + '\r\n\r\n'), body]);
              if (fragmented) {
                let offset = 0;
                const tick = setInterval(() => {
                  process.stdout.write(frame.subarray(offset, offset + 3)); offset += 3;
                  if (offset >= frame.length) clearInterval(tick);
                }, 1);
              } else process.stdout.write(frame);
            }
            function handle(message) {
              if (message.method === 'initialize') {
                if (mode === 'hang') return;
                if (mode === 'spurious-stdout') { process.stdout.write('Starting server!\r\n\r\n'); return; }
                if (mode === 'duplicate-length') { process.stdout.write('Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}'); return; }
                if (mode === 'negative-length') { process.stdout.write('Content-Length: -1\r\n\r\n'); return; }
                if (mode === 'oversized-length') { process.stdout.write('Content-Length: 4294967296\r\n\r\n'); return; }
                if (mode === 'invalid-utf8') { process.stdout.write(Buffer.from([67,111,110,116,101,110,116,45,76,101,110,103,116,104,58,32,49,13,10,13,10,255])); return; }
                if (mode === 'error') { send({jsonrpc:'2.0', id:message.id, error:{code:-32603,message:'Broken runtime'}}); return; }
                if (mode === 'no-capabilities') { send({jsonrpc:'2.0', id:message.id, result:{}}); return; }
                if (mode === 'array-capabilities') { send({jsonrpc:'2.0', id:message.id, result:{capabilities:[]}}); return; }
                if (mode === 'worker') {
                  const worker = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {stdio:'ignore'});
                  fs.writeFileSync(root + '/worker.pid', String(worker.pid)); worker.unref();
                }
                send({jsonrpc:'2.0', method:'window/logMessage', params:{message:'server ready'}});
                send({jsonrpc:'2.0', id:mode === 'wrong-id' ? 999 : message.id,
                  result:{capabilities:{textDocumentSync:1}, serverInfo:{name:'日本語 server'}}}, mode === 'responsive');
              } else if (message.method === 'initialized') {
                initialized = true;
                send({jsonrpc:'2.0', id:'config', method:'workspace/configuration', params:{items:[{section:'fixture'}]}});
              } else if (message.method === 'shutdown') {
                if (!initialized) { process.exit(2); return; }
                if (mode === 'shutdown-hang') return;
                send({jsonrpc:'2.0', id:message.id, result:mode === 'lua-shutdown' ? true : mode === 'bad-shutdown' ? {} : mode === 'nonempty-shutdown' ? {unexpected:true} : null});
              } else if (message.method === 'exit') {
                if (mode === 'exit-hang') { process.on('SIGTERM', () => {}); setInterval(() => {}, 1000); return; }
                if (mode === 'delayed-exit') { setTimeout(() => process.exit(0), 5000); return; }
                process.exit(0);
              }
            }
            process.stdin.on('data', chunk => {
              buffer = Buffer.concat([buffer, chunk]);
              while (true) {
                const end = buffer.indexOf('\r\n\r\n'); if (end < 0) return;
                const length = Number(/Content-Length: (\d+)/.exec(buffer.subarray(0,end).toString())[1]);
                if (buffer.length < end + 4 + length) return;
                const message = JSON.parse(buffer.subarray(end+4,end+4+length));
                buffer = buffer.subarray(end+4+length); handle(message);
              }
            });
        """.trimIndent() + "\n"
    }
}
