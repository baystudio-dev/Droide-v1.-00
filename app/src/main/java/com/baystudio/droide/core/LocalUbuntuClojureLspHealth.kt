package com.baystudio.droide.core

internal object LocalUbuntuClojureLspHealth {
    const val FILE_NAME = "clojure-lsp-health.cjs"
    const val TIMEOUT_MS = 60_000L
    val script = (LspClojureLaunchPolicy.commonSource + """
        const { TextDecoder } = require('node:util');
        const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
        const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
        const decoder = new TextDecoder('utf-8', { fatal: true });
        // Reject duplicate object keys and excessive nesting before semantic exact-shape comparison.
        function strictJson(text) {
          let offset = 0, values = 0;
          const space = () => { while (offset < text.length && /[ \t\r\n]/.test(text[offset])) ++offset; };
          function string() {
            const begin = offset++;
            while (offset < text.length) {
              const ch = text[offset++];
              if (ch === '"') return JSON.parse(text.slice(begin, offset));
              if (ch === '\\') ++offset;
            }
            throw new Error('incomplete JSON string');
          }
          function value(depth) {
            if (depth > 64 || ++values > 50000) throw new Error('JSON structure bound exceeded');
            space(); const ch = text[offset];
            if (ch === '"') return string();
            if (ch === '{') {
              ++offset; const result = Object.create(null); space(); if (text[offset] === '}') { ++offset; return result; }
              for (;;) {
                space(); if (text[offset] !== '"') throw new Error('invalid JSON key');
                const key = string(); if (own(result,key)) throw new Error('duplicate JSON key');
                space(); if (text[offset++] !== ':') throw new Error('invalid JSON object'); result[key] = value(depth + 1);
                space(); const next = text[offset++]; if (next === '}') return result; if (next !== ',') throw new Error('invalid JSON object');
              }
            }
            if (ch === '[') {
              ++offset; const result = []; space(); if (text[offset] === ']') { ++offset; return result; }
              for (;;) { result.push(value(depth + 1)); space(); const next = text[offset++]; if (next === ']') return result; if (next !== ',') throw new Error('invalid JSON array'); }
            }
            const match = /^(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(text.slice(offset));
            if (!match) throw new Error('invalid JSON scalar'); offset += match[0].length; const result = JSON.parse(match[0]);
            if (typeof result === 'number' && !Number.isFinite(result)) throw new Error('nonfinite JSON number'); return result;
          }
          const result = value(0); space(); if (offset !== text.length) throw new Error('trailing JSON data'); return result;
        }
        let phase = 'initialize', expected = 1, phaseTimer = null, child;
        let buffer = Buffer.alloc(0), length = null, received = 0, messages = 0, stderr = Buffer.alloc(0);
        let warning = false, initialized = false, shutdown = false, exitFlushed = false, protocolFailure = false;
        function fail(reason) {
          protocolFailure = true; clearTimeout(phaseTimer);
          // Native stderr is rejected; never echo private log contents.
          void finish(1, reason);
        }
        function deadline(ms) { clearTimeout(phaseTimer); const left = 34400 - (performance.now() - started); if (left <= 0) throw new Error('health work deadline exceeded'); phaseTimer = setTimeout(() => fail(phase + ' timed out'), Math.min(ms,left)); }
        function send(message) {
          if (finishing) return;
          const body = Buffer.from(JSON.stringify(message), 'utf8');
          child.stdin.write(Buffer.concat([Buffer.from('Content-Length: ' + body.length + '\r\n\r\n', 'ascii'), body]));
        }
        const expectedResult = {"capabilities":{"documentHighlightProvider":true,"hoverProvider":true,"declarationProvider":true,"implementationProvider":true,"callHierarchyProvider":true,"linkedEditingRangeProvider":true,"referencesProvider":true,"definitionProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"foldingRangeProvider":true,"selectionRangeProvider":true,"signatureHelpProvider":{"triggerCharacters":[]},"codeActionProvider":{"codeActionKinds":["quickfix","refactor","refactor.extract","refactor.inline","refactor.rewrite","source","source.organizeImports"]},"codeLensProvider":{"resolveProvider":true},"renameProvider":{"prepareProvider":true},"documentFormattingProvider":null,"documentRangeFormattingProvider":null,"workspace":{"fileOperations":{"willRename":{"filters":[{"scheme":"file","pattern":{"glob":"**/*.{clj,cljs,cljc,cljd,edn,bb,clj_kondo}","matches":"file"}}]},"didRename":{"filters":[{"scheme":"file","pattern":{"glob":"**/*.{clj,cljs,cljc,cljd,edn,bb,clj_kondo}","matches":"file"}}]}}},"semanticTokensProvider":{"legend":{"tokenTypes":["namespace","type","function","macro","keyword","class","variable","method","event","interface","comment"],"tokenModifiers":["definition","defaultLibrary","implementation"]},"range":true,"full":true},"executeCommandProvider":{"commands":["add-import-to-namespace","add-missing-import","add-missing-libspec","add-require-suggestion","as-to-refer","backward","backward-barf","backward-select","backward-slurp","change-coll","clean-ns","cond->if-refactor","create-function","create-test","cursor-info","cycle-coll","cycle-keyword-auto-resolve","cycle-namespaced-map","cycle-privacy","demote-fn","destructure-keys","drag-backward","drag-forward","drag-param-backward","drag-param-forward","expand-let","extract-function","extract-to-def","forward","forward-barf","forward-select","forward-slurp","get-in-all","get-in-less","get-in-more","get-in-none","if->cond-refactor","inline-function","inline-symbol","introduce-let","kill-sexp","move-coll-entry-down","move-coll-entry-up","move-form","move-to-for-let","move-to-let","promote-fn","raise-sexp","refer-to-as","replace-refer-all-with-alias","replace-refer-all-with-refer","resolve-macro-as","restructure-keys","server-info","sort-clauses","sort-map","suppress-diagnostic","swap-namespace-with-alias","thread-first","thread-first-all","thread-last","thread-last-all","unwind-all","unwind-thread"]},"textDocumentSync":{"openClose":true,"change":1,"save":{"includeText":true}},"completionProvider":{"resolveProvider":true,"triggerCharacters":[":","/"]},"experimental":{"testTree":true,"projectTree":true,"cursorInfo":true,"serverInfo":true,"clojuredocs":true}}};
        const expectedWarning = {"jsonrpc":"2.0","method":"window/showMessage","params":{"type":2,"message":"No project-root-uri was specified, some features will be limited.","extra":null}};
        function exact(a, b) {
          if (a === b) return true;
          if (Array.isArray(a) || Array.isArray(b)) return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, index) => exact(value, b[index]));
          if (!object(a) || !object(b)) return false;
          const keys = Object.keys(a);
          return keys.length === Object.keys(b).length && keys.every(key => own(b, key) && exact(a[key], b[key]));
        }
        function capabilities(result) {
          if (!object(result) || !object(result.capabilities) || !object(result.capabilities.codeActionProvider)) throw new Error('missing native capabilities');
          const kinds = result.capabilities.codeActionProvider.codeActionKinds;
          if (!Array.isArray(kinds) || kinds.some(x => typeof x !== 'string') || new Set(kinds).size !== kinds.length) throw new Error('invalid code action kinds');
          const ordered = JSON.parse(JSON.stringify(result)); ordered.capabilities.codeActionProvider.codeActionKinds = [...kinds].sort();
          const expectedOrdered = JSON.parse(JSON.stringify(expectedResult)); expectedOrdered.capabilities.codeActionProvider.codeActionKinds.sort();
          if (!exact(ordered, expectedOrdered)) throw new Error('unexpected exact rootless native capabilities');
        }
        function handle(message) {
          if (!object(message) || message.jsonrpc !== '2.0' || ++messages > 3) throw new Error('invalid or excessive JSON-RPC messages');
          if (own(message, 'method')) {
            if (phase !== 'initialize' || warning || !exact(message, expectedWarning)) throw new Error('unexpected server method or warning');
            warning = true; return;
          }
          if (Object.keys(message).length !== 3 || !own(message, 'jsonrpc') || !own(message, 'id') || !own(message, 'result') ||
              message.id !== expected || typeof message.id !== 'number') throw new Error('unexpected server response envelope');
          if (phase === 'initialize') {
            if (!warning) throw new Error('required rootless warning missing');
            capabilities(message.result); initialized = true;
            send({ jsonrpc:'2.0', method:'initialized', params:{} }); phase = 'shutdown'; expected = 2; deadline(5000);
            send({ jsonrpc:'2.0', id:2, method:'shutdown' });
          } else if (phase === 'shutdown') {
            if (message.result !== null) throw new Error('shutdown result must be explicit null');
            shutdown = true; phase = 'exit'; expected = null; deadline(12000);
            send({ jsonrpc:'2.0', method:'exit' }); child.stdin.end(() => { exitFlushed = true; });
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
              const match = /^Content-Length: ([1-9][0-9]*)${'$'}/.exec(raw.toString('ascii'));
              const declared = match ? Number(match[1]) : NaN;
              if (!Number.isSafeInteger(declared) || declared < 1 || declared > 1048576) throw new Error('invalid Content-Length');
              length = declared; buffer = buffer.subarray(end + 4);
            }
            if (buffer.length < length) return;
            const body = buffer.subarray(0, length); buffer = buffer.subarray(length); length = null;
            handle(strictJson(decoder.decode(body)));
          }
        }
        for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.on(signal, () => fail('health canceled'));
        try {
          const argv = process.argv.slice(2);
          if (argv.length !== 1) throw new Error('health requires exactly one admitted native target');
          healthMode = true; target = argv[0]; preflight();
          const prepared = prepare(), record = start(prepared); child = record.child;
          child.on('error', error => fail('cannot start admitted native: ' + error.message));
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
            const valid = !result.error && !protocolFailure && phase === 'exit' && warning && messages === 3 && initialized && shutdown && exitFlushed &&
              child.stdin.writableFinished && result.code === 0 && result.signal === null && length === null && buffer.length === 0 && stderr.length === 0;
            if (!valid) fail('server did not complete clean initialize/shutdown/exit');
            else void finish(0, null);
          });
          // Reserve 5.6 seconds for TERM/KILL, bounded group reaping and private cleanup.
          const remaining = 34400 - (performance.now() - started);
          if (remaining <= 0) throw new Error('health preparation deadline exceeded');
          workTimer = setTimeout(() => fail('overall health exchange deadline exceeded'), remaining);
          deadline(15000);
          logTimer = setInterval(() => { try { checkLog(); } catch (error) { fail(error.message); } }, 100);
          checkTarget();
          const initialize = {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"workspaceFolders":[],"capabilities":{"general":{"positionEncodings":["utf-16"]}},"initializationOptions":{}}};
          send(initialize);
        } catch (error) { fail(error.message); }
    """.trimIndent() + "\n").also { require(it.toByteArray(Charsets.UTF_8).size in 1..65_536) }
}
