package com.baystudio.droide.core

/** Dedicated zero-document health. Exact source recognition is not release or ARM64 runtime attestation. */
internal object LocalUbuntuMakeLspHealth {
    const val FILE_NAME = "make-lsp-health.cjs"
    const val TIMEOUT_MS = 60_000L
    private val stageName: String by lazy {
        LocalPackageInstallJournal.paths("/", LocalUbuntuMakePolicy.FAMILY, LocalUbuntuMakePolicy.VERSION,
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.name
    }
    internal val preNodeHandoff: String = """set -eu; lease=${'$'}{DROIDE_PROCESS_LEASE-}; if [ -n "${'$'}lease" ]; then case "${'$'}lease" in *[!0-9a-f]*) exit 64;; esac; [ "${'$'}{#lease}" -eq 20 ] || exit 64; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "DROIDE_PROCESS_LEASE=${'$'}lease" "${'$'}@"; fi; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "${'$'}@""""
    private fun absolute(value: String): Boolean = value.startsWith('/') && value.toByteArray(Charsets.UTF_8).size <= 4095 &&
        value.none { it.code < 32 || it.code == 127 } && value.drop(1).split('/').all {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 255 && it != "." && it != ".."
        }
    fun healthCommand(healthFile: String, target: String): List<String> {
        require(absolute(target) && target.endsWith("/payload/tree/make-ls")) { "Invalid original Make LS target" }
        val root = target.removeSuffix("/payload/tree/make-ls")
        require(root.endsWith("/packages/lsp.make-ls/0.1.25/arm64-v8a") || root.endsWith("/packages/.staging/$stageName")) {
            "Invalid reviewed Make LS package layout"
        }
        require(absolute(healthFile) && healthFile == "$root/$FILE_NAME") { "Make LS health helper must belong to the exact package root" }
        return listOf("/bin/sh", "-c", preNodeHandoff, "droide-make-env", "/usr/bin/node", healthFile, target)
    }
    val script: String = """
        'use strict';
        const fs = require('node:fs');
        const path = require('node:path');
        const { spawn } = require('node:child_process');
        const { performance } = require('node:perf_hooks');
        const started = performance.now();
        let root = null, rootIdentity = null, rootFd = null, tmpIdentity = null, tmpFd = null, targetFd = null, targetIdentity = null;
        let owner = null, finishing = false, finishCode = 0, workTimer = null, target;
        const same = (a, b) => a.dev === b.dev && a.ino === b.ino && a.mode === b.mode;
        const unchanged = (a, b) => same(a, b) && a.size === b.size && a.mtimeMs === b.mtimeMs && a.ctimeMs === b.ctimeMs;
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        const absolute = value => typeof value === 'string' && value.startsWith('/') && Buffer.byteLength(value, 'utf8') <= 4095 &&
          !/[\x00-\x1f\x7f]/.test(value) && value.slice(1).split('/').every(part => part && Buffer.byteLength(part, 'utf8') <= 255 && part !== '.' && part !== '..');
        function packageRoot(value) {
          if (!absolute(value) || !value.endsWith('/payload/tree/make-ls')) throw new Error('invalid original Make LS target');
          const base = value.slice(0, -('/payload/tree/make-ls').length);
          if (!/(?:^|\/)packages\/(?:lsp\.make-ls\/0\.1\.25\/arm64-v8a|\.staging\/lsp\.make-ls-0\.1\.25-local-ubuntu-vendor-cd45e1bae123d94f96cd37a3)${'$'}/.test(base))
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
        function preflight() {
          const base = packageRoot(target);
          if (process.argv[1] !== base + '/make-lsp-health.cjs') throw new Error('health helper belongs to another package');
          noLinkParents(target); noLinkParents(process.argv[1]);
          const helper = fs.lstatSync(process.argv[1]);
          if (!helper.isFile() || helper.isSymbolicLink() || helper.size < 1 || helper.size > 65536) throw new Error('invalid owned health helper');
          targetFd = fs.openSync(target, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
          targetIdentity = fs.fstatSync(targetFd);
          if (!targetIdentity.isFile() || targetIdentity.size < 1 || targetIdentity.size > 67108864 || (targetIdentity.mode & 0o111) === 0)
            throw new Error('original Make LS must be a bounded regular executable');
          fs.accessSync(target, fs.constants.X_OK); checkTarget();
        }
        function checkTarget() {
          if (targetFd === null || !unchanged(targetIdentity, fs.fstatSync(targetFd)) || !unchanged(targetIdentity, fs.lstatSync(target)))
            throw new Error('original Make LS identity changed');
        }
        function checkRoot() {
          if (tmpFd === null || !same(fs.fstatSync(tmpFd), tmpIdentity) || !same(realDirectory('/tmp'), tmpIdentity) || !same(realDirectory(root), rootIdentity) ||
              rootFd === null || !same(fs.fstatSync(rootFd), rootIdentity)) throw new Error('private root identity changed');
        }
        function prepare() {
          const lease = process.env.DROIDE_PROCESS_LEASE;
          if (lease !== undefined && lease !== '' && !/^[0-9a-f]{20}${'$'}/.test(lease)) throw new Error('invalid process ownership token');
          tmpIdentity = realDirectory('/tmp');
          tmpFd = fs.openSync('/tmp', fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
          if (!same(fs.fstatSync(tmpFd), tmpIdentity)) throw new Error('private parent identity changed');
          process.umask(0o077);
          root = fs.mkdtempSync('/tmp/droide-make-health-');
          rootIdentity = realDirectory(root);
          rootFd = fs.openSync(root, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
          checkRoot();
          if ((rootIdentity.mode & 0o777) !== 0o700) throw new Error('private state must be mode 0700');
          const directory = name => {
            checkRoot(); const result = '/proc/self/fd/' + rootFd + '/' + name;
            fs.mkdirSync(result, { mode: 0o700 });
            const identity = realDirectory(result);
            if ((identity.mode & 0o777) !== 0o700) throw new Error('private directory mode changed');
            return path.join(root, name);
          };
          const environment = { HOME: directory('home'), TMPDIR: directory('tmp'), XDG_CACHE_HOME: directory('cache'),
            XDG_CONFIG_HOME: directory('xdg-config'), XDG_DATA_HOME: directory('xdg-data'), XDG_STATE_HOME: directory('xdg-state'),
            XDG_RUNTIME_DIR: directory('xdg-runtime'), PATH: '', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TERM: 'dumb', NO_COLOR: '1' };
          if (lease) environment.DROIDE_PROCESS_LEASE = lease;
          const cwd = directory('work');
          checkRoot(); checkTarget();
          return { environment, cwd };
        }
        function cleanup() {
          if (root === null) return;
          let entries = 0, bytes = 0;
          const deadline = Math.min(performance.now() + 2000, started + 35000);
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
          checkRoot(); remove('/proc/self/fd/' + tmpFd + '/' + path.basename(root), 0, rootIdentity); root = null;
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
        function start(prepared) {
          if (finishing) throw new Error('launch canceled');
          checkRoot(); checkTarget();
          const record = { child: spawn(target, [], { cwd: prepared.cwd, env: prepared.environment,
            stdio: ['pipe', 'pipe', 'pipe'], detached: true, shell: false }), closed: false, error: null };
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
          console.error('Make LS: private state retained at ' + String(root) + identity + ': ' + reason);
        }
        async function finish(code, reason) {
          if (code !== 0) finishCode = code;
          if (finishing) return;
          finishing = true; clearTimeout(workTimer);
          if (reason) console.error('Make LS: ' + reason);
          try { await stop(); }
          catch (error) { finishCode = 1; retained(error.message); closeDescriptors(); process.exit(1); return; }
          try { cleanup(); }
          catch (error) { finishCode = 1; retained(error.message); }
          closeDescriptors();
          if (performance.now() - started > 35000) { finishCode = 1; console.error('Make LS: total health deadline exceeded'); }
          if (finishCode === 0) console.log('Droide Make LS initialize/shutdown OK');
          process.exit(finishCode);
        }
        function closeDescriptors() {
          for (const descriptor of [targetFd, rootFd, tmpFd]) if (descriptor !== null) { try { fs.closeSync(descriptor); } catch (_) {} }
          targetFd = null; rootFd = null; tmpFd = null;
        }
        const { TextDecoder } = require('node:util');
        const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
        const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
        const decoder = new TextDecoder('utf-8', { fatal: true });
        let phase = 'initialize', expected = 1, phaseTimer = null, child;
        let buffer = Buffer.alloc(0), length = null, received = 0, messages = 0, stderr = Buffer.alloc(0);
        let initialized = false, shutdown = false, exitFlushed = false, protocolFailure = false;
        function fail(reason) {
          protocolFailure = true; clearTimeout(phaseTimer);
          if (stderr.length) { process.stderr.write(stderr); stderr = Buffer.alloc(0); }
          void finish(1, reason);
        }
        function deadline(ms) { clearTimeout(phaseTimer); phaseTimer = setTimeout(() => fail(phase + ' timed out'), ms); }
        function send(message) {
          if (finishing) return;
          const body = Buffer.from(JSON.stringify(message), 'utf8');
          child.stdin.write(Buffer.concat([Buffer.from('Content-Length: ' + body.length + '\r\n\r\n', 'ascii'), body]));
        }
        const expectedResult = {"capabilities":{"positionEncoding":"utf-16","textDocumentSync":{"openClose":true,"change":1,"save":{"includeText":false}},"hoverProvider":true,"documentSymbolProvider":{},"completionProvider":{"triggerCharacters":["${'$'}","("]},"definitionProvider":true,"referencesProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix"]},"documentFormattingProvider":true},"serverInfo":{"name":"make-ls","version":"0.1.0"}};
        function exact(a, b) {
          if (a === b) return true;
          if (Array.isArray(a) || Array.isArray(b)) return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, index) => exact(value, b[index]));
          if (!object(a) || !object(b)) return false;
          const keys = Object.keys(a);
          return keys.length === Object.keys(b).length && keys.every(key => own(b, key) && exact(a[key], b[key]));
        }
        function capabilities(result) {
          if (!exact(result, expectedResult)) throw new Error('unexpected Make LS exact native capabilities/serverInfo');
        }
        function handle(message) {
          if (!object(message) || message.jsonrpc !== '2.0') throw new Error('invalid JSON-RPC envelope');
          if (++messages > 2 || Object.keys(message).length !== 3 || !own(message, 'jsonrpc') || !own(message, 'id') ||
              !own(message, 'result') || message.id !== expected || typeof message.id !== 'number')
            throw new Error('unexpected server method, envelope or response');
          if (phase === 'initialize') {
            capabilities(message.result); initialized = true;
            send({ jsonrpc: '2.0', method: 'initialized', params: {} });
            phase = 'shutdown'; expected = 2; deadline(5000);
            send({ jsonrpc: '2.0', id: 2, method: 'shutdown' });
          } else if (phase === 'shutdown') {
            if (message.result !== null) throw new Error('shutdown result must be null');
            shutdown = true; phase = 'exit'; expected = null; deadline(8000);
            send({ jsonrpc: '2.0', method: 'exit' });
            child.stdin.end(() => { exitFlushed = true; });
          } else throw new Error('unexpected response after shutdown');
        }
        function receive(chunk) {
          if (finishing) return;
          received += chunk.length;
          if (received > 2097152) throw new Error('protocol stdout bound exceeded');
          buffer = Buffer.concat([buffer, chunk]);
          while (!finishing) {
            if (length === null) {
              const end = buffer.indexOf('\r\n\r\n');
              if (end < 0) { if (buffer.length > 8192) throw new Error('protocol header bound exceeded'); return; }
              if (end > 8192) throw new Error('protocol header bound exceeded');
              const raw = buffer.subarray(0, end);
              if ([...raw].some(byte => byte < 32 || byte > 126)) throw new Error('invalid protocol header');
              const match = /^Content-Length: ([0-9]+)${'$'}/.exec(raw.toString('ascii'));
              const declared = match ? Number(match[1]) : NaN;
              if (!Number.isSafeInteger(declared) || declared < 1 || declared > 1048576) throw new Error('invalid Content-Length');
              length = declared; buffer = buffer.subarray(end + 4);
            }
            if (buffer.length < length) return;
            const body = buffer.subarray(0, length); buffer = buffer.subarray(length); length = null;
            handle(JSON.parse(decoder.decode(body)));
          }
        }
        for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.on(signal, () => fail('health canceled'));
        try {
          const argv = process.argv.slice(2);
          if (argv.length !== 1) throw new Error('health requires exactly one original Make LS target');
          target = argv[0]; preflight();
          const prepared = prepare(), record = start(prepared); child = record.child;
          child.on('error', error => fail('cannot start original Make LS: ' + error.message));
          child.stdin.on('error', error => fail('server stdin failed: ' + error.message));
          child.stdout.on('error', error => fail('server stdout failed: ' + error.message));
          child.stderr.on('error', error => fail('server stderr failed: ' + error.message));
          child.stdout.on('data', chunk => { try { receive(chunk); } catch (error) { fail(error.message); } });
          child.stderr.on('data', chunk => {
            const room = 65536 - stderr.length;
            stderr = Buffer.concat([stderr, chunk.subarray(0, room)]);
            fail(chunk.length > room ? 'server stderr bound exceeded' : 'unexpected server stderr');
          });
          record.done.then(result => {
            clearTimeout(phaseTimer);
            if (finishing) return;
            const valid = !result.error && !protocolFailure && phase === 'exit' && initialized && shutdown && exitFlushed &&
              child.stdin.writableFinished && result.code === 0 && result.signal === null && length === null && buffer.length === 0 && stderr.length === 0;
            if (!valid) fail('server did not complete clean initialize/shutdown/exit');
            else void finish(0, null);
          });
          // Reserve 5.6 seconds for TERM/KILL, bounded group reaping and private cleanup.
          const remaining = 29400 - (performance.now() - started);
          if (remaining <= 0) throw new Error('health preparation deadline exceeded');
          workTimer = setTimeout(() => fail('overall health exchange deadline exceeded'), remaining);
          deadline(12000);
          checkTarget();
          const initialize = {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"workspaceFolders":[],"initializationOptions":{},"trace":"off","capabilities":{"general":{"positionEncodings":["utf-16"]},"workspace":{"workspaceFolders":false,"configuration":false,"applyEdit":false,"didChangeWatchedFiles":{"dynamicRegistration":false},"executeCommand":{"dynamicRegistration":false}},"textDocument":{"synchronization":{"dynamicRegistration":false},"codeAction":{"codeActionLiteralSupport":{"codeActionKind":{"valueSet":["quickfix"]}}}}}}};
          send(initialize);
        } catch (error) { fail(error.message); }
    """.trimIndent().plus("\n").also {
        require(it.toByteArray(Charsets.UTF_8).size in 1..65_536) { "Make LS health helper exceeds integration file bounds" }
        require(stageName == "lsp.make-ls-0.1.25-local-ubuntu-vendor-cd45e1bae123d94f96cd37a3") { "Make LS staging identity changed; review health target gate" }
    }
}
