package com.baystudio.droide.core

import java.security.MessageDigest

/** Strict zero-document Svelte health. Runtime consistency is not a sandbox or ownership signature. */
internal object LocalUbuntuSvelteLspHealth {
    const val FILE_NAME = "svelte-lsp-health.cjs"
    const val TIMEOUT_MS = 45_000L
    const val REVISION = "svelte-zero-document-private-health-v1"
    const val CONTRACT_SHA256 = "3a7e3a1a171bdd4a31abec2b505fa51e075ecf84bcdc9be208676b593928c516"

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
        const HEALTH_REVISION = 'svelte-zero-document-private-health-v1';
        const HEALTH_CONTRACT = '3a7e3a1a171bdd4a31abec2b505fa51e075ecf84bcdc9be208676b593928c516';
        const HELPER_RELATIVE = 'payload/svelte-lsp-health.cjs';
        const LAUNCHER_RELATIVE = 'payload/npm/droide-svelte-launch.cjs';
        const HEALTH = {"revision":"svelte-zero-document-private-health-design-v1","entrypoint":"payload/npm/node_modules/svelte-language-server/bin/server.js","argv":["<verified-absolute-node>","<verified-absolute-public-bin>","--stdio"],"pre_node_isolation":true,"private_directories":["workspace","home","tmp","config","cache","data"],"documents_sent":0,"rootUri":"<private-workspace-file-uri>","workspaceFolders":[{"name":"health","uri":"<private-workspace-file-uri>"}],"initializationOptions":{"isTrusted":false,"configuration":{"svelte":{"plugin":{"typescript":{"enable":false},"svelte":{"enable":false,"diagnostics":{"enable":false},"format":{"enable":false}},"html":{"enable":false},"css":{"enable":false}},"language-server":{"debug":false}},"typescript":{"preferences":{"includePackageJsonAutoImports":"off"}},"javascript":{"preferences":{"includePackageJsonAutoImports":"off"}}}},"client_capabilities":{"general":{"positionEncodings":["utf-16"]},"workspace":{"workspaceFolders":true,"configuration":false,"applyEdit":false,"didChangeWatchedFiles":{"dynamicRegistration":false,"relativePatternSupport":false}},"textDocument":{"synchronization":{"dynamicRegistration":false}}},"protocol":["initialize id=1 with exact private parameters","validate source-bound essential capability shape; reject errors or alien encoding","initialized notification","shutdown id=2; require result null","exit notification; close stdin","require clean child code=0, EOF, no partial/trailing frames, owned cleanup success"],"capabilities_required":{"textDocumentSync":{"openClose":true,"change":2,"save":{"includeText":false}},"hoverProvider":true,"completionProvider":{"resolveProvider":true},"documentFormattingProvider":true,"diagnosticProvider":{"interFileDependencies":true,"workspaceDiagnostics":false}},"capabilities_forbidden":["executeCommandProvider when applyEdit=false"],"server_requests":"Reject all with bounded -32601 response and mark health failed; no configuration, registration or applyEdit was advertised. Bounded window/logMessage and ${'$'}/logTrace notifications are informational only; reject document-scoped diagnostics in zero-document profile.","limits":{"host_timeout_ms":45000,"absolute_supervisor_ms":37000,"work_ms":30000,"startup_ms":20000,"shutdown_ms":5000,"exit_ms":5000,"term_ms":2000,"reap_ms":3000,"cleanup_ms":2000,"write_drain_ms":2000,"header_bytes":8192,"frame_bytes":1048576,"aggregate_stdout_bytes":4194304,"aggregate_stderr_bytes":1048576,"stderr_retained_bytes":65536,"messages":256,"json_depth":64,"json_values":50000,"single_write_bytes":16384,"pending_write_bytes":65536,"cleanup_entries":4096,"cleanup_depth":64},"required_false_failure_flags":["timedOut","outputIncomplete","cleanupIncomplete","cancelled","protocolFailed","identityChanged"],"success_scope":"Exact managed import/stdio initialization, shutdown and owned cleanup only; neither semantic acceptance nor project-analysis consent."};
        const flags = { timedOut:false, outputIncomplete:false, cleanupIncomplete:false, cancelled:false, protocolFailed:false, identityChanged:false };
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
          requireCheck(process.execArgv.length === 0 && process.platform === 'linux' && process.arch === 'arm64', 'unexpected runtime');
          requireCheck(safeAbsolute(__filename) && process.argv[1] === __filename && __filename.endsWith('/' + HELPER_RELATIVE), 'unexpected helper');
          const root = __filename.slice(0, -('/' + HELPER_RELATIVE).length);
          const authority = parseJson(decoder.decode(readRegular(root + '/SVELTE_LAUNCH_AUTHORITY.json', 64000)));
          requireCheck(authority.healthSourceSha256 === hash(readRegular(__filename, 1000000)) &&
            authority.launcherSha256 === hash(readRegular(root + '/' + LAUNCHER_RELATIVE, 1000000)), 'authored helper changed');
          // This exact app-authored guard uses builtins only. The server is never imported in this process.
          return require(root + '/' + LAUNCHER_RELATIVE).inspect(true);
        }
        let child = null, childClosed = false, childCode = null, childSignal = null;
        let finalized = false, closing = false, failure = null, privateRoot = null, privateIdentity = null;
        const privateDirectories = new Map();
        let phase = 'initialize', phaseTimer = null, absoluteTimer = null;
        let buffer = Buffer.alloc(0), frameLength = null, received = 0, messages = 0, stderrTail = Buffer.alloc(0);
        let stderrBytes = 0;
        let pendingWrites = 0, writeChain = Promise.resolve(), inbox = [], processing = false;
        let exitFlushed = false, inputEnded = false, outstandingResponse = null;
        function safeReason(reason) {
          return typeof reason === 'string' && /^[A-Za-z][A-Za-z0-9 -]{0,95}${'$'}/.test(reason)
            ? reason : 'integration or private context validation failed';
        }
        function fail(reason) { if (!failure) failure = safeReason(reason); if (/timed out|deadline/.test(reason)) flags.timedOut=true; else flags.protocolFailed=true; void finalize(false); }
        function armPhase(name, milliseconds) {
          phase = name; clearTimeout(phaseTimer);
          const remaining = Math.min(milliseconds, LIMIT.work - elapsed());
          if (remaining <= 0) { fail('absolute work deadline exceeded'); return; }
          phaseTimer = setTimeout(() => fail(name + ' timed out'), remaining);
        }
        function makePrivateContext() {
          requireCheck(process.argv.length === 4 && /^\/tmp\/droide-svelte-health-[A-Za-z0-9]{12}${'$'}/.test(process.argv[2]) &&
            /^[0-9]+:[0-9]+${'$'}/.test(process.argv[3]), 'invalid private handoff');
          privateRoot = process.argv[2]; privateIdentity = process.argv[3];
          safeParents(privateRoot + '/workspace');
          const rootStat = fs.lstatSync(privateRoot, {bigint:true});
          requireCheck(rootStat.isDirectory() && !rootStat.isSymbolicLink() && inode(rootStat) === privateIdentity &&
            (rootStat.mode & 0o777n) === 0o700n, 'private root identity changed');
          requireCheck(same(fs.readdirSync(privateRoot).sort(), ['workspace','home','tmp','config','cache','data'].sort()), 'private directory shape changed');
          for (const name of ['workspace','home','tmp','config','cache','data']) {
            const target = privateRoot + '/' + name, stat = fs.lstatSync(target, {bigint:true});
            requireCheck(stat.isDirectory() && !stat.isSymbolicLink() && stat.dev === rootStat.dev &&
              (stat.mode & 0o777n) === 0o700n && fs.readdirSync(target).length === 0, 'private directory is not empty');
            privateDirectories.set(target, inode(stat));
          }
          const env = { HOME:privateRoot+'/home', TMPDIR:privateRoot+'/tmp', XDG_CONFIG_HOME:privateRoot+'/config',
            XDG_CACHE_HOME:privateRoot+'/cache', XDG_DATA_HOME:privateRoot+'/data', PATH:'', LANG:'C.UTF-8', LC_ALL:'C.UTF-8', TERM:'dumb',
            DROIDE_PROCESS_LEASE:process.env.DROIDE_PROCESS_LEASE };
          requireCheck(/^[0-9a-f]{20}${'$'}/.test(env.DROIDE_PROCESS_LEASE) && same(process.env, env) &&
            process.cwd() === privateRoot + '/workspace', 'private pre Node environment changed');
          return env;
        }
        function countOutput(chunk) { received += chunk.length; if(received>LIMIT.output)flags.outputIncomplete=true; requireCheck(received <= LIMIT.output, 'stdout budget exceeded'); }
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
              if (own(message, 'id') && own(message, 'method')) {
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
        function validateNotification(message) {
          requireCheck(keys(message,['jsonrpc','method','params']), 'invalid notification envelope');
          if (message.method === 'window/logMessage') {
            requireCheck(keys(message.params,['type','message']) && Number.isInteger(message.params.type) && message.params.type>=1 && message.params.type<=4 &&
              typeof message.params.message==='string' && Buffer.byteLength(message.params.message)<=LIMIT.tail, 'invalid log notification');
            requireCheck(message.params.type!==1, 'server reported an error');
          } else {
            requireCheck(message.method==='${'$'}/logTrace' && object(message.params) &&
              Object.keys(message.params).every(k=>['message','verbose'].includes(k)) && typeof message.params.message==='string' &&
              (message.params.verbose===undefined||typeof message.params.verbose==='string') && Buffer.byteLength(JSON.stringify(message.params))<=LIMIT.tail, 'unexpected notification');
          }
        }
        function validateCapabilities(c) {
          requireCheck(same(c.textDocumentSync,HEALTH.capabilities_required.textDocumentSync) &&
            (c.positionEncoding===undefined||c.positionEncoding==='utf-16') && c.hoverProvider===true && c.documentFormattingProvider===true &&
            object(c.completionProvider) && c.completionProvider.resolveProvider===true &&
            same(c.diagnosticProvider,HEALTH.capabilities_required.diagnosticProvider) && !own(c,'executeCommandProvider'), 'initialize capabilities changed');
        }
        async function handle(message) {
          requireCheck(object(message) && message.jsonrpc === '2.0', 'invalid JSON-RPC envelope');
          requireCheck(phase !== 'exit' && phase !== 'exit-write', 'unexpected protocol output after shutdown');
          if (own(message, 'method')) {
            if (own(message, 'id')) {
              await send({jsonrpc:'2.0',id:message.id,error:{code:-32601,message:'Method not supported'}});
              throw new Error('unexpected server request');
            }
            validateNotification(message); return;
          }
          requireCheck(keys(message, ['jsonrpc', 'id', 'result']), 'invalid response shape');
          if (phase === 'initialize') {
            requireCheck(message.id === 1 && object(message.result) && object(message.result.capabilities), 'invalid initialize result');
            validateCapabilities(message.result.capabilities);
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
              if (own(message, 'id')) {
                requireCheck(keys(message, ['jsonrpc','id','method','params']) &&
                  (typeof message.id === 'string' && Buffer.byteLength(message.id)<=128 || Number.isSafeInteger(message.id)) &&
                  typeof message.method==='string' && Buffer.byteLength(message.method)<=256, 'malformed server request');
              } else validateNotification(message);
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
          requireCheck(!childClosed && child.exitCode === null && child.signalCode === null, 'owned group leader already reaped');
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
            if (child) { child.stdin.destroy(); if (!childClosed && child.exitCode === null && child.signalCode === null) signalGroup('SIGTERM');
              if (!await waitGone(LIMIT.term)) { signalGroup('SIGKILL');
                requireCheck(await waitGone(LIMIT.reap), 'owned process cleanup not confirmed'); } }
          } catch (error) { flags.cleanupIncomplete=true; if (!failure) failure = safeReason(error.message); }
          try { cleanupPrivate(); } catch (error) { flags.cleanupIncomplete=true; if (!failure) failure = safeReason(error.message); }
          finalized = true; clearTimeout(absoluteTimer);
          if (success && !failure && (!child || childClosed)) {
            process.stdout.write(JSON.stringify({schema:1,revision:HEALTH_REVISION,...flags})+'\n'); process.exitCode = 0;
          } else {
            // Never echo server output, environment, URIs or arbitrary exception paths.
            process.stderr.write('Svelte health failed: ' + (failure || 'incomplete lifecycle') + '\n'); process.exitCode = 1;
          }
          if (child) { child.stdout.destroy(); child.stderr.destroy(); }
        }
        function maybeComplete() {
          if (closing || !childClosed || processing) return;
          if (childCode === 0 && childSignal === null && phase === 'exit' && exitFlushed && inputEnded &&
              frameLength === null && buffer.length === 0 && inbox.length === 0 && pendingWrites === 0) void finalize(true);
          else fail('original server closed before clean lifecycle completion');
        }
        for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => { flags.cancelled=true; fail('health canceled'); });
        absoluteTimer = setTimeout(() => {
          failure = 'absolute supervisor deadline exceeded';
          try { signalGroup('SIGKILL'); } catch (_) {}
          process.stderr.write('Svelte health failed: absolute supervisor deadline exceeded\n'); process.exit(1);
        }, LIMIT.absolute);
        (async () => {
          try {
            const env = makePrivateContext(); let context;
            try { context = preflight(); } catch (error) { flags.identityChanged=true; throw error; }
            requireCheck(elapsed() < LIMIT.startup, 'initialize timed out');
            for (const [filename, identity] of retained) {
              checkWork(); safeParents(filename);
              requireCheck(stamp(fs.lstatSync(filename, { bigint: true })) === identity, 'integration changed before spawn');
            }
            // No shell, target override, cwd override or inherited configuration reaches the child.
            child = spawn(context.authority.nodeCommand, [context.bin, '--stdio'],
              { cwd: privateRoot + '/workspace', env, detached: true, stdio: ['pipe', 'pipe', 'pipe'] });
            child.on('error', () => fail('cannot start original server'));
            child.stdin.on('error', () => { if (!closing) fail('server input failed'); });
            child.stdin.on('close', () => { if (!closing && !exitFlushed) fail('server input closed early'); });
            child.stdout.on('data', chunk => { try { receive(chunk); void drainInbox(); } catch (error) { fail(error.message); } });
            child.stdout.on('error', () => fail('server output failed'));
            child.stderr.on('data', chunk => { try { stderrBytes+=chunk.length; if(stderrBytes>1048576)flags.outputIncomplete=true; requireCheck(stderrBytes<=1048576,'stderr budget exceeded'); stderrTail = Buffer.concat([stderrTail, chunk]).subarray(-LIMIT.tail); }
              catch (error) { fail(error.message); } });
            child.stderr.on('error', () => fail('server error stream failed'));
            child.on('close', (code, signal) => { childClosed = true; childCode = code; childSignal = signal; maybeComplete(); });
            armPhase('initialize', LIMIT.startup - elapsed());
            const uri = pathToFileURL(privateRoot + '/workspace').href;
            await send({jsonrpc:'2.0',id:1,method:'initialize',params:{processId:null,rootUri:uri,
              workspaceFolders:[{uri,name:'health'}],capabilities:HEALTH.client_capabilities,
              initializationOptions:HEALTH.initializationOptions,trace:'off'}});
          } catch (error) {
            // Only fixed authored validation messages are displayed; native errors can include private paths.
            const reason = error && typeof error.message === 'string' && /^[A-Za-z][A-Za-z0-9 -]{0,95}${'$'}/.test(error.message)
              ? error.message : 'integration or private context validation failed'; fail(reason);
          }
        })();
    """.trimIndent() + "\n"

    fun succeeded(result: ExecResult): Boolean = runCatching {
        require(result.exitCode == 0 && !result.timedOut && !result.outputIncomplete)
        val value = LocalUbuntuSveltePolicy.strictObject(result.output.trim(), 4096)
        require(value.keys == setOf("schema", "revision", "timedOut", "outputIncomplete", "cleanupIncomplete", "cancelled", "protocolFailed", "identityChanged"))
        require(value["schema"] == kotlinx.serialization.json.JsonPrimitive(1) && value["revision"] == kotlinx.serialization.json.JsonPrimitive(REVISION))
        listOf("timedOut", "outputIncomplete", "cleanupIncomplete", "cancelled", "protocolFailed", "identityChanged").all { value[it] == kotlinx.serialization.json.JsonPrimitive(false) }
    }.getOrDefault(false)

    val SOURCE_SHA256: String = MessageDigest.getInstance("SHA-256")
        .digest(script.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
