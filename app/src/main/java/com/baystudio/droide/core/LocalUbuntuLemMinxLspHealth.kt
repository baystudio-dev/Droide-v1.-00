package com.baystudio.droide.core

/** Zero-document direct Java lifecycle; a fake-peer pass does not certify the original runtime. */
internal object LocalUbuntuLemMinxLspHealth {
    const val FILE_NAME = "lemminx-lsp-health.cjs"
    const val TIMEOUT_MS = 60_000L
    val script: String = (LspLemMinxLaunchPolicy.commonSource + """
        const { TextDecoder } = require('node:util');
        const { pathToFileURL } = require('node:url');
        const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
        const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
        const decoder = new TextDecoder('utf-8', { fatal: true });
        let phase = 'initialize', expected = 1, phaseTimer = null, child;
        let buffer = Buffer.alloc(0), length = null, received = 0, messages = 0, stderr = Buffer.alloc(0);
        let initialized = false, shutdown = false, exitFlushed = false, protocolFailure = false;
        function fail(reason) {
          protocolFailure = true; clearTimeout(phaseTimer);
          if (stderr.length) { process.stderr.write(stderr); stderr = Buffer.alloc(0); }
          void finish(1, reason, true);
        }
        function deadline(ms) { clearTimeout(phaseTimer); phaseTimer = setTimeout(() => fail(phase + ' timed out'), ms); }
        function send(message) {
          if (finishing) return;
          const body = Buffer.from(JSON.stringify(message), 'utf8');
          child.stdin.write(Buffer.concat([Buffer.from('Content-Length: ' + body.length + '\r\n\r\n', 'ascii'), body]));
        }
        function capabilities(result) {
          if (!object(result) || !object(result.capabilities)) throw new Error('initialize capabilities are missing');
          const caps = result.capabilities;
          const exactArray = (a, b) => Array.isArray(a) && JSON.stringify(a) === JSON.stringify(b);
          if (caps.textDocumentSync !== 2 || (own(caps, 'positionEncoding') && caps.positionEncoding !== 'utf-16') ||
              !['documentSymbolProvider', 'documentHighlightProvider', 'documentFormattingProvider', 'documentRangeFormattingProvider',
                'hoverProvider', 'foldingRangeProvider', 'definitionProvider', 'typeDefinitionProvider', 'referencesProvider',
                'linkedEditingRangeProvider', 'colorProvider', 'selectionRangeProvider'].every(key => caps[key] === true) ||
              !object(caps.workspace) || !object(caps.workspace.workspaceFolders) ||
              caps.workspace.workspaceFolders.supported !== true || caps.workspace.workspaceFolders.changeNotifications !== true ||
              !object(caps.documentLinkProvider) || caps.documentLinkProvider.resolveProvider !== true ||
              !object(caps.completionProvider) || caps.completionProvider.resolveProvider !== true ||
              !exactArray(caps.completionProvider.triggerCharacters, ['.', ':', '<', '"', '=', '/', '\\', '?', "'", '&', '#']) ||
              !object(caps.codeLensProvider) || Object.keys(caps.codeLensProvider).length !== 0 ||
              !object(caps.codeActionProvider) || caps.codeActionProvider.resolveProvider !== true ||
              !exactArray(caps.codeActionProvider.codeActionKinds, ['quickfix', 'source']) ||
              !object(caps.renameProvider) || caps.renameProvider.prepareProvider !== true)
            throw new Error('unexpected LemMinX native capabilities');
        }
        function handle(message) {
          if (!object(message) || message.jsonrpc !== '2.0') throw new Error('invalid JSON-RPC envelope');
          if (++messages > 256) throw new Error('protocol message bound exceeded');
          if (own(message, 'method')) {
            if (message.method !== 'workspace/configuration' || !own(message, 'id') || own(message, 'result') || own(message, 'error') ||
                !(typeof message.id === 'string' || Number.isSafeInteger(message.id))) throw new Error('unexpected health server notification or request');
            const items = object(message.params) ? message.params.items : null;
            if (!Array.isArray(items) || items.length > 64 || !items.every(item => object(item) &&
                (!own(item, 'section') || typeof item.section === 'string') && (!own(item, 'scopeUri') || typeof item.scopeUri === 'string')))
              throw new Error('malformed or oversized configuration request');
            // Health has no open documents. Missing/foreign scopes never acquire defaults.
            send({ jsonrpc: '2.0', id: message.id, result: items.map(() => null) }); return;
          }
          if (!own(message, 'id') || message.id !== expected || own(message, 'error') || !own(message, 'result'))
            throw new Error('unexpected or failed ' + phase + ' response');
          if (phase === 'initialize') {
            capabilities(message.result); initialized = true;
            send({ jsonrpc: '2.0', method: 'initialized', params: {} });
            phase = 'shutdown'; expected = 2; deadline(3000);
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
          if (received > 8388608) throw new Error('protocol stdout bound exceeded');
          buffer = Buffer.concat([buffer, chunk]);
          while (!finishing) {
            if (length === null) {
              const end = buffer.indexOf('\r\n\r\n');
              if (end < 0) { if (buffer.length > 8192) throw new Error('protocol header bound exceeded'); return; }
              if (end > 8192) throw new Error('protocol header bound exceeded');
              const raw = buffer.subarray(0, end);
              if ([...raw].some(byte => byte > 127 || (byte < 32 && byte !== 9 && byte !== 10 && byte !== 13))) throw new Error('invalid protocol header');
              let declared = null;
              for (const header of raw.toString('ascii').split('\r\n')) {
                const match = /^([A-Za-z-]+):[ \t]*(.*)${'$'}/.exec(header);
                if (!match || /[\r\n]/.test(match[2])) throw new Error('invalid protocol header');
                if (match[1].toLowerCase() === 'content-length') {
                  if (declared !== null || !/^[0-9]+${'$'}/.test(match[2])) throw new Error('invalid Content-Length');
                  declared = Number(match[2]);
                }
              }
              if (!Number.isSafeInteger(declared) || declared < 1 || declared > 4194304) throw new Error('invalid Content-Length');
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
          if (argv.length !== 1) throw new Error('health requires exactly one original JAR target');
          target = argv[0]; preflight();
          const prepared = prepare(true), record = start(prepared, true); child = record.child;
          child.on('error', error => fail('cannot start fixed Java: ' + error.message));
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
            else void finish(0, null, true);
          });
          // Reserve 5.6 seconds for TERM/KILL, bounded group reaping and private cleanup.
          const remaining = 29400 - (performance.now() - started);
          if (remaining <= 0) throw new Error('health preparation deadline exceeded');
          workTimer = setTimeout(() => fail('overall health exchange deadline exceeded'), remaining);
          deadline(15000);
          const uri = pathToFileURL(prepared.cwd + '/').href;
          const initialize = {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":"file:///tmp/droide-lemminx-health-FIXTURE/work/","workspaceFolders":[{"uri":"file:///tmp/droide-lemminx-health-FIXTURE/work/","name":"empty-health"}],"capabilities":{"general":{"positionEncodings":["utf-16"]},"workspace":{"workspaceFolders":true,"configuration":true,"applyEdit":false,"didChangeWatchedFiles":{"dynamicRegistration":false},"executeCommand":{"dynamicRegistration":false}},"textDocument":{"synchronization":{"dynamicRegistration":false,"willSave":false,"willSaveWaitUntil":false,"didSave":true},"completion":{"dynamicRegistration":false,"completionItem":{"snippetSupport":true,"documentationFormat":["markdown","plaintext"]}},"hover":{"dynamicRegistration":false,"contentFormat":["markdown","plaintext"]},"definition":{"dynamicRegistration":false},"references":{"dynamicRegistration":false},"documentSymbol":{"dynamicRegistration":false,"hierarchicalDocumentSymbolSupport":true},"rename":{"dynamicRegistration":false,"prepareSupport":false},"formatting":{"dynamicRegistration":false},"codeAction":{"dynamicRegistration":false,"codeActionLiteralSupport":{"codeActionKind":{"valueSet":["quickfix","source"]}},"resolveSupport":{"properties":["edit"]}},"publishDiagnostics":{"relatedInformation":true,"versionSupport":true}}},"initializationOptions":{"settings":{"xml":{"useCache":true,"downloadExternalResources":{"enabled":false},"validation":{"enabled":true,"schema":{"enabled":"always"},"resolveExternalEntities":false,"disallowDocTypeDecl":false,"xInclude":{"enabled":false},"noGrammar":"hint","filters":[]},"catalogs":[],"fileAssociations":[],"format":{"enabled":true,"insertSpaces":true,"tabSize":2},"completion":{"autoCloseTags":true},"telemetry":{"enabled":false},"logs":{"client":false},"codeLens":{"enabled":false}}},"extendedClientCapabilities":{"actionableNotificationSupport":false,"openSettingsCommandSupport":false,"bindingWizardSupport":false,"shouldLanguageServerExitOnShutdown":false}},"trace":"off"}};
          initialize.params.rootUri = uri;
          initialize.params.workspaceFolders = [{ uri, name: 'empty-health' }];
          send(initialize);
        } catch (error) { fail(error.message); }
    """.trimIndent() + "\n").also {
        require(it.toByteArray(Charsets.UTF_8).size in 1..65_536) { "LemMinX health helper exceeds integration file bounds" }
    }
}
