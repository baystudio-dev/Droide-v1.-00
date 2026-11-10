package com.baystudio.droide.core

import java.security.MessageDigest
import java.util.Base64

/** Exact original LemMinX launcher. Private state/finite environment are not an IO sandbox. */
internal object LspLemMinxLaunchPolicy {
    const val JAR_NAME = "org.eclipse.lemminx-0.31.2-uber.jar"
    private const val STAGE = "lsp.lemminx-0.31.2-local-ubuntu-vendor-40a5b00e1cdee4e06985fea0"
    internal val preNodeHandoff: String = """set -eu; lease=${'$'}{DROIDE_PROCESS_LEASE-}; if [ -n "${'$'}lease" ]; then case "${'$'}lease" in *[!0-9a-f]*) exit 64;; esac; [ "${'$'}{#lease}" -eq 20 ] || exit 64; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "DROIDE_PROCESS_LEASE=${'$'}lease" "${'$'}@"; fi; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "${'$'}@""""

    // Shared only by this family's fixed editor expression and dedicated direct health file. No
    // dynamic module or project-derived code is loaded.
    internal val commonSource: String = """
        'use strict';
        const fs = require('node:fs');
        const path = require('node:path');
        const { spawn } = require('node:child_process');
        const { createHash } = require('node:crypto');
        const { performance } = require('node:perf_hooks');
        const JAVA = '/usr/lib/jvm/java-21-openjdk-arm64/bin/java';
        const JAR_NAME = 'org.eclipse.lemminx-0.31.2-uber.jar';
        const JAR_SIZE = 8780504;
        const JAR_SHA = 'f4fde164e785c635e5f86361dbf4b993bfe2c5f83fdb52891d058b7dfc9bcfc8';
        const started = performance.now();
        let root = null, rootIdentity = null, rootFd = null, tmpIdentity = null, targetFd = null, targetIdentity = null;
        let owner = null, finishing = false, finishCode = 0, workTimer = null, target;
        const same = (a, b) => a.dev === b.dev && a.ino === b.ino && a.mode === b.mode;
        const unchanged = (a, b) => same(a, b) && a.size === b.size && a.mtimeMs === b.mtimeMs && a.ctimeMs === b.ctimeMs;
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        const absolute = value => typeof value === 'string' && value.startsWith('/') && Buffer.byteLength(value, 'utf8') <= 4095 &&
          !/[\x00-\x1f\x7f]/.test(value) && value.slice(1).split('/').every(part => part && Buffer.byteLength(part, 'utf8') <= 255 && part !== '.' && part !== '..');
        function packageRoot(value) {
          if (!absolute(value) || !value.endsWith('/payload/tree/' + JAR_NAME)) throw new Error('invalid original JAR target');
          const base = value.slice(0, -('/payload/tree/' + JAR_NAME).length);
          if (!/(?:^|\/)packages\/(?:lsp\.lemminx\/0\.31\.2\/arm64-v8a|\.staging\/lsp\.lemminx-0\.31\.2-local-ubuntu-vendor-40a5b00e1cdee4e06985fea0)${'$'}/.test(base))
            throw new Error('invalid original package layout');
          return base;
        }
        function realDirectory(value) {
          const stat = fs.lstatSync(value);
          if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('not a real directory: ' + value);
          return stat;
        }
        function noLinkParents(value) {
          let current = '/';
          for (const part of path.dirname(value).slice(1).split('/').filter(Boolean)) {
            current = path.join(current, part); realDirectory(current);
          }
        }
        function readRegular(value, maximum) {
          const descriptor = fs.openSync(value, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
          try {
            const before = fs.fstatSync(descriptor);
            if (!before.isFile() || before.size < 1 || before.size > maximum) throw new Error('invalid bounded regular file: ' + value);
            const bytes = Buffer.alloc(before.size + 1);
            let count = 0, read;
            while (count < bytes.length && (read = fs.readSync(descriptor, bytes, count, bytes.length - count, count)) > 0) count += read;
            if (count !== before.size || !unchanged(before, fs.fstatSync(descriptor)) || !unchanged(before, fs.lstatSync(value)))
              throw new Error('regular file changed during verification');
            return bytes.subarray(0, count);
          } finally { fs.closeSync(descriptor); }
        }
        function preflight() {
          packageRoot(target); noLinkParents(target);
          targetFd = fs.openSync(target, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
          targetIdentity = fs.fstatSync(targetFd);
          if (!targetIdentity.isFile() || targetIdentity.size !== JAR_SIZE) throw new Error('original JAR size mismatch');
          const digest = createHash('sha256'), chunk = Buffer.alloc(65536);
          let offset = 0, count;
          while ((count = fs.readSync(targetFd, chunk, 0, Math.min(chunk.length, JAR_SIZE + 1 - offset), offset)) > 0) {
            offset += count; if (offset > JAR_SIZE) throw new Error('original JAR grew'); digest.update(chunk.subarray(0, count));
          }
          if (offset !== JAR_SIZE || digest.digest('hex') !== JAR_SHA) throw new Error('original JAR SHA-256 mismatch');
          checkTarget();
          noLinkParents(JAVA);
          let java;
          try { java = fs.lstatSync(JAVA); fs.accessSync(JAVA, fs.constants.X_OK); }
          catch (_) { throw new Error('fixed managed Java executable is missing'); }
          if (!java.isFile() || java.isSymbolicLink()) throw new Error('fixed Java must be a real executable file');
          const release = readRegular(path.dirname(path.dirname(JAVA)) + '/release', 65536).toString('utf8');
          function field(name) {
            const rows = release.split(/\r?\n/).filter(row => row.startsWith(name + '='));
            if (rows.length !== 1) throw new Error('missing or ambiguous Java release ' + name);
            const match = /^[A-Z_]+="([^"\r\n]*)"${'$'}/.exec(rows[0]);
            if (!match) throw new Error('invalid Java release metadata');
            return match[1];
          }
          if (!/^21(?:\.[0-9]+)*(?:\+[0-9]+)?${'$'}/.test(field('JAVA_VERSION'))) throw new Error('fixed Java major is not reviewed Java 21');
          if (field('OS_NAME') !== 'Linux') throw new Error('fixed Java platform is not Linux');
          if (field('OS_ARCH') !== 'aarch64') throw new Error('fixed Java architecture is not aarch64');
        }
        function checkTarget() {
          if (targetFd === null || !unchanged(targetIdentity, fs.fstatSync(targetFd)) || !unchanged(targetIdentity, fs.lstatSync(target)))
            throw new Error('original JAR identity changed');
        }
        function checkRoot() {
          if (!same(realDirectory('/tmp'), tmpIdentity) || !same(realDirectory(root), rootIdentity) ||
              rootFd === null || !same(fs.fstatSync(rootFd), rootIdentity)) throw new Error('private root identity changed');
        }
        function prepare(health) {
          const lease = process.env.DROIDE_PROCESS_LEASE;
          if (lease !== undefined && lease !== '' && !/^[0-9a-f]{20}${'$'}/.test(lease)) throw new Error('invalid process ownership token');
          tmpIdentity = realDirectory('/tmp'); process.umask(0o077);
          root = fs.mkdtempSync('/tmp/droide-lemminx-');
          rootIdentity = realDirectory(root);
          rootFd = fs.openSync(root, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
          checkRoot();
          if ((rootIdentity.mode & 0o777) !== 0o700) throw new Error('private state must be mode 0700');
          const directory = name => { checkRoot(); const result = path.join(root, name); fs.mkdirSync(result, { mode: 0o700 }); return result; };
          const environment = { HOME: directory('home'), TMPDIR: directory('tmp'), PATH: '', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TERM: 'dumb' };
          if (lease) environment.DROIDE_PROCESS_LEASE = lease;
          const cache = directory('cache'), cwd = health ? directory('work') : process.cwd();
          checkRoot(); checkTarget();
          return { environment, cwd, args: ['-Xms32m', '-Xmx512m', '-DwatchParentProcess=false', '-Dlemminx.workdir=' + cache,
            '-Djava.io.tmpdir=' + environment.TMPDIR, '-Duser.home=' + environment.HOME,
            '-XX:ErrorFile=' + root + '/hs_err_pid%p.log', '-jar', target] };
        }
        function cleanup(health) {
          if (root === null) return;
          let entries = 0, bytes = 0;
          const deadline = Math.min(performance.now() + 2000, health ? started + 35000 : Infinity);
          const bound = depth => {
            if (entries > 4096 || bytes > 67108864 || depth > 32 || performance.now() > deadline) throw new Error('private cleanup bound exceeded');
            checkRoot();
          };
          function identity(value, expected) {
            const actual = fs.lstatSync(value);
            if (!same(actual, expected) || (!actual.isDirectory() && !unchanged(actual, expected)))
              throw new Error('private entry identity changed');
            return actual;
          }
          // Child paths are anchored to retained directory descriptors, never an untrusted
          // symlink ancestor. This needs Linux /proc; failure retains this exact owned tree.
          function remove(value, depth, expected) {
            ++entries; bound(depth); const actual = identity(value, expected);
            if (actual.isSymbolicLink()) { bound(depth); identity(value, expected); fs.unlinkSync(value); return; }
            if (actual.isFile()) { bytes += actual.size; bound(depth); identity(value, expected); fs.unlinkSync(value); return; }
            if (!actual.isDirectory()) throw new Error('private tree contains a special file');
            const descriptor = fs.openSync(value, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
            let directory;
            try {
              if (!same(fs.fstatSync(descriptor), expected)) throw new Error('private directory identity changed');
              directory = fs.opendirSync('/proc/self/fd/' + descriptor);
              for (;;) {
                bound(depth);
                if (!same(fs.lstatSync(value), expected) || !same(fs.fstatSync(descriptor), expected)) throw new Error('private directory identity changed');
                const entry = directory.readSync(); if (entry === null) break;
                const child = '/proc/self/fd/' + descriptor + '/' + entry.name;
                remove(child, depth + 1, fs.lstatSync(child));
              }
            } finally { if (directory) directory.closeSync(); fs.closeSync(descriptor); }
            bound(depth);
            if (!same(fs.lstatSync(value), expected)) throw new Error('private directory identity changed');
            fs.rmdirSync(value);
          }
          checkRoot(); remove(root, 0, rootIdentity); root = null;
        }
        function signalGroup(signal) {
          if (!owner || !owner.child.pid) return;
          try { process.kill(-owner.child.pid, signal); }
          catch (error) { if (error.code !== 'ESRCH') throw error; }
        }
        function groupAlive() {
          if (!owner || !owner.child.pid) return false;
          try { process.kill(-owner.child.pid, 0); return true; }
          catch (error) { if (error.code === 'ESRCH') return false; throw error; }
        }
        function start(prepared, health) {
          if (finishing) throw new Error('launch canceled');
          checkRoot(); checkTarget();
          const record = { child: spawn(JAVA, prepared.args, { cwd: prepared.cwd, env: prepared.environment,
            stdio: health ? ['pipe', 'pipe', 'pipe'] : 'inherit', detached: true, shell: false }), closed: false, error: null };
          owner = record;
          record.done = new Promise(resolve => {
            record.child.once('error', error => { record.error = error; });
            record.child.once('close', (code, signal) => { record.closed = true; resolve({ code, signal, error: record.error }); });
          });
          return record;
        }
        async function stop() {
          if (!owner) return;
          signalGroup('SIGTERM'); await delay(200); signalGroup('SIGKILL');
          const deadline = performance.now() + 3000;
          while (!owner.closed || groupAlive()) {
            if (performance.now() > deadline) throw new Error('owned child/group did not quiesce');
            await delay(20);
          }
        }
        function retained(reason) {
          const identity = rootIdentity ? ' dev=' + rootIdentity.dev + ' ino=' + rootIdentity.ino : '';
          console.error('LemMinX: private state retained at ' + String(root) + identity + ': ' + reason);
        }
        async function finish(code, reason, health) {
          if (code !== 0) finishCode = code;
          if (finishing) return;
          finishing = true; clearTimeout(workTimer);
          if (reason) console.error('LemMinX: ' + reason);
          try { await stop(); }
          catch (error) { finishCode = 1; retained(error.message); closeDescriptors(); process.exit(1); return; }
          try { cleanup(health); }
          catch (error) { finishCode = 1; retained(error.message); }
          closeDescriptors();
          if (health && performance.now() - started > 35000) { finishCode = 1; console.error('LemMinX: total health deadline exceeded'); }
          if (health && finishCode === 0) console.log('Droide LemMinX initialize/shutdown OK');
          process.exit(finishCode);
        }
        function closeDescriptors() {
          for (const descriptor of [targetFd, rootFd]) if (descriptor !== null) { try { fs.closeSync(descriptor); } catch (_) {} }
          targetFd = null; rootFd = null;
        }
    """.trimIndent() + "\n"

    internal val sourceScript: String = commonSource + """
        for (const [signal, code] of [['SIGTERM', 143], ['SIGINT', 130], ['SIGHUP', 129]])
          process.on(signal, () => { void finish(code, 'editor launch canceled', false); });
        (async () => {
          try {
            const argv = process.argv.slice(1);
            if (argv.length !== 2 || argv[0] !== 'editor') throw new Error('invalid editor positional arguments');
            target = argv[1];
            preflight();
            const prepared = prepare(false), record = start(prepared, false), result = await record.done;
            if (finishing) return;
            if (result.error) await finish(1, 'cannot start fixed Java: ' + result.error.message, false);
            else if (result.signal !== null) await finish(1, 'fixed Java exited by signal ' + result.signal, false);
            else await finish(Number.isInteger(result.code) ? result.code : 1, null, false);
          } catch (error) { await finish(1, error.message, false); }
        })();
    """.trimIndent() + "\n"

    internal val script: String by lazy {
        ("eval(Buffer.from('" + Base64.getEncoder().encodeToString(sourceScript.toByteArray(Charsets.UTF_8)) +
            "','base64').toString('utf8'));").also {
            require(it.length <= 32_000 && it.toByteArray(Charsets.UTF_8).size <= 32_000) {
                "LemMinX fixed bootstrap exceeds the existing argument bound"
            }
        }
    }
    internal val scriptSha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    val editorIdentity: String get() =
        "lemminx-inline-node-v1:sha256=$scriptSha256:original-0.31.2:java21:finite-env:owned-group:private-state"

    private fun absolute(value: String): Boolean = value.startsWith('/') &&
        value.toByteArray(Charsets.UTF_8).size <= 4095 && value.none { it.code < 32 || it.code == 127 } &&
        value.drop(1).split('/').all { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 255 && it != "." && it != ".." }

    private fun packageRoot(target: String): String {
        require(absolute(target) && target.endsWith("/payload/tree/$JAR_NAME")) { "Invalid original LemMinX target" }
        val root = target.removeSuffix("/payload/tree/$JAR_NAME")
        require(root.endsWith("/packages/lsp.lemminx/0.31.2/arm64-v8a") || root.endsWith("/packages/.staging/$STAGE")) {
            "Invalid reviewed LemMinX package layout"
        }
        return root
    }

    private fun handoff(node: List<String>): List<String> =
        listOf("/bin/sh", "-c", preNodeHandoff, "droide-lemminx-env") + node

    fun editorCommand(target: String): List<String> {
        packageRoot(target)
        return handoff(listOf("/usr/bin/node", "-e", script, "--", "editor", target))
    }

    fun healthCommand(healthFile: String, target: String): List<String> {
        val root = packageRoot(target)
        require(absolute(healthFile) && healthFile == "$root/${LocalUbuntuLemMinxLspHealth.FILE_NAME}") {
            "LemMinX health helper must belong to the exact original package root"
        }
        return handoff(listOf("/usr/bin/node", healthFile, target))
    }
}
