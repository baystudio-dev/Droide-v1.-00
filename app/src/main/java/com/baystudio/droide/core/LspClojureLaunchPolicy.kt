package com.baystudio.droide.core

import java.security.MessageDigest
import java.util.Base64

/** Managed Clojure launcher only. App-owned receipt consistency is not same-UID isolation or native acceptance. */
internal object LspClojureLaunchPolicy {
    const val REVISION = "clojure-native-managed-local-log-v1"
    internal const val STAGE = "lsp.clojure-lsp-2026.10.15-11.42.38-local-ubuntu-vendor-de479f3506bf35a877d20c5b"
    internal val editorHandoff = """set -eu; umask 077; lease=${'$'}{DROIDE_PROCESS_LEASE-}; if [ -n "${'$'}lease" ]; then case "${'$'}lease" in *[!0-9a-f]*) exit 64;; esac; [ "${'$'}{#lease}" -eq 20 ] || exit 64; set -- "DROIDE_PROCESS_LEASE=${'$'}lease" "${'$'}@"; fi; if [ "${'$'}{JAVA_HOME+x}" = x ]; then set -- "JAVA_HOME=${'$'}JAVA_HOME" "${'$'}@"; fi; if [ "${'$'}{NPM_CONFIG_PREFIX+x}" = x ]; then set -- "NPM_CONFIG_PREFIX=${'$'}NPM_CONFIG_PREFIX" "${'$'}@"; fi; if [ "${'$'}{XDG_CACHE_HOME+x}" = x ]; then set -- "XDG_CACHE_HOME=${'$'}XDG_CACHE_HOME" "${'$'}@"; fi; if [ "${'$'}{XDG_CONFIG_HOME+x}" = x ]; then set -- "XDG_CONFIG_HOME=${'$'}XDG_CONFIG_HOME" "${'$'}@"; fi; if [ "${'$'}{XDG_DATA_HOME+x}" = x ]; then set -- "XDG_DATA_HOME=${'$'}XDG_DATA_HOME" "${'$'}@"; fi; if [ "${'$'}{XDG_STATE_HOME+x}" = x ]; then set -- "XDG_STATE_HOME=${'$'}XDG_STATE_HOME" "${'$'}@"; fi; if [ "${'$'}{XDG_RUNTIME_DIR+x}" = x ]; then set -- "XDG_RUNTIME_DIR=${'$'}XDG_RUNTIME_DIR" "${'$'}@"; fi; if [ "${'$'}{USER+x}" = x ]; then set -- "USER=${'$'}USER" "${'$'}@"; fi; if [ "${'$'}{LOGNAME+x}" = x ]; then set -- "LOGNAME=${'$'}LOGNAME" "${'$'}@"; fi; exec /usr/bin/env -i "HOME=${'$'}{HOME-}" "PATH=${'$'}{PATH-}" LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "${'$'}@""""
    internal val healthHandoff = """set -eu; umask 077; lease=${'$'}{DROIDE_PROCESS_LEASE-}; if [ -n "${'$'}lease" ]; then case "${'$'}lease" in *[!0-9a-f]*) exit 64;; esac; [ "${'$'}{#lease}" -eq 20 ] || exit 64; set -- "DROIDE_PROCESS_LEASE=${'$'}lease" "${'$'}@"; fi; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb "${'$'}@""""
    internal val commonSource = """
        'use strict';
        const fs = require('node:fs');
        const path = require('node:path');
        const { spawn } = require('node:child_process');
        const { createHash } = require('node:crypto');
        const { performance } = require('node:perf_hooks');
        const started = performance.now();
        let root = null, rootIdentity = null, rootFd = null, tmpIdentity = null, tmpFd = null, targetFd = null, targetIdentity = null;
        let owner = null, finishing = false, finishCode = 0, workTimer = null, logTimer = null, target, wrapper, healthMode = false;
        let logFd = null, logIdentity = null, logPath = null, authority = null;
        const retainedIdentities = [], privateDirectories = [];
        const same = (a, b) => a.dev === b.dev && a.ino === b.ino && a.mode === b.mode;
        const unchanged = (a, b) => same(a, b) && a.size === b.size && a.mtimeMs === b.mtimeMs && a.ctimeMs === b.ctimeMs;
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        const absolute = value => typeof value === 'string' && value.startsWith('/') && Buffer.byteLength(value, 'utf8') <= 4095 &&
          !/[\x00-\x1f\x7f]/.test(value) && value.slice(1).split('/').every(part => part && Buffer.byteLength(part, 'utf8') <= 255 && part !== '.' && part !== '..');
        function packageRoot(value) {
          if (!absolute(value) || !value.endsWith('/payload/tree/clojure-lsp')) throw new Error('invalid original native target');
          const base = value.slice(0, -'/payload/tree/clojure-lsp'.length);
          if (!base.endsWith('/packages/lsp.clojure-lsp/2026.10.15-11.42.38/arm64-v8a') &&
              !base.endsWith('/packages/.staging/lsp.clojure-lsp-2026.10.15-11.42.38-local-ubuntu-vendor-de479f3506bf35a877d20c5b')) throw new Error('invalid managed package layout');
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
        function digest(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
        function readRegular(value, maximum, allowEmpty = false) {
          noLinkParents(value);
          const fd = fs.openSync(value, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
          try {
            const before = fs.fstatSync(fd);
            if (!before.isFile() || before.size < (allowEmpty ? 0 : 1) || before.size > maximum) throw new Error('invalid bounded package file');
            const bytes = Buffer.alloc(before.size + 1); let count = 0, n;
            while (count < bytes.length && (n = fs.readSync(fd, bytes, count, bytes.length - count, count)) > 0) count += n;
            if (count !== before.size || !unchanged(before, fs.fstatSync(fd)) || !unchanged(before, fs.lstatSync(value))) throw new Error('package file changed');
            retainedIdentities.push([value, before]);
            return bytes.subarray(0, count);
          } finally { fs.closeSync(fd); }
        }
        function preflight() {
          const base = packageRoot(target);
          noLinkParents(target);
          const data = readRegular(base + '/CLOJURE_LAUNCH_AUTHORITY.json', 16384);
          authority = JSON.parse(data.toString('utf8'));
          if (!authority || Array.isArray(authority) || Object.keys(authority).length !== 13 ||
              authority.schema !== 1 || authority.family !== 'lsp.clojure-lsp' || authority.version !== '2026.10.15-11.42.38' ||
              authority.archiveSha256 !== '232ac0cd191070a3b7b5532135ec9f97217b82324de7e97da0b1bf81353a3c94' ||
              authority.policy !== 'clojure-native-managed-local-log-v1' ||
              ['finalRoot','stageRoot','activationId','nativeSha256','helperSha256','wrapperSha256','originSha256'].some(key => typeof authority[key] !== 'string') ||
              !absolute(authority.finalRoot) || !authority.finalRoot.endsWith('/packages/lsp.clojure-lsp/2026.10.15-11.42.38/arm64-v8a') ||
              !absolute(authority.stageRoot) || !authority.stageRoot.endsWith('/packages/.staging/lsp.clojure-lsp-2026.10.15-11.42.38-local-ubuntu-vendor-de479f3506bf35a877d20c5b') ||
              !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}${'$'}/.test(authority.activationId) ||
              !/^[0-9a-f]{64}${'$'}/.test(authority.nativeSha256) || !/^[0-9a-f]{64}${'$'}/.test(authority.helperSha256) ||
              !/^[0-9a-f]{64}${'$'}/.test(authority.wrapperSha256) || !/^[0-9a-f]{64}${'$'}/.test(authority.originSha256) ||
              !Number.isSafeInteger(authority.nativeBytes) || authority.nativeBytes < 1 || authority.nativeBytes > 268435456 ||
              (base !== authority.finalRoot && base !== authority.stageRoot)) throw new Error('managed launch authority mismatch');
          if (readRegular(base + '/DROIDE_ACTIVATION_ID', 64).toString('ascii') !== authority.activationId) throw new Error('package activation owner mismatch');
          const helper = base + '/clojure-lsp-health.cjs';
          if (digest(readRegular(helper, 65536)) !== authority.helperSha256) throw new Error('managed helper changed');
          if (healthMode) { if (process.argv[1] !== helper) throw new Error('health helper belongs to another package'); }
          else {
            if (base !== authority.finalRoot || wrapper !== base + '/payload/bin/clojure-lsp' ||
                digest(readRegular(wrapper, 65536)) !== authority.wrapperSha256) throw new Error('editor wrapper owner mismatch');
          }
          const notice = readRegular(base + '/payload/tree/DROIDE-NOTICES/clojure-lsp-LICENSE.txt', 1080);
          if (notice.length !== 1080 || digest(notice) !== 'f2ea07ed625651b1e4a88ae49b620f0fecd32b64fe1f6609e4d0f12754d77e72') throw new Error('source notice changed');
          if (digest(readRegular(base + '/payload/tree/DROIDE-NOTICES/origin.json', 16384)) !== authority.originSha256) throw new Error('source origin changed');
          const expectedTree = authority.nativeSha256 + '  clojure-lsp\n' +
            'f2ea07ed625651b1e4a88ae49b620f0fecd32b64fe1f6609e4d0f12754d77e72  DROIDE-NOTICES/clojure-lsp-LICENSE.txt\n' +
            authority.originSha256 + '  DROIDE-NOTICES/origin.json\n';
          const tree = readRegular(base + '/TREE_SHA256SUMS', 4096).toString('ascii').split('\n').filter(Boolean).sort();
          if (JSON.stringify(tree) !== JSON.stringify(expectedTree.split('\n').filter(Boolean).sort())) throw new Error('admitted tree manifest mismatch');
          if (readRegular(base + '/TREE_LINKS.tsv', 1, true).length !== 0) throw new Error('native tree must reject links');
          targetFd = fs.openSync(target, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
          targetIdentity = fs.fstatSync(targetFd);
          if (!targetIdentity.isFile() || targetIdentity.size !== authority.nativeBytes || (targetIdentity.mode & 0o777) !== 0o755)
            throw new Error('native target size/type/mode changed');
          const hash = createHash('sha256'), chunk = Buffer.alloc(65536); let count = 0, n;
          while (count <= authority.nativeBytes && (n = fs.readSync(targetFd, chunk, 0, Math.min(chunk.length, authority.nativeBytes + 1 - count), count)) > 0) {
            count += n; hash.update(chunk.subarray(0, n));
          }
          if (count !== authority.nativeBytes || hash.digest('hex') !== authority.nativeSha256) throw new Error('admitted native SHA-256 changed');
          fs.accessSync(target, fs.constants.X_OK);
          if (base === authority.finalRoot) {
            const lock = JSON.parse(readRegular(base + '/RECIPE_LOCK.json', 65536).toString('utf8'));
            if (lock.schema !== 1 || lock.provider !== 'LOCAL_UBUNTU_VENDOR' || lock.familyId !== authority.family ||
                lock.version !== authority.version || lock.releaseTag !== authority.version || lock.assetSha256 !== authority.archiveSha256 ||
                lock.assetSize !== 43862001 || lock.assetName !== 'clojure-lsp-native-linux-aarch64.zip' || lock.artifactFormat !== 'ZIP' ||
                lock.command !== 'clojure-lsp' || lock.treeEntryPoint !== 'clojure-lsp' || lock.treeFiles !== 3 || lock.treeSymlinks !== 0)
              throw new Error('installed recipe receipt mismatch');
            // The installer/registry owns and seals this receipt. This is runtime consistency,
            // not a signature or protection against same-UID modification of all authority.
            const rows = readRegular(base + '/SHA256SUMS', 16384).toString('ascii').trimEnd().split('\n'), names = new Set();
            const required = ['CLOJURE_LAUNCH_AUTHORITY.json','DROIDE_ACTIVATION_ID','RECIPE_LOCK.json','TREE_COMMANDS.tsv','TREE_LINKS.tsv','TREE_SHA256SUMS',
              'clojure-lsp-health.cjs','payload/bin/clojure-lsp','payload/host-bin/clojure-lsp','payload/tree/clojure-lsp',
              'payload/tree/DROIDE-NOTICES/clojure-lsp-LICENSE.txt','payload/tree/DROIDE-NOTICES/origin.json'];
            if (rows.length !== required.length) throw new Error('installed seal inventory mismatch');
            for (const row of rows) {
              const match = /^([0-9a-f]{64})  ([^\r\n]+)${'$'}/.exec(row);
              if (!match || !required.includes(match[2]) || names.has(match[2])) throw new Error('invalid installed seal');
              names.add(match[2]);
              const actual = match[2] === 'payload/tree/clojure-lsp' ? authority.nativeSha256 : digest(readRegular(base + '/' + match[2], 65536, match[2] === 'TREE_LINKS.tsv'));
              if (actual !== match[1]) throw new Error('installed sealed integration changed');
            }
          }
          const allowed = new Set(['clojure-lsp','DROIDE-NOTICES/clojure-lsp-LICENSE.txt','DROIDE-NOTICES/origin.json']);
          let visited = 0;
          function inventory(directory, prefix) {
            const handle = fs.opendirSync(directory);
            try { for (;;) {
              const entry = handle.readSync(); if (entry === null) break;
              if (++visited > 8) throw new Error('native tree inventory bound exceeded');
              const relative = prefix + entry.name, value = directory + '/' + entry.name, stat = fs.lstatSync(value);
              if (stat.isSymbolicLink()) throw new Error('native tree link rejected');
              if (relative === 'DROIDE-NOTICES' && stat.isDirectory()) inventory(value, relative + '/');
              else if (!stat.isFile() || !allowed.delete(relative) || (stat.mode & 0o777) !== (relative === 'clojure-lsp' ? 0o755 : 0o644))
                throw new Error('native tree inventory or mode mismatch');
            } } finally { handle.closeSync(); }
          }
          inventory(base + '/payload/tree', '');
          if (allowed.size) throw new Error('native tree inventory incomplete');
          checkTarget();
        }
        function checkTarget() {
          if (targetFd === null || !unchanged(targetIdentity, fs.fstatSync(targetFd)) || !unchanged(targetIdentity, fs.lstatSync(target)))
            throw new Error('admitted native identity changed');
          for (const [value, identity] of retainedIdentities) if (!unchanged(identity, fs.lstatSync(value))) throw new Error('package receipt identity changed');
        }
        function checkRoot() {
          if (tmpFd === null || !same(fs.fstatSync(tmpFd), tmpIdentity) || !same(realDirectory('/tmp'), tmpIdentity) || !same(realDirectory(root), rootIdentity) ||
              rootFd === null || !same(fs.fstatSync(rootFd), rootIdentity)) throw new Error('private root identity changed');
        }
        function prepare() {
          const lease = process.env.DROIDE_PROCESS_LEASE;
          if (lease !== undefined && lease !== '' && !/^[0-9a-f]{20}${'$'}/.test(lease)) throw new Error('invalid process ownership token');
          process.umask(0o077);
          tmpIdentity = realDirectory('/tmp');
          tmpFd = fs.openSync('/tmp', fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
          if (!same(fs.fstatSync(tmpFd), tmpIdentity)) throw new Error('private parent identity changed');
          root = fs.mkdtempSync('/tmp/droide-clojure-'); rootIdentity = realDirectory(root);
          rootFd = fs.openSync(root, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
          checkRoot();
          if ((rootIdentity.mode & 0o777) !== 0o700) throw new Error('private root must be mode 0700');
          const directory = name => {
            checkRoot(); const anchored = '/proc/self/fd/' + rootFd + '/' + name;
            fs.mkdirSync(anchored, { mode: 0o700 });
            const identity = realDirectory(anchored);
            if ((identity.mode & 0o777) !== 0o700) throw new Error('private directory mode changed');
            privateDirectories.push([path.join(root, name), identity]);
            return path.join(root, name);
          };
          const temp = directory('tmp'), logs = directory('logs'); logPath = logs + '/server.out';
          logFd = fs.openSync(logPath, fs.constants.O_RDWR | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
          logIdentity = fs.fstatSync(logFd); checkLog();
          const environment = { LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TERM: 'dumb', NO_COLOR: '1', TMPDIR: temp };
          let cwd = process.cwd();
          if (healthMode) {
            Object.assign(environment, { HOME: directory('home'), PATH: '', XDG_CACHE_HOME: directory('cache'), XDG_CONFIG_HOME: directory('xdg-config'),
              XDG_DATA_HOME: directory('xdg-data'), XDG_STATE_HOME: directory('xdg-state'), XDG_RUNTIME_DIR: directory('xdg-runtime') });
            cwd = directory('work');
          } else {
            for (const key of ['HOME','USER','LOGNAME','PATH','JAVA_HOME','NPM_CONFIG_PREFIX','XDG_CACHE_HOME','XDG_CONFIG_HOME','XDG_DATA_HOME','XDG_STATE_HOME','XDG_RUNTIME_DIR'])
              if (Object.prototype.hasOwnProperty.call(process.env, key)) environment[key] = process.env[key];
          }
          if (lease) environment.DROIDE_PROCESS_LEASE = lease;
          const args = ['-Djava.io.tmpdir=' + temp];
          if (healthMode) args.push('-Duser.home=' + environment.HOME);
          args.push('--log-path', logPath, 'listen');
          checkRoot(); checkTarget(); checkLog(); return { environment, cwd, args };
        }
        function checkLog() {
          checkRoot();
          for (const [value, identity] of privateDirectories) if (!same(realDirectory(value), identity)) throw new Error('private directory identity changed');
          const current = fs.lstatSync(logPath), held = fs.fstatSync(logFd);
          if (!current.isFile() || current.isSymbolicLink() || !same(current, logIdentity) || !same(held, logIdentity) ||
              (current.mode & 0o777) !== 0o600 || (healthMode && current.size > 1048576)) throw new Error('private configured log identity/mode/soft bound failed');
        }
        function cleanup() {
          if (root === null) return;
          let entries = 0, bytes = 0;
          const deadline = Math.min(performance.now() + 2000, healthMode ? started + 40000 : Infinity);
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
          checkRoot(); checkTarget(); checkLog();
          if (healthMode && performance.now() - started >= 34400) throw new Error('health work deadline exhausted before native start');
          const record = { child: spawn(target, prepared.args, { cwd: prepared.cwd, env: prepared.environment,
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
          if (owner.closed && !groupAlive()) return;
          signalGroup('SIGTERM'); await delay(200);
          if (!owner.closed || groupAlive()) signalGroup('SIGKILL');
          const deadline = performance.now() + 3000;
          while (!owner.closed || groupAlive()) {
            if (performance.now() > deadline) throw new Error('owned child/group did not quiesce');
            await delay(20);
          }
        }
        function retained(reason) {
          const identity = rootIdentity ? ' dev=' + rootIdentity.dev + ' ino=' + rootIdentity.ino : '';
          console.error('Clojure: private state retained at ' + String(root) + identity + ': ' + reason);
        }
        async function finish(code, reason) {
          if (code !== 0) finishCode = code;
          if (finishing) return;
          finishing = true; clearTimeout(workTimer); clearInterval(logTimer);
          if (reason) console.error('Clojure: ' + reason);
          if (healthMode) console.error('Clojure: initial guest-temp log residue may remain; its actual native location and bytes are unproven. Only owned configured state is cleaned.');
          try { await stop(); }
          catch (error) { finishCode = 1; retained(error.message); closeDescriptors(); process.exit(1); return; }
          try { cleanup(); }
          catch (error) { finishCode = 1; retained(error.message); }
          closeDescriptors();
          if (healthMode && performance.now() - started > 40000) { finishCode = 1; console.error('Clojure: total health deadline exceeded'); }
          if (healthMode && finishCode === 0) console.log('Droide Clojure managed lifecycle initialize/shutdown OK; project analysis untested');
          process.exit(finishCode);
        }
        function closeDescriptors() {
          for (const descriptor of [targetFd, rootFd, tmpFd, logFd]) if (descriptor !== null) { try { fs.closeSync(descriptor); } catch (_) {} }
          targetFd = null; rootFd = null; tmpFd = null; logFd = null;
        }
    """.trimIndent() + "\n"
    internal val sourceScript = commonSource + """
        for (const [signal, code] of [['SIGTERM',143],['SIGINT',130],['SIGHUP',129]]) process.on(signal, () => { void finish(code, 'editor canceled'); });
        (async () => {
          try {
            const argv = process.argv.slice(1);
            if (argv.length !== 3 || argv[0] !== 'editor') throw new Error('invalid editor positional arguments');
            target = argv[1]; wrapper = argv[2]; preflight();
            const prepared = prepare(), record = start(prepared), child = record.child;
            const transportFailure = () => { void finish(1, 'editor transport closed'); };
            process.stdin.on('end', transportFailure); process.stdin.on('close', transportFailure); process.stdin.on('error', transportFailure);
            process.stdout.on('error', transportFailure); process.stderr.on('error', transportFailure);
            for (const stream of [child.stdin,child.stdout,child.stderr]) stream.on('error', transportFailure);
            process.stdin.pipe(child.stdin); child.stdout.pipe(process.stdout, { end: false }); child.stderr.pipe(process.stderr, { end: false });
            const result = await record.done;
            if (finishing) return;
            await finish(!result.error && result.signal === null && Number.isInteger(result.code) ? result.code : 1,
              result.error ? 'native start failed' : result.signal !== null ? 'native exited by signal' : null);
          } catch (error) { await finish(1, error.message); }
        })();
    """.trimIndent() + "\n"
    internal val script: String by lazy {
        ("eval(Buffer.from('" + Base64.getEncoder().encodeToString(sourceScript.toByteArray(Charsets.UTF_8)) +
            "','base64').toString('utf8'));").also { require(it.toByteArray(Charsets.UTF_8).size <= 32_000) }
    }
    internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val editorIdentity: String get() = "$REVISION:sha256=${sha256(script)}:selected-tool-env:private-configured-log:initial-temp-residue-unproven"
    private fun absolute(value: String) = value.startsWith('/') && value.toByteArray(Charsets.UTF_8).size <= 4095 &&
        value.none { it.code < 32 || it.code == 127 } && value.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.toByteArray(Charsets.UTF_8).size <= 255 }
    private fun packageRoot(target: String): String {
        require(absolute(target) && target.endsWith("/payload/tree/clojure-lsp"))
        return target.removeSuffix("/payload/tree/clojure-lsp").also {
            require(it.endsWith("/packages/lsp.clojure-lsp/2026.10.15-11.42.38/arm64-v8a") || it.endsWith("/packages/.staging/$STAGE"))
        }
    }
    fun editorCommand(target: String): List<String> {
        val root = packageRoot(target)
        require(!root.endsWith("/packages/.staging/$STAGE")) { "Editor may only use the activated managed package" }
        return listOf("/bin/sh", "-c", editorHandoff, "droide-clojure-env", "/usr/bin/node", "-e", script, "--", "editor", target, "$root/payload/bin/clojure-lsp")
    }
    fun healthCommand(helper: String, target: String): List<String> {
        val root = packageRoot(target)
        require(absolute(helper) && helper == "$root/${LocalUbuntuClojureLspHealth.FILE_NAME}")
        return listOf("/bin/sh", "-c", healthHandoff, "droide-clojure-health-env", "/usr/bin/node", helper, target)
    }
}
