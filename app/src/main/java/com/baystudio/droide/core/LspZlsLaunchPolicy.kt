package com.baystudio.droide.core

import java.security.MessageDigest
import java.util.Base64

/** Fixed positional launcher. Private state is not a sandbox; the host lease owns descendants. */
internal object LspZlsLaunchPolicy {
    internal val sourceScript: String = """
        'use strict';
        const fs = require('node:fs');
        const path = require('node:path');
        const os = require('node:os');
        const { spawn } = require('node:child_process');
        const argv = process.argv.slice(1);
        const MAX_ENTRIES = 4096, MAX_BYTES = 64 * 1024 * 1024, MAX_DEPTH = 32;
        let mode, compiler, pin, server, root = null, rootIdentity = null, rootFd = null;
        let owner = null, finishing = false, cancelCode = null;
        const validText = value => typeof value === 'string' && value.length > 0 && !/[\0\r\n]/.test(value);
        const absolute = value => validText(value) && path.isAbsolute(value);
        const stable = value => /^0\.16\.(0|[1-9][0-9]*)${'$'}/.test(value);
        const same = (a, b) => a.dev === b.dev && a.ino === b.ino;
        const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
        function checkRoot() {
          const current = fs.lstatSync(root);
          if (!current.isDirectory() || current.isSymbolicLink() || !same(current, rootIdentity) ||
              rootFd === null || !same(fs.fstatSync(rootFd), rootIdentity)) throw new Error('private root identity changed');
        }
        function prepare() {
          const parent = fs.lstatSync('/tmp');
          if (!parent.isDirectory() || parent.isSymbolicLink()) throw new Error('private state parent is not a real directory');
          process.umask(0o077);
          root = fs.mkdtempSync('/tmp/droide-zls.');
          rootIdentity = fs.lstatSync(root);
          rootFd = fs.openSync(root, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
          checkRoot();
          if ((rootIdentity.mode & 0o777) !== 0o700) throw new Error('private root has unexpected permissions');
          const directory = name => { const target = path.join(root, name); fs.mkdirSync(target, { mode: 0o700 }); return target; };
          const config = { global_cache_path: directory('cache') };
          let environment, cwd;
          if (mode === 'editor') {
            environment = { ...process.env, DROIDE_ZLS_SELECTED_ZIG: compiler };
            cwd = process.cwd();
            config.zig_exe_path = path.join(root, 'zig');
            fs.writeFileSync(config.zig_exe_path, '#!/bin/sh\nset -eu\nexec "${'$'}DROIDE_ZLS_SELECTED_ZIG" "${'$'}@"\n', { flag: 'wx', mode: 0o700 });
          } else {
            cwd = directory('work');
            environment = {
              HOME: directory('home'), XDG_CONFIG_HOME: directory('xdg-config'), XDG_CACHE_HOME: directory('xdg-cache'),
              XDG_DATA_HOME: directory('xdg-data'), XDG_STATE_HOME: directory('xdg-state'), XDG_RUNTIME_DIR: directory('xdg-runtime'),
              TMPDIR: directory('tmp'), PATH: '', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TERM: 'dumb', NO_COLOR: '1'
            };
            if (process.env.DROIDE_PROCESS_LEASE !== undefined) environment.DROIDE_PROCESS_LEASE = process.env.DROIDE_PROCESS_LEASE;
          }
          fs.writeFileSync(path.join(root, 'zls.json'), JSON.stringify(config), { flag: 'wx', mode: 0o600 });
          fs.writeFileSync(path.join(root, 'zls.log'), '', { flag: 'wx', mode: 0o600 });
          checkRoot();
          return { environment, cwd };
        }
        function cleanup() {
          if (root === null) return;
          let entries = 0, bytes = 0;
          const stopAt = Date.now() + 2000;
          const bound = depth => {
            if (entries > MAX_ENTRIES || depth > MAX_DEPTH || bytes > MAX_BYTES || Date.now() > stopAt)
              throw new Error('private cleanup bound exceeded');
            checkRoot();
          };
          const currentEntry = (target, expected) => {
            const current = fs.lstatSync(target);
            if (!same(current, expected) || current.mode !== expected.mode) throw new Error('private entry identity changed');
            return current;
          };
          const remove = (target, depth, expected) => {
            ++entries; bound(depth);
            const actual = currentEntry(target, expected);
            if (actual.isSymbolicLink()) { bound(depth); currentEntry(target, expected); fs.unlinkSync(target); return; }
            if (actual.isFile()) {
              bytes += actual.size; bound(depth);
              const beforeDelete = currentEntry(target, expected);
              if (beforeDelete.size !== actual.size) throw new Error('private file changed during cleanup');
              fs.unlinkSync(target); return;
            }
            if (!actual.isDirectory()) throw new Error('private tree contains an unexpected special file');
            const descriptor = fs.openSync(target, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
            let directory;
            try {
              if (!same(fs.fstatSync(descriptor), expected)) throw new Error('private directory identity changed');
              directory = fs.opendirSync(target);
              while (true) {
                bound(depth); currentEntry(target, expected);
                if (!same(fs.fstatSync(descriptor), expected)) throw new Error('private directory identity changed');
                const entry = directory.readSync();
                if (entry === null) break;
                const childPath = path.join(target, entry.name);
                remove(childPath, depth + 1, fs.lstatSync(childPath));
              }
            } finally {
              if (directory) directory.closeSync();
              fs.closeSync(descriptor);
            }
            bound(depth); currentEntry(target, expected); fs.rmdirSync(target);
          };
          try { checkRoot(); remove(root, 0, rootIdentity); root = null; }
          finally { if (rootFd !== null) { fs.closeSync(rootFd); rootFd = null; } }
        }
        function signalGroup(record, signal) {
          if (!record || !record.child.pid) return;
          try { process.kill(-record.child.pid, signal); }
          catch (error) { if (error.code !== 'ESRCH') { try { record.child.kill(signal); } catch (_) {} } }
        }
        function start(command, args, options) {
          if (finishing) throw new Error('launch was canceled');
          const record = { child: spawn(command, args, { ...options, detached: true, shell: false }), closed: false, error: null, stop: null };
          owner = record;
          record.done = new Promise(resolve => {
            record.child.once('error', error => { record.error = error; });
            record.child.once('close', (code, signal) => { record.closed = true; resolve({ code, signal, error: record.error }); });
          });
          return record;
        }
        function stop(record) {
          if (!record) return Promise.resolve();
          if (record.stop) return record.stop;
          record.stop = (async () => {
            signalGroup(record, 'SIGTERM');
            await delay(200);
            signalGroup(record, 'SIGKILL');
            if (!record.closed) {
              let timer;
              try {
                await Promise.race([record.done, new Promise((_, reject) => {
                  timer = setTimeout(() => reject(new Error('direct child cleanup timed out')), 3000);
                })]);
              } finally { clearTimeout(timer); }
            }
          })();
          return record.stop;
        }
        async function finish(code, reason) {
          if (finishing) return;
          finishing = true;
          if (reason) console.error('ZLS launch: ' + reason);
          try { await stop(owner); }
          catch (error) {
            // Retain state when a direct child could still be using it. The host lease
            // remains the ultimate owner, including detached descendants and SIGKILL.
            console.error('ZLS launch: ' + error.message);
            if (rootFd !== null) { fs.closeSync(rootFd); rootFd = null; }
            process.exit(1); return;
          }
          try { cleanup(); }
          catch (error) {
            console.error('ZLS launch: private state retained: ' + error.message);
            if (mode === 'health') code = 1;
          }
          process.exit(code);
        }
        function checkKernel() {
          if (os.platform() !== 'linux') throw new Error('ZLS requires a Linux kernel');
          const release = os.release();
          const match = typeof release === 'string' && release.length <= 128 &&
            /^([0-9]+)\.([0-9]+)\.([0-9]+)(?:[-+._][A-Za-z0-9._+-]+)?${'$'}/.exec(release);
          if (!match || /(?:^|[-+._])(?:rc|alpha|beta|pre|next)[0-9]*(?:${'$'}|[-+._])/i.test(release))
            throw new Error('Linux kernel version is unavailable or prerelease');
          const major = Number(match[1]), minor = Number(match[2]), patch = Number(match[3]);
          if (![major, minor, patch].every(Number.isSafeInteger) || major < 5 || (major === 5 && minor < 10))
            throw new Error('ZLS requires Linux 5.10 or newer');
          console.error('ZLS launch: ' + (major > 6 || (major === 6 && minor >= 5) ?
            'native file watching is kernel-eligible; target behavior is unverified' : 'manual didSave build updates required'));
        }
        async function preflight() {
          checkKernel();
          fs.accessSync(compiler, fs.constants.X_OK);
          if (!fs.statSync(compiler).isFile()) throw new Error('selected compiler is not an executable file');
          const record = start(compiler, ['version'], { cwd: process.cwd(), env: { ...process.env }, stdio: ['ignore', 'pipe', 'pipe'] });
          let stdout = Buffer.alloc(0), stdoutBytes = 0, stderrBytes = 0, failure = null;
          const fail = reason => { if (failure === null) { failure = reason; stop(record).catch(error => finish(1, error.message)); } };
          record.child.stdout.on('data', chunk => {
            stdoutBytes += chunk.length;
            if (stdoutBytes > 128) fail('compiler version stdout bound exceeded');
            else if (failure === null) stdout = Buffer.concat([stdout, chunk]);
          });
          record.child.stderr.on('data', chunk => {
            stderrBytes += chunk.length;
            if (stderrBytes > 4096) fail('compiler version stderr bound exceeded');
          });
          record.child.stdout.on('error', error => fail('compiler version stdout failed: ' + error.message));
          record.child.stderr.on('error', error => fail('compiler version stderr failed: ' + error.message));
          const timer = setTimeout(() => fail('compiler version timed out'), 5000);
          let result;
          try { result = await record.done; }
          finally { clearTimeout(timer); }
          await stop(record);
          if (finishing) throw new Error('launch was canceled');
          owner = null;
          if (failure !== null) throw new Error(failure);
          if (result.error || result.code !== 0 || result.signal !== null || stderrBytes !== 0)
            throw new Error('selected compiler version did not exit cleanly');
          const version = stdout.toString('ascii');
          if ([...stdout].some(byte => byte > 127) || !/^0\.16\.(0|[1-9][0-9]*)\n${'$'}/.test(version))
            throw new Error('selected compiler must report exactly one stable 0.16.x version');
          if (pin !== '-' && version !== pin + '\n') throw new Error('selected compiler does not match the exact project version hint');
        }
        for (const [signal, code] of [['SIGTERM', 143], ['SIGINT', 130], ['SIGHUP', 129]])
          process.on(signal, () => { cancelCode = code; void finish(code, 'launch canceled'); });
        (async () => {
          try {
            mode = argv[0];
            if (mode === 'editor' && argv.length === 4) {
              compiler = argv[1]; pin = argv[2]; server = argv[3];
              if (!absolute(compiler) || !validText(pin) || (pin !== '-' && !stable(pin))) throw new Error('invalid editor arguments');
            } else if (mode === 'health' && argv.length === 2) server = argv[1];
            else throw new Error('invalid positional launch mode');
            if (!absolute(server)) throw new Error('original server must be an absolute path');
            const lease = process.env.DROIDE_PROCESS_LEASE;
            if (lease !== undefined && !/^[0-9a-f]{20}${'$'}/.test(lease)) throw new Error('invalid process ownership token');
            if (mode === 'editor') await preflight();
            if (finishing) return;
            const prepared = prepare();
            const serverArgs = ['--config-path', path.join(root, 'zls.json'), '--log-file', path.join(root, 'zls.log')];
            if (mode === 'health') serverArgs.push('--disable-lsp-logs');
            const record = start(server, serverArgs, { cwd: prepared.cwd, env: prepared.environment, stdio: 'inherit' });
            const result = await record.done;
            if (finishing) return;
            if (result.error) await finish(1, 'cannot start original server: ' + result.error.message);
            else if (result.signal !== null) await finish(1, 'original server exited by signal ' + result.signal);
            else await finish(Number.isInteger(result.code) ? result.code : 1);
          } catch (error) { await finish(cancelCode || 1, error.message); }
        })();
    """.trimIndent() + "\n"

    // ProcessSecurityPolicy forbids literal LF/CR in an argument. This immutable bootstrap decodes
    // only fixed app-owned bytes; no argv/project value is code.
    internal val script: String by lazy {
        "eval(Buffer.from('" + Base64.getEncoder().encodeToString(sourceScript.toByteArray(Charsets.UTF_8)) +
            "','base64').toString('utf8'));"
    }

    internal val scriptSha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    val editorIdentity: String get() = "zls-inline-node-v1:sha256=$scriptSha256:editor:absolute-zig:exact-nearest-pin:stdio:owned-groups:private-proxy"

    private fun absolute(value: String): Boolean = value.startsWith('/') && value.none { it == '\u0000' || it == '\r' || it == '\n' }

    private fun requireOriginal(argv: List<String>, nodePath: String) {
        require(argv.size == 1 && absolute(argv.single()) && absolute(nodePath)) {
            "ZLS launcher requires one original absolute server and an absolute Node path"
        }
    }

    fun command(argv: List<String>, nodePath: String, zigPath: String, pin: String? = null): List<String> {
        requireOriginal(argv, nodePath)
        require(absolute(zigPath) && (pin == null || pin.matches(Regex("0\\.16\\.(0|[1-9][0-9]*)")))) {
            "ZLS launcher requires an absolute selected compiler and an exact stable 0.16.x hint"
        }
        return listOf(nodePath, "-e", script, "--", "editor", zigPath, pin ?: "-") + argv
    }

    fun healthCommand(argv: List<String>, nodePath: String): List<String> {
        requireOriginal(argv, nodePath)
        return listOf(nodePath, "-e", script, "--", "health") + argv
    }
}
