package com.baystudio.droide.core

/** Dedicated zero-document health. Exact source recognition is not release or ARM64 runtime attestation. */
internal object LocalUbuntuSqruffLspHealth {
    const val FILE_NAME = "sqruffp-health.cjs"
    const val TIMEOUT_MS = 60_000L
    private val stageName: String by lazy {
        LocalPackageInstallJournal.paths("/", LocalUbuntuSqruffPolicy.FAMILY, LocalUbuntuSqruffPolicy.VERSION,
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.name
    }
    internal val preNodeHandoff: String = """set -eu; lease=${'$'}{DROIDE_PROCESS_LEASE-}; if [ -n "${'$'}lease" ]; then case "${'$'}lease" in *[!0-9a-f]*) exit 64;; esac; [ "${'$'}{#lease}" -eq 20 ] || exit 64; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "DROIDE_PROCESS_LEASE=${'$'}lease" "${'$'}@"; fi; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "${'$'}@""""
    private fun absolute(value: String): Boolean = value.startsWith('/') && value.toByteArray(Charsets.UTF_8).size <= 4095 &&
        value.none { it.code < 32 || it.code == 127 } && value.drop(1).split('/').all {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 255 && it != "." && it != ".."
        }
    fun healthCommand(healthFile: String, target: String): List<String> {
        require(absolute(target) && target.endsWith("/payload/tree/sqruff")) { "Invalid original Sqruff target" }
        val root = target.removeSuffix("/payload/tree/sqruff")
        require(root.endsWith("/packages/lsp.sqruff/0.41.0/arm64-v8a") || root.endsWith("/packages/.staging/$stageName")) {
            "Invalid reviewed Sqruff package layout"
        }
        require(absolute(healthFile) && healthFile == "$root/$FILE_NAME") { "Sqruff health helper must belong to the exact package root" }
        return listOf("/bin/sh", "-c", preNodeHandoff, "droide-sqruff-env", "/usr/bin/node", healthFile, target)
    }
    val script: String = """
        'use strict';
        const fs = require('node:fs');
        const path = require('node:path');
        const { spawn } = require('node:child_process');
        const { performance } = require('node:perf_hooks');
        const started = performance.now();
        let root = null, rootIdentity = null, rootFd = null, tmpIdentity = null, tmpFd = null, targetFd = null, targetIdentity = null, helperFd = null, helperIdentity = null, configFd = null, configIdentity = null, privateCwd = null;
        let owner = null, finishing = false, finishCode = 0, workTimer = null, target;
        const same = (a, b) => a.dev === b.dev && a.ino === b.ino && a.mode === b.mode;
        const unchanged = (a, b) => same(a, b) && a.size === b.size && a.mtimeMs === b.mtimeMs && a.ctimeMs === b.ctimeMs;
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        const absolute = value => typeof value === 'string' && value.startsWith('/') && Buffer.byteLength(value, 'utf8') <= 4095 &&
          !/[\x00-\x1f\x7f]/.test(value) && value.slice(1).split('/').every(part => part && Buffer.byteLength(part, 'utf8') <= 255 && part !== '.' && part !== '..');
        function packageRoot(value) {
          if (!absolute(value) || !value.endsWith('/payload/tree/sqruff')) throw new Error('invalid original Sqruff target');
          const base = value.slice(0, -('/payload/tree/sqruff').length);
          if (!base.endsWith('/packages/lsp.sqruff/0.41.0/arm64-v8a') && !base.endsWith('/packages/.staging/$stageName'))
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
          if (process.argv[1] !== base + '/sqruffp-health.cjs') throw new Error('health helper belongs to another package');
          noLinkParents(target); noLinkParents(process.argv[1]);
          const helper = fs.lstatSync(process.argv[1]);
          if (!helper.isFile() || helper.isSymbolicLink() || helper.nlink !== 1 || helper.size < 1 || helper.size > 65536) throw new Error('invalid owned health helper');
          helperFd = fs.openSync(process.argv[1], fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
          helperIdentity = fs.fstatSync(helperFd);
          if (!unchanged(helper, helperIdentity)) throw new Error('helper changed during open');
          targetFd = fs.openSync(target, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
          targetIdentity = fs.fstatSync(targetFd);
          if (!targetIdentity.isFile() || targetIdentity.nlink !== 1 || targetIdentity.size < 1 || targetIdentity.size > 67108864 || (targetIdentity.mode & 0o111) === 0)
            throw new Error('original Sqruff must be a bounded regular executable');
          fs.accessSync(target, fs.constants.X_OK); checkTarget();
        }
        function checkTarget() {
          noLinkParents(target); noLinkParents(process.argv[1]);
          if (helperFd === null || !unchanged(helperIdentity, fs.fstatSync(helperFd)) || !unchanged(helperIdentity, fs.lstatSync(process.argv[1])))
            throw new Error('health helper identity changed');
          if (targetFd === null || !unchanged(targetIdentity, fs.fstatSync(targetFd)) || !unchanged(targetIdentity, fs.lstatSync(target)))
            throw new Error('original Sqruff identity changed');
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
          root = fs.mkdtempSync('/tmp/droide-sqruff-health-');
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
            XDG_RUNTIME_DIR: directory('xdg-runtime'), PATH: '', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TERM: 'dumb', NO_COLOR: '1', SQRUFF_LOG: 'off' };
          if (lease) environment.DROIDE_PROCESS_LEASE = lease;
          const cwd = directory('work'); privateCwd = cwd;
          const config = cwd + '/.sqruff';
          configFd = fs.openSync(config, fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_RDWR | fs.constants.O_NOFOLLOW, 0o600);
          fs.writeFileSync(configFd, '[sqruff]\ndialect = ansi\ntemplater = raw\nlibrary_path = none\n', 'utf8');
          configIdentity = fs.fstatSync(configFd);
          if (!configIdentity.isFile() || configIdentity.nlink !== 1 || (configIdentity.mode & 0o777) !== 0o600) throw new Error('private config mode changed');
          checkRoot(); checkTarget(); checkConfig();
          return { environment, cwd, config };
        }
        function checkConfig() {
          noLinkParents(privateCwd + '/.sqruff');
          if (configFd === null || !unchanged(configIdentity, fs.fstatSync(configFd)) ||
              !unchanged(configIdentity, fs.lstatSync(privateCwd + '/.sqruff')) ||
              fs.readdirSync(privateCwd).length !== 1 || fs.readdirSync(privateCwd)[0] !== '.sqruff')
            throw new Error('private config or cwd changed');
        }
        function cleanup() {
          if (root === null) return;
          let entries = 0, bytes = 0;
          const deadline = Math.min(performance.now() + 2000, started + 42000);
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
          checkRoot(); checkTarget(); checkConfig();
          const record = { child: spawn(target, ['--config', prepared.config, 'lsp'], { cwd: prepared.cwd, env: prepared.environment,
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
          console.error('Sqruff: private state retained at ' + String(root) + identity + ': ' + reason);
        }
        async function finish(code, reason) {
          if (code !== 0) finishCode = code;
          if (finishing) return;
          finishing = true; clearTimeout(workTimer);
          if (reason) console.error('Sqruff: ' + reason);
          try { await stop(); }
          catch (error) { finishCode = 1; retained(error.message); closeDescriptors(); process.exit(1); return; }
          if (finishCode === 0) {
            try { checkRoot(); checkTarget(); checkConfig(); }
            catch (error) { finishCode = 1; console.error('Sqruff: final identity check failed: ' + error.message); }
          }
          try { cleanup(); }
          catch (error) { finishCode = 1; retained(error.message); }
          closeDescriptors();
          if (performance.now() - started > 42000) { finishCode = 1; console.error('Sqruff: total health deadline exceeded'); }
          if (finishCode === 0) console.log('Droide Sqruff initialize/shutdown OK');
          process.exit(finishCode);
        }
        function closeDescriptors() {
          for (const descriptor of [targetFd, helperFd, configFd, rootFd, tmpFd]) if (descriptor !== null) { try { fs.closeSync(descriptor); } catch (_) {} }
          targetFd = null; helperFd = null; configFd = null; rootFd = null; tmpFd = null;
        }
        const { TextDecoder } = require('node:util');
        const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
        const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
        const decoder = new TextDecoder('utf-8', { fatal: true });
        let phase = 'initialize', expected = 1, phaseTimer = null, child;
        let buffer = Buffer.alloc(0), length = null, received = 0, messages = 0, stderr = Buffer.alloc(0);
        let initialized = false, registered = false, shutdown = false, exitFlushed = false, protocolFailure = false;
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
        const expectedResult = {capabilities:{textDocumentSync:1,documentFormattingProvider:true,semanticTokensProvider:{legend:{tokenTypes:['keyword','string','number','comment','operator','function','type','variable','parameter','property','macro'],tokenModifiers:[]},range:false,full:true}}};
        const expectedRegistration = {jsonrpc:'2.0',id:'textDocument-didSave',method:'client/registerCapability',params:{registrations:[{id:'textDocument/didSave',method:'textDocument/didSave',registerOptions:{documentSelector:[{pattern:'**/.sqlfluff'},{pattern:'**/.sqruff'},{pattern:'**/.sqruffignore'}],includeText:false}}]}};
        function exact(a, b) {
          if (a === b) return true;
          if (Array.isArray(a) || Array.isArray(b)) return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, index) => exact(value, b[index]));
          if (!object(a) || !object(b)) return false;
          const keys = Object.keys(a);
          return keys.length === Object.keys(b).length && keys.every(key => own(b, key) && exact(a[key], b[key]));
        }
        function capabilities(result) {
          if (!exact(result, expectedResult)) throw new Error('unexpected Sqruff exact native capabilities/serverInfo');
        }
        function handle(message) {
          if (!object(message) || message.jsonrpc !== '2.0' || ++messages > 64) throw new Error('invalid or excessive JSON-RPC envelope');
          if (phase === 'registration') {
            if (!exact(message, expectedRegistration)) {
              if (typeof message.method === 'string' && own(message, 'id') &&
                  (typeof message.id === 'string' || (typeof message.id === 'number' && Number.isFinite(message.id))))
                send({jsonrpc:'2.0',id:message.id,error:{code:-32601,message:'Method not supported by installer health client'}});
              throw new Error('unexpected Sqruff registration request');
            }
            registered = true;
            // Node's ordered writable queues this honest rejection completely before shutdown.
            send({jsonrpc:'2.0',id:message.id,error:{code:-32601,message:'Method not supported by installer health client'}});
            phase = 'shutdown'; expected = 2; deadline(3000);
            send({jsonrpc:'2.0',id:2,method:'shutdown'});
            return;
          }
          if (Object.keys(message).length !== 3 || !own(message, 'id') || !own(message, 'result') ||
              message.id !== expected || typeof message.id !== 'number') throw new Error('unexpected method, envelope or response');
          if (phase === 'initialize') {
            capabilities(message.result); initialized = true;
            phase = 'registration'; expected = null;
            send({jsonrpc:'2.0',method:'initialized',params:{}});
          } else if (phase === 'shutdown') {
            if (message.result !== null) throw new Error('shutdown result must be null');
            shutdown = true; phase = 'exit'; expected = null; deadline(8000);
            send({jsonrpc:'2.0',method:'exit'});
            child.stdin.end(() => { exitFlushed = true; });
          } else throw new Error('unexpected response after shutdown');
        }
        function receive(chunk) {
          if (finishing) return;
          received += chunk.length;
          if (received > 4194304) throw new Error('protocol stdout bound exceeded');
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
          if (argv.length !== 1) throw new Error('health requires exactly one original Sqruff target');
          target = argv[0]; preflight();
          const prepared = prepare(), record = start(prepared); child = record.child;
          child.on('error', error => fail('cannot start original Sqruff: ' + error.message));
          child.stdin.on('error', error => fail('server stdin failed: ' + error.message));
          child.stdout.on('error', error => fail('server stdout failed: ' + error.message));
          child.stderr.on('error', error => fail('server stderr failed: ' + error.message));
          child.stdout.on('data', chunk => { try { receive(chunk); } catch (error) { fail(error.message); } });
          child.stderr.on('data', chunk => {
            const room = 65536 - stderr.length;
            stderr = Buffer.concat([stderr, chunk.subarray(0, room)]);
            if (chunk.length > room) fail('server stderr bound exceeded');
          });
          record.done.then(result => {
            clearTimeout(phaseTimer);
            if (finishing) return;
            const valid = !result.error && !protocolFailure && phase === 'exit' && initialized && registered && shutdown && exitFlushed &&
              child.stdin.writableFinished && result.code === 0 && result.signal === null && length === null && buffer.length === 0;
            if (!valid) fail('server did not complete clean initialize/shutdown/exit');
            else {
              try { checkRoot(); checkTarget(); checkConfig(); void finish(0, null); }
              catch (error) { fail('clean-close identity changed: ' + error.message); }
            }
          });
          // Reserve 5.6 seconds for TERM/KILL, bounded group reaping and private cleanup.
          const remaining = 30000 - (performance.now() - started);
          if (remaining <= 0) throw new Error('health preparation deadline exceeded');
          workTimer = setTimeout(() => fail('overall health exchange deadline exceeded'), remaining);
          deadline(Math.max(1, remaining));
          checkTarget();
          const initialize = {jsonrpc:'2.0',id:1,method:'initialize',params:{processId:process.pid,rootUri:null,workspaceFolders:[],trace:'off',capabilities:{general:{positionEncodings:['utf-16']},workspace:{workspaceFolders:false,configuration:false,applyEdit:false,didChangeWatchedFiles:{dynamicRegistration:false},executeCommand:{dynamicRegistration:false}},textDocument:{synchronization:{dynamicRegistration:false}}}}};
          send(initialize);
        } catch (error) { fail(error.message); }
    """.trimIndent().plus("\n").also {
        require(it.toByteArray(Charsets.UTF_8).size in 1..65_536) { "Sqruff health helper exceeds integration file bounds" }
    }
}
