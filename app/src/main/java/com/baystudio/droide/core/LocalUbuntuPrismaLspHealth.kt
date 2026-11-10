package com.baystudio.droide.core

import java.security.MessageDigest

/** Strict zero-document Prisma health. Runtime consistency is not a sandbox or ownership signature. */
internal object LocalUbuntuPrismaLspHealth {
    const val FILE_NAME = "prisma-lsp-health.cjs"
    const val TIMEOUT_MS = 45_000L
    const val REVISION = "prisma-zero-document-source-path-health-v1"
    const val CONTRACT_SHA256 = "6c2805fd4a874d03d89aaceb931c1ff13376ffab6284127cad1fd062fcb3356c"

    val script: String = """
        'use strict';
        // Reviewed zero-document supervisor. Adjacent records establish consistency, not a same-UID boundary.
        const fs = require('node:fs');
        const path = require('node:path');
        const crypto = require('node:crypto');
        const { spawn } = require('node:child_process');
        const { TextDecoder } = require('node:util');
        const { pathToFileURL } = require('node:url');
        const started = process.hrtime.bigint();
        const elapsed = () => Number(process.hrtime.bigint() - started) / 1000000;
        const LIMIT = Object.freeze({ startup: 20000, shutdown: 5000, exit: 5000, term: 2000, reap: 3000,
          cleanup: 2000, work: 30000, absolute: 37000, header: 8192, frame: 1048576, output: 4194304,
          messages: 256, tail: 65536, write: 16384, pending: 65536, drain: 2000, depth: 64, values: 50000,
          cleanupEntries: 4096, cleanupDepth: 64 });
        const HEALTH_REVISION = 'prisma-zero-document-source-path-health-v1';
        const HEALTH_CONTRACT = '6c2805fd4a874d03d89aaceb931c1ff13376ffab6284127cad1fd062fcb3356c';
        const POLICY_REVISION = 'prisma-managed-public-root-launch-v1';
        const CLOSURE_CONTRACT = '6de1f7e4710d74447f40b56e700e2f6d72fec0e7daad211f72eaede4ef0d202d';
        const LAUNCHER_RELATIVE = 'payload/npm/droide-prisma-legacy.cjs';
        const HELPER_RELATIVE = 'payload/prisma-lsp-health.cjs';
        const ROOT_INTEGRITY = 'sha512-3ATe7MSnR4mFBCaiDyvNDrjWtfbz15y0guzGQ8oXOzJDBnHE4AzVxGobP7PgrWxsqry1CCY0x7/FfExZzAHKAA==';
        const DEPENDENCIES = {"@babel/helper-string-parser":"7.29.7","@babel/helper-validator-identifier":"7.29.7","@babel/parser":"7.29.8","@babel/types":"7.29.8","@prisma/config":"7.9.0-dev.4","@prisma/prisma-schema-wasm":"7.8.0-6.3c6e192761c0362d496ed980de936e2f3cebcd3a","@prisma/schema-files-loader":"7.9.0-dev.4","@standard-schema/spec":"1.0.0","@types/js-levenshtein":"1.1.3","c12":"3.3.4","chokidar":"5.0.0","confbox":"0.2.4","deepmerge-ts":"7.1.5","defu":"6.1.7","destr":"2.0.5","dotenv":"17.4.1","effect":"3.20.0","empathic":"2.0.0","exsolve":"1.0.8","fast-check":"3.23.2","fs-extra":"11.3.0","giget":"3.2.0","graceful-fs":"4.2.11","jiti":"2.6.1","js-levenshtein":"1.1.6","jsonfile":"6.2.0","klona":"2.0.6","magicast":"0.5.4","ohash":"2.0.11","pathe":"2.0.3","perfect-debounce":"2.1.0","pkg-types":"2.3.0","pure-rand":"6.1.0","rc9":"3.0.1","readdirp":"5.0.0","source-map-js":"1.2.1","universalify":"2.0.1","vscode-jsonrpc":"8.1.0","vscode-languageserver":"8.1.0","vscode-languageserver-protocol":"3.17.3","vscode-languageserver-textdocument":"1.0.11","vscode-languageserver-types":"3.17.3","vscode-uri":"3.1.0"};
        const CAPABILITIES = Object.freeze({ textDocumentSync: 2, definitionProvider: true,
          documentFormattingProvider: true, completionProvider: { resolveProvider: true,
            triggerCharacters: ['@', '"', '.'] }, hoverProvider: true, renameProvider: true,
          documentSymbolProvider: true, referencesProvider: true });
        const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
        const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
        const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
        function requireCheck(value, reason) { if (!value) throw new Error(reason); }
        function keys(value, expected) {
          if (!object(value)) return false;
          const actual = Object.keys(value).sort(), wanted = expected.slice().sort();
          return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
        }
        function same(a, b) {
          if (a === b) return true;
          if (Array.isArray(a) || Array.isArray(b)) return Array.isArray(a) && Array.isArray(b) &&
            a.length === b.length && a.every((v, i) => same(v, b[i]));
          return object(a) && object(b) && keys(a, Object.keys(b)) && Object.keys(a).every(k => same(a[k], b[k]));
        }
        // A bounded recursive-descent JSON parser rejects duplicate decoded keys, including escaped aliases.
        function parseJson(text) {
          let at = 0, count = 0;
          function whitespace() { while (at < text.length && /[\x20\t\r\n]/.test(text[at])) at++; }
          function string() {
            requireCheck(text[at] === '"', 'invalid JSON string');
            const begin = at++;
            while (at < text.length) {
              const code = text.charCodeAt(at++);
              if (code === 34) return JSON.parse(text.slice(begin, at));
              requireCheck(code >= 32, 'invalid JSON string');
              if (code === 92) {
                requireCheck(at < text.length, 'invalid JSON escape');
                const escape = text[at++];
                if (escape === 'u') {
                  requireCheck(/^[0-9a-fA-F]{4}${'$'}/.test(text.slice(at, at + 4)), 'invalid JSON escape'); at += 4;
                } else requireCheck('"\\/bfnrt'.includes(escape), 'invalid JSON escape');
              }
            }
            throw new Error('unterminated JSON string');
          }
          function value(depth) {
            requireCheck(depth <= LIMIT.depth && ++count <= LIMIT.values, 'JSON complexity limit exceeded');
            whitespace(); const ch = text[at];
            if (ch === '"') return string();
            if (ch === '{') {
              at++; whitespace(); const result = Object.create(null);
              if (text[at] === '}') { at++; return result; }
              while (at < text.length) {
                whitespace(); const key = string();
                requireCheck(!own(result, key), 'duplicate JSON key'); whitespace();
                requireCheck(text[at++] === ':', 'invalid JSON object'); result[key] = value(depth + 1); whitespace();
                const sep = text[at++]; if (sep === '}') return result;
                requireCheck(sep === ',', 'invalid JSON object');
              }
            } else if (ch === '[') {
              at++; whitespace(); const result = [];
              if (text[at] === ']') { at++; return result; }
              while (at < text.length) {
                result.push(value(depth + 1)); whitespace(); const sep = text[at++];
                if (sep === ']') return result; requireCheck(sep === ',', 'invalid JSON array');
              }
            } else {
              for (const [literal, result] of [['true', true], ['false', false], ['null', null]])
                if (text.slice(at, at + literal.length) === literal) { at += literal.length; return result; }
              const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(text.slice(at));
              if (match) { at += match[0].length; const number = Number(match[0]);
                requireCheck(Number.isFinite(number), 'invalid JSON number'); return number; }
            }
            throw new Error('invalid JSON value');
          }
          const result = value(0); whitespace(); requireCheck(at === text.length, 'trailing JSON data'); return result;
        }
        function safeAbsolute(value) {
          return typeof value === 'string' && value.length > 1 && value.length <= 4096 && value[0] === '/' &&
            !/[\x00-\x20\x7f\\:]/.test(value) && value.split('/').slice(1).every(p => p && p !== '.' && p !== '..');
        }
        function stamp(stat) { return [stat.dev, stat.ino, stat.size, stat.mtimeNs, stat.ctimeNs].map(String).join(':'); }
        function inode(stat) { return String(stat.dev) + ':' + String(stat.ino); }
        const retained = new Map();
        function checkWork() { requireCheck(elapsed() < LIMIT.work, 'absolute work deadline exceeded'); }
        function safeParents(filename) {
          requireCheck(safeAbsolute(filename), 'unsafe integration path');
          let current = '/';
          for (const part of filename.split('/').slice(1, -1)) {
            checkWork(); current = path.join(current, part); const stat = fs.lstatSync(current, { bigint: true });
            requireCheck(stat.isDirectory() && !stat.isSymbolicLink(), 'unsafe integration parent');
          }
        }
        function readRegular(filename, limit) {
          checkWork(); safeParents(filename);
          const before = fs.lstatSync(filename, { bigint: true });
          requireCheck(before.isFile() && !before.isSymbolicLink() && before.size > 0n && before.size <= BigInt(limit),
            'invalid integration file');
          const fd = fs.openSync(filename, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
          try {
            const opened = fs.fstatSync(fd, { bigint: true }); requireCheck(stamp(opened) === stamp(before), 'integration file changed');
            const bytes = Buffer.alloc(Number(opened.size)); let offset = 0;
            while (offset < bytes.length) {
              checkWork(); const amount = fs.readSync(fd, bytes, offset, Math.min(65536, bytes.length - offset), offset);
              requireCheck(amount > 0, 'integration file truncated'); offset += amount;
            }
            requireCheck(stamp(fs.fstatSync(fd, { bigint: true })) === stamp(opened) &&
              stamp(fs.lstatSync(filename, { bigint: true })) === stamp(opened), 'integration file changed');
            retained.set(filename, stamp(opened)); return bytes;
          } finally { fs.closeSync(fd); }
        }
        function hash(bytes) { return crypto.createHash('sha256').update(bytes).digest('hex'); }
        function preflight() {
          requireCheck(process.execArgv.length === 0, 'unexpected Node runtime arguments');
          requireCheck(process.platform === 'linux' && process.arch === 'arm64', 'health requires Linux ARM64');
          requireCheck(Number(process.versions.node.split('.')[0]) >= 22, 'health requires Node 22 or newer');
          requireCheck(safeAbsolute(__filename) && process.argv[1] === __filename &&
            __filename.endsWith('/' + HELPER_RELATIVE), 'unexpected helper path');
          const root = __filename.slice(0, -('/' + HELPER_RELATIVE).length);
          requireCheck(safeAbsolute(root), 'unexpected package root');
          const launcher = root + '/' + LAUNCHER_RELATIVE;
          const authority = parseJson(decoder.decode(readRegular(root + '/PRISMA_LAUNCH_AUTHORITY.json', 65536)));
          const authorityFields = ['schema', 'familyId', 'version', 'policyRevision', 'closureContractSha256',
            'finalGuestRoot', 'stageGuestRoot', 'activationId', 'nodeCommand', 'nodeVersion', 'nodeDependencyKeyOrNull',
            'launcherSha256', 'healthSourceSha256', 'lockSha256'];
          requireCheck(keys(authority, authorityFields) && authority.schema === 1 &&
            authority.familyId === 'lsp.prisma-language-server' && authority.version === '31.13.0' &&
            authority.policyRevision === POLICY_REVISION && authority.closureContractSha256 === CLOSURE_CONTRACT,
            'invalid Prisma authority');
          requireCheck(safeAbsolute(authority.stageGuestRoot) && safeAbsolute(authority.finalGuestRoot) &&
            authority.stageGuestRoot !== authority.finalGuestRoot &&
            (root === authority.stageGuestRoot || root === authority.finalGuestRoot), 'unrecognized package root');
          requireCheck(typeof authority.activationId === 'string' &&
            /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}${'$'}/.test(authority.activationId), 'invalid activation identity');
          requireCheck(safeAbsolute(authority.nodeCommand) && process.execPath === authority.nodeCommand &&
            process.versions.node === authority.nodeVersion &&
            /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?${'$'}/.test(authority.nodeVersion) &&
            (authority.nodeDependencyKeyOrNull === null ||
              authority.nodeDependencyKeyOrNull === 'runtime.node@' + authority.nodeVersion),
            'runtime identity changed');
          requireCheck(same(process.argv.slice(2), [authority.nodeCommand, launcher, '--stdio']), 'invalid health supervisor arguments');
          const receipt = parseJson(decoder.decode(readRegular(root + '/RECIPE_LOCK.json', 65536)));
          requireCheck(keys(receipt, ['schema', 'familyId', 'version', 'packageName', 'packageVersion', 'registryIntegrity',
            'nodeMajor', 'npmVersion', 'provenanceUrl', 'nodeCommand', 'additionalDependencies', 'allowLifecycleScripts', 'launchProfile']) &&
            receipt.schema === 1 && receipt.familyId === authority.familyId && receipt.version === authority.version &&
            receipt.packageName === '@prisma/language-server' && receipt.packageVersion === '31.13.0' &&
            receipt.registryIntegrity === ROOT_INTEGRITY && receipt.provenanceUrl === 'https://github.com/prisma/language-tools' &&
            receipt.allowLifecycleScripts === false && receipt.nodeCommand === authority.nodeCommand &&
            receipt.nodeMajor === Number(process.versions.node.split('.')[0]) && typeof receipt.npmVersion === 'string' &&
            /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?${'$'}/.test(receipt.npmVersion) && Number(receipt.npmVersion.split('.')[0]) >= 10 &&
            same(receipt.additionalDependencies, DEPENDENCIES), 'invalid Prisma recipe');
          const profile = { revision: POLICY_REVISION, closureContractSha256: CLOSURE_CONTRACT,
            launcherRelativePath: LAUNCHER_RELATIVE, launcherSha256: authority.launcherSha256,
            healthRelativePath: HELPER_RELATIVE, healthRevision: HEALTH_REVISION, healthContractSha256: HEALTH_CONTRACT,
            healthSourceSha256: authority.healthSourceSha256, nodeCommand: authority.nodeCommand,
            nodeVersion: authority.nodeVersion, nodeDependencyKeyOrNull: authority.nodeDependencyKeyOrNull };
          requireCheck(same(receipt.launchProfile, profile), 'Prisma launch profile changed');
          requireCheck(decoder.decode(readRegular(root + '/DROIDE_ACTIVATION_ID', 64)) === authority.activationId, 'activation changed');
          for (const key of ['launcherSha256', 'healthSourceSha256', 'lockSha256'])
            requireCheck(typeof authority[key] === 'string' && /^[0-9a-f]{64}${'$'}/.test(authority[key]), 'invalid integration hash');
          requireCheck(hash(readRegular(__filename, 262144)) === authority.healthSourceSha256 &&
            hash(readRegular(launcher, 1048576)) === authority.launcherSha256 &&
            hash(readRegular(root + '/payload/npm/package-lock.json', 8000000)) === authority.lockSha256, 'integration bytes changed');
          // The launcher performs strict package-lock, installed layout and final full-seal checks before import.
          if (root === authority.stageGuestRoot) {
            let absent = false; try { fs.lstatSync(root + '/NPM_TREE_SEAL.json'); } catch (error) {
              if (error.code === 'ENOENT') absent = true; else throw new Error('cannot inspect stage seal');
            }
            requireCheck(absent, 'stale stage seal');
          } else readRegular(root + '/NPM_TREE_SEAL.json', 8000000);
          return { root, launcher, authority };
        }
        let child = null, childClosed = false, childCode = null, childSignal = null;
        let finalized = false, closing = false, failure = null, privateRoot = null, privateIdentity = null;
        const privateDirectories = new Map();
        let phase = 'initialize', phaseTimer = null, absoluteTimer = null;
        let buffer = Buffer.alloc(0), frameLength = null, received = 0, messages = 0, stderrTail = Buffer.alloc(0);
        let pendingWrites = 0, writeChain = Promise.resolve(), inbox = [], processing = false;
        let exitFlushed = false, inputEnded = false, outstandingResponse = null;
        function safeReason(reason) {
          return typeof reason === 'string' && /^[A-Za-z][A-Za-z0-9 -]{0,95}${'$'}/.test(reason)
            ? reason : 'integration or private context validation failed';
        }
        function fail(reason) { if (!failure) failure = safeReason(reason); void finalize(false); }
        function armPhase(name, milliseconds) {
          phase = name; clearTimeout(phaseTimer);
          const remaining = Math.min(milliseconds, LIMIT.work - elapsed());
          if (remaining <= 0) { fail('absolute work deadline exceeded'); return; }
          phaseTimer = setTimeout(() => fail(name + ' timed out'), remaining);
        }
        function makePrivateContext() {
          const tmp = fs.lstatSync('/tmp', { bigint: true });
          requireCheck(tmp.isDirectory() && !tmp.isSymbolicLink(), 'unsafe health temporary directory');
          privateRoot = fs.mkdtempSync('/tmp/droide-prisma-health-'); fs.chmodSync(privateRoot, 0o700);
          const rootStat = fs.lstatSync(privateRoot, { bigint: true });
          requireCheck(rootStat.isDirectory() && !rootStat.isSymbolicLink() && (rootStat.mode & 0o777n) === 0o700n,
            'invalid private health root');
          privateIdentity = inode(rootStat);
          for (const name of ['cwd', 'home', 'xdg-cache', 'xdg-config', 'xdg-data', 'xdg-state', 'xdg-runtime', 'tmp']) {
            const target = privateRoot + '/' + name; fs.mkdirSync(target, { mode: 0o700 });
            const stat = fs.lstatSync(target, { bigint: true });
            requireCheck(stat.isDirectory() && !stat.isSymbolicLink() && stat.dev === rootStat.dev &&
              (stat.mode & 0o777n) === 0o700n, 'invalid private health directory'); privateDirectories.set(target, inode(stat));
          }
          const env = { HOME: privateRoot + '/home', XDG_CACHE_HOME: privateRoot + '/xdg-cache',
            XDG_CONFIG_HOME: privateRoot + '/xdg-config', XDG_DATA_HOME: privateRoot + '/xdg-data',
            XDG_STATE_HOME: privateRoot + '/xdg-state', XDG_RUNTIME_DIR: privateRoot + '/xdg-runtime',
            TMPDIR: privateRoot + '/tmp', TMP: privateRoot + '/tmp', TEMP: privateRoot + '/tmp',
            PATH: '', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TERM: 'dumb' };
          if (own(process.env, 'DROIDE_PROCESS_LEASE')) {
            requireCheck(/^[0-9a-f]{20}${'$'}/.test(process.env.DROIDE_PROCESS_LEASE), 'invalid process lease');
            env.DROIDE_PROCESS_LEASE = process.env.DROIDE_PROCESS_LEASE;
          }
          return env;
        }
        function countOutput(chunk) { received += chunk.length; requireCheck(received <= LIMIT.output, 'combined output budget exceeded'); }
        function send(message) {
          const body = Buffer.from(JSON.stringify(message), 'utf8');
          const frame = Buffer.concat([Buffer.from('Content-Length: ' + body.length + '\r\n\r\n', 'ascii'), body]);
          requireCheck(frame.length <= LIMIT.write && pendingWrites + frame.length <= LIMIT.pending, 'write budget exceeded');
          pendingWrites += frame.length;
          const job = writeChain.then(() => new Promise((resolve, reject) => {
            if (closing || failure || !child || child.stdin.destroyed || child.stdin.writableEnded) return reject(new Error('server input closed'));
            let settled = false, callback = false, drained = false;
            const timer = setTimeout(() => finish(new Error('server write timed out')), Math.min(LIMIT.drain, Math.max(1, LIMIT.work - elapsed())));
            function finish(error) {
              if (settled) return; if (!error && (!callback || !drained)) return;
              settled = true; clearTimeout(timer); child.stdin.removeListener('drain', onDrain);
              child.stdin.removeListener('close', onClose); child.stdin.removeListener('error', onError);
              error ? reject(error) : resolve();
            }
            function onDrain() { drained = true; finish(); }
            function onClose() { finish(new Error('server input closed during write')); }
            function onError() { finish(new Error('server input failed')); }
            child.stdin.once('close', onClose); child.stdin.once('error', onError); child.stdin.on('drain', onDrain);
            try {
              if (own(message, 'id')) {
                requireCheck(outstandingResponse === null, 'overlapping health request'); outstandingResponse = message.id;
              }
              const accepted = child.stdin.write(frame, error => { callback = true; finish(error ? new Error('server write failed') : null); });
              drained = accepted; finish();
            } catch (_) { finish(new Error('server write failed')); }
          }));
          writeChain = job.catch(() => {});
          return job.finally(() => { pendingWrites -= frame.length; });
        }
        function endInput() {
          return new Promise((resolve, reject) => {
            let done = false;
            const timer = setTimeout(() => finish(new Error('server input end timed out')), LIMIT.drain);
            function finish(error) { if (done) return; done = true; clearTimeout(timer);
              child.stdin.removeListener('error', onError); error ? reject(error) : resolve(); }
            function onError() { finish(new Error('server input end failed')); }
            child.stdin.once('error', onError);
            try { child.stdin.end(error => { if (!error) inputEnded = true; finish(error ? new Error('server input end failed') : null); }); }
            catch (_) { finish(new Error('server input end failed')); }
          });
        }
        async function handle(message) {
          requireCheck(object(message) && message.jsonrpc === '2.0', 'invalid JSON-RPC envelope');
          requireCheck(phase !== 'exit' && phase !== 'exit-write', 'unexpected protocol output after shutdown');
          if (own(message, 'method')) {
            requireCheck(keys(message, ['jsonrpc', 'method', 'params']) && message.method === 'window/logMessage' &&
              keys(message.params, ['type', 'message']) && Number.isInteger(message.params.type) &&
              message.params.type >= 1 && message.params.type <= 4 && typeof message.params.message === 'string' &&
              Buffer.byteLength(message.params.message, 'utf8') <= LIMIT.tail, 'unexpected server message');
            requireCheck(message.params.type !== 1, 'server reported an error'); return;
          }
          requireCheck(keys(message, ['jsonrpc', 'id', 'result']), 'invalid response shape');
          if (phase === 'initialize') {
            requireCheck(message.id === 1 && keys(message.result, ['capabilities']) &&
              same(message.result.capabilities, CAPABILITIES), 'initialize capabilities changed');
            phase = 'initialized-write';
            await send({ jsonrpc: '2.0', method: 'initialized', params: {} });
            armPhase('shutdown', LIMIT.shutdown);
            await send({ jsonrpc: '2.0', id: 2, method: 'shutdown' });
          } else if (phase === 'shutdown') {
            requireCheck(message.id === 2 && message.result === null, 'invalid shutdown response');
            armPhase('exit-write', LIMIT.exit);
            await send({ jsonrpc: '2.0', method: 'exit' }); exitFlushed = true;
            await endInput(); phase = 'exit';
          } else throw new Error('unexpected response');
        }
        async function drainInbox() {
          if (processing || closing) return; processing = true;
          try { while (inbox.length && !closing) await handle(inbox.shift()); }
          catch (error) { fail(error.message); }
          finally { processing = false; maybeComplete(); }
        }
        function receive(chunk) {
          countOutput(chunk); requireCheck(!closing, 'output during cleanup');
          buffer = Buffer.concat([buffer, chunk]);
          while (!closing) {
            if (frameLength === null) {
              const end = buffer.indexOf('\r\n\r\n');
              if (end < 0) { requireCheck(buffer.length <= LIMIT.header, 'protocol header limit exceeded'); return; }
              requireCheck(end > 0 && end <= LIMIT.header, 'protocol header limit exceeded');
              const header = buffer.subarray(0, end);
              for (const byte of header) requireCheck(byte >= 32 && byte <= 126 || byte === 13 || byte === 10,
                'invalid ASCII protocol header');
              let declared = null, contentType = false;
              for (const line of header.toString('ascii').split('\r\n')) {
                const match = /^([A-Za-z-]+): *(.*)${'$'}/.exec(line); requireCheck(match !== null, 'invalid protocol header');
                const name = match[1].toLowerCase();
                if (name === 'content-length') {
                  requireCheck(declared === null && /^[1-9][0-9]*${'$'}/.test(match[2]), 'invalid Content-Length');
                  declared = Number(match[2]);
                } else if (name === 'content-type') {
                  requireCheck(!contentType && match[2] === 'application/vscode-jsonrpc; charset=utf-8', 'invalid Content-Type'); contentType = true;
                } else throw new Error('unexpected protocol header');
              }
              requireCheck(Number.isSafeInteger(declared) && declared > 0 && declared <= LIMIT.frame, 'invalid Content-Length');
              frameLength = declared; buffer = buffer.subarray(end + 4);
            }
            if (buffer.length < frameLength) return;
            const body = buffer.subarray(0, frameLength); buffer = buffer.subarray(frameLength); frameLength = null;
            requireCheck(++messages <= LIMIT.messages, 'protocol message limit exceeded');
            const message = parseJson(decoder.decode(body));
            // Admit envelopes when their bytes arrive, before the async write queue advances protocol state.
            // A pre-sent id=2 cannot become valid merely by waiting behind the id=1 handler.
            requireCheck(object(message) && message.jsonrpc === '2.0', 'invalid JSON-RPC envelope');
            if (own(message, 'method')) {
              requireCheck(keys(message, ['jsonrpc', 'method', 'params']) && message.method === 'window/logMessage' &&
                keys(message.params, ['type', 'message']) && Number.isInteger(message.params.type) &&
                message.params.type >= 1 && message.params.type <= 4 && typeof message.params.message === 'string' &&
                Buffer.byteLength(message.params.message, 'utf8') <= LIMIT.tail, 'unexpected server message');
              requireCheck(message.params.type !== 1, 'server reported an error');
            } else {
              requireCheck(keys(message, ['jsonrpc', 'id', 'result']) && outstandingResponse !== null &&
                message.id === outstandingResponse, 'unsolicited or invalid response'); outstandingResponse = null;
            }
            inbox.push(message);
          }
        }
        function groupExists() {
          if (!child || !child.pid) return false;
          try { process.kill(-child.pid, 0); return true; }
          catch (error) { if (error.code === 'ESRCH') return false; throw new Error('cannot confirm owned group cleanup'); }
        }
        function signalGroup(signal) {
          if (!child || !child.pid) return;
          try { process.kill(-child.pid, signal); }
          catch (error) { if (error.code !== 'ESRCH') throw new Error('cannot signal owned group'); }
        }
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        async function waitGone(milliseconds) {
          const until = elapsed() + milliseconds;
          while (elapsed() < until && elapsed() < LIMIT.absolute - LIMIT.cleanup) {
            if ((!child || childClosed) && !groupExists()) return true; await delay(20);
          }
          return (!child || childClosed) && !groupExists();
        }
        function cleanupPrivate() {
          if (!privateRoot) return;
          const begin = elapsed(), identity = privateIdentity;
          requireCheck(identity !== null, 'private root ownership unknown');
          function budget() { requireCheck(elapsed() - begin < LIMIT.cleanup && elapsed() < LIMIT.absolute,
            'private cleanup timed out'); }
          budget(); const rootStat = fs.lstatSync(privateRoot, { bigint: true });
          requireCheck(rootStat.isDirectory() && !rootStat.isSymbolicLink() && inode(rootStat) === identity,
            'private root identity changed');
          const entries = []; let count = 0;
          function inspect(target, depth) {
            budget(); requireCheck(depth <= LIMIT.cleanupDepth && ++count <= LIMIT.cleanupEntries, 'private cleanup budget exceeded');
            const stat = fs.lstatSync(target, { bigint: true });
            requireCheck(stat.dev === rootStat.dev, 'private cleanup crossed device');
            if (privateDirectories.has(target)) requireCheck(stat.isDirectory() && !stat.isSymbolicLink() &&
              inode(stat) === privateDirectories.get(target), 'private directory identity changed');
            if (stat.isDirectory() && !stat.isSymbolicLink()) {
              const names = fs.readdirSync(target); requireCheck(names.length + count <= LIMIT.cleanupEntries, 'private cleanup budget exceeded');
              for (const name of names) inspect(target + '/' + name, depth + 1);
            } else requireCheck(stat.isFile() || stat.isSymbolicLink(), 'unsafe private cleanup entry');
            entries.push({ target, identity: inode(stat), directory: stat.isDirectory() && !stat.isSymbolicLink() });
          }
          // Inspect the complete bounded tree before deleting anything; never follow a child link.
          inspect(privateRoot, 0);
          for (const entry of entries) {
            budget(); requireCheck(inode(fs.lstatSync(privateRoot, { bigint: true })) === identity, 'private root identity changed');
            let ancestor = path.dirname(entry.target);
            while (ancestor.startsWith(privateRoot + '/') || ancestor === privateRoot) {
              const stat = fs.lstatSync(ancestor, { bigint: true });
              requireCheck(stat.isDirectory() && !stat.isSymbolicLink(), 'private cleanup parent changed');
              if (ancestor === privateRoot) break; ancestor = path.dirname(ancestor);
            }
            const stat = fs.lstatSync(entry.target, { bigint: true });
            requireCheck(inode(stat) === entry.identity && (stat.isDirectory() && !stat.isSymbolicLink()) === entry.directory,
              'private cleanup entry changed');
            entry.directory ? fs.rmdirSync(entry.target) : fs.unlinkSync(entry.target);
          }
          privateRoot = null;
        }
        async function finalize(success) {
          if (closing || finalized) return; closing = true; clearTimeout(phaseTimer);
          try {
            if (child) { child.stdin.destroy(); signalGroup('SIGTERM');
              if (!await waitGone(LIMIT.term)) { signalGroup('SIGKILL');
                requireCheck(await waitGone(LIMIT.reap), 'owned process cleanup not confirmed'); } }
          } catch (error) { if (!failure) failure = safeReason(error.message); }
          try { cleanupPrivate(); } catch (error) { if (!failure) failure = safeReason(error.message); }
          finalized = true; clearTimeout(absoluteTimer);
          if (success && !failure && (!child || childClosed)) {
            process.stdout.write('Droide Prisma zero-document initialize/shutdown OK\n'); process.exitCode = 0;
          } else {
            // Never echo server output, environment, URIs or arbitrary exception paths.
            process.stderr.write('Prisma health failed: ' + (failure || 'incomplete lifecycle') + '\n'); process.exitCode = 1;
          }
          if (child) { child.stdout.destroy(); child.stderr.destroy(); }
        }
        function maybeComplete() {
          if (closing || !childClosed || processing) return;
          if (childCode === 0 && childSignal === null && phase === 'exit' && exitFlushed && inputEnded &&
              frameLength === null && buffer.length === 0 && inbox.length === 0 && pendingWrites === 0) void finalize(true);
          else fail('original server closed before clean lifecycle completion');
        }
        for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => fail('health canceled'));
        absoluteTimer = setTimeout(() => {
          failure = 'absolute supervisor deadline exceeded';
          try { signalGroup('SIGKILL'); } catch (_) {}
          process.stderr.write('Prisma health failed: absolute supervisor deadline exceeded\n'); process.exit(1);
        }, LIMIT.absolute);
        (async () => {
          try {
            const context = preflight(); const env = makePrivateContext();
            requireCheck(elapsed() < LIMIT.startup, 'initialize timed out');
            for (const [filename, identity] of retained) {
              checkWork(); safeParents(filename);
              requireCheck(stamp(fs.lstatSync(filename, { bigint: true })) === identity, 'integration changed before spawn');
            }
            // No shell, target override, cwd override or inherited configuration reaches the child.
            child = spawn(context.authority.nodeCommand, [context.launcher, '--droide-prisma-health-child',
              __filename, context.authority.activationId, '--stdio'],
              { cwd: privateRoot + '/cwd', env, detached: true, stdio: ['pipe', 'pipe', 'pipe'] });
            child.on('error', () => fail('cannot start original server'));
            child.stdin.on('error', () => { if (!closing) fail('server input failed'); });
            child.stdin.on('close', () => { if (!closing && !exitFlushed) fail('server input closed early'); });
            child.stdout.on('data', chunk => { try { receive(chunk); void drainInbox(); } catch (error) { fail(error.message); } });
            child.stdout.on('error', () => fail('server output failed'));
            child.stderr.on('data', chunk => { try { countOutput(chunk); stderrTail = Buffer.concat([stderrTail, chunk]).subarray(-LIMIT.tail); }
              catch (error) { fail(error.message); } });
            child.stderr.on('error', () => fail('server error stream failed'));
            child.on('close', (code, signal) => { childClosed = true; childCode = code; childSignal = signal; maybeComplete(); });
            armPhase('initialize', LIMIT.startup - elapsed());
            await send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { processId: null,
              rootUri: pathToFileURL(privateRoot + '/cwd').href, workspaceFolders: null,
              capabilities: { general: { positionEncodings: ['utf-16'] }, workspace: { configuration: false,
                workspaceFolders: false }, textDocument: { synchronization: { dynamicRegistration: false } } },
              initializationOptions: {}, trace: 'off' } });
          } catch (error) {
            // Only fixed authored validation messages are displayed; native errors can include private paths.
            const reason = error && typeof error.message === 'string' && /^[A-Za-z][A-Za-z0-9 -]{0,95}${'$'}/.test(error.message)
              ? error.message : 'integration or private context validation failed'; fail(reason);
          }
        })();
    """.trimIndent() + "\n"

    val SOURCE_SHA256: String = MessageDigest.getInstance("SHA-256")
        .digest(script.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
