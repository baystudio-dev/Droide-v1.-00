package com.baystudio.droide.core

/** Exact ZLS-only exchange. The fixed health launcher owns compiler-free private state. */
internal object LocalUbuntuZlsLspHealth {
    const val FILE_NAME = "zls-lsp-health.cjs"
    const val TIMEOUT_MS = 40_000L

    /** Preserve the historical helper byte-for-byte; bind new installs to their selected release. */
    fun scriptForVersion(version: String): String {
        require(version.matches(Regex("[0-9]{1,6}[.][0-9]{1,6}[.][0-9]{1,6}")))
        return script.replace("'0.16.0'", "'$version'")
    }

    val script: String = run {
        fun replaceOnce(text: String, marker: String, replacement: String): String {
            require(text.indexOf(marker) >= 0 && text.indexOf(marker) == text.lastIndexOf(marker)) {
                "The reviewed ZLS health boundary changed"
            }
            return text.replace(marker, replacement)
        }
        val setup = """
            const path = require('node:path');
            const { createHash } = require('node:crypto');
            let zlsInitialized = false, zlsShutdown = false, zlsExitEnded = false, zlsStderrOverflow = false;
            function zlsPrepareCommand() {
              if (argv.length !== 6 || !path.isAbsolute(argv[0]) || argv[1] !== '-e' || argv[3] !== '--' ||
                  argv[4] !== 'health' || !path.isAbsolute(argv[5]) ||
                  [argv[0], argv[5]].some(value => /[\0\r\n]/.test(value)) ||
                  createHash('sha256').update(argv[2], 'utf8').digest('hex') !== '${LspZlsLaunchPolicy.scriptSha256}')
                throw new Error('invalid fixed ZLS health command');
            }
            function zlsLifecycleValid(code, signal) {
              return phase === 'exit' && zlsInitialized && zlsShutdown && zlsExitEnded && child.stdin.writableFinished &&
                signal === null && code === 0 && !zlsStderrOverflow && stderr.length === 0;
            }
        """.trimIndent()
        var result = LocalUbuntuNpmLspHealth.script
        result = replaceOnce(result, "const argv = process.argv.slice(2);", setup + "\nconst argv = process.argv.slice(2);")
        result = replaceOnce(result, "const reviewedLuaShutdown = argv[0] === '--droide-reviewed-luals-shutdown';", "const reviewedLuaShutdown = false;")
        result = replaceOnce(result, "const reviewedJdtShutdown = argv[0] === '--droide-reviewed-jdtls-shutdown';", "const reviewedJdtShutdown = false;")
        result = replaceOnce(result, "if (!own(message, 'id')) return;", """
            if (!own(message, 'id') && !own(message, 'result') && !own(message, 'error') &&
                message.method === 'window/showMessage' && object(message.params) &&
                Object.keys(message.params).sort().join(',') === 'message,type' && message.params.type === 2 &&
                ['zig executable could not be found', 'HELP ME, I AM STUCK INSIDE AN LSP!'].includes(message.params.message)) return;
            throw new Error('unexpected ZLS server notification or request');
        """.trimIndent())
        result = replaceOnce(result, "if (!object(message.result) || !object(message.result.capabilities)) throw new Error('initialize capabilities are missing');", """
            if (!object(message.result) || !object(message.result.capabilities)) throw new Error('initialize capabilities are missing');
            const caps = message.result.capabilities;
            if (!object(message.result.serverInfo) || message.result.serverInfo.name !== 'zls' || message.result.serverInfo.version !== '0.16.0' ||
                caps.positionEncoding !== 'utf-16' || !object(caps.textDocumentSync) || caps.textDocumentSync.openClose !== true ||
                caps.textDocumentSync.change !== 2 || caps.textDocumentSync.save !== true || caps.textDocumentSync.willSaveWaitUntil !== true ||
                caps.hoverProvider !== true || caps.definitionProvider !== true || caps.referencesProvider !== true ||
                caps.documentSymbolProvider !== true || caps.workspaceSymbolProvider !== true || caps.documentFormattingProvider !== true ||
                !object(caps.renameProvider) || caps.renameProvider.prepareProvider !== true || caps.inlayHintProvider !== true ||
                !object(caps.signatureHelpProvider) || JSON.stringify(caps.signatureHelpProvider.triggerCharacters) !== JSON.stringify(['(']) ||
                JSON.stringify(caps.signatureHelpProvider.retriggerCharacters) !== JSON.stringify([',']) ||
                !object(caps.completionProvider) || caps.completionProvider.resolveProvider !== false ||
                JSON.stringify(caps.completionProvider.triggerCharacters) !== JSON.stringify(['.', ':', '@', ']', '"', '/']) ||
                !object(caps.completionProvider.completionItem) || caps.completionProvider.completionItem.labelDetailsSupport !== true ||
                !object(caps.semanticTokensProvider) || caps.semanticTokensProvider.full !== true || caps.semanticTokensProvider.range !== true ||
                !object(caps.semanticTokensProvider.legend) ||
                JSON.stringify(caps.semanticTokensProvider.legend.tokenTypes) !== JSON.stringify([
                  'namespace', 'type', 'class', 'enum', 'interface', 'struct', 'typeParameter', 'parameter', 'variable', 'property',
                  'enumMember', 'event', 'function', 'method', 'macro', 'keyword', 'modifier', 'comment', 'string', 'escapeSequence',
                  'number', 'regexp', 'operator', 'decorator', 'errorTag', 'builtin', 'label', 'keywordLiteral']) ||
                JSON.stringify(caps.semanticTokensProvider.legend.tokenModifiers) !== JSON.stringify([
                  'declaration', 'definition', 'readonly', 'static', 'deprecated', 'abstract', 'async', 'modification',
                  'documentation', 'defaultLibrary', 'generic', 'mutable', '_']))
              throw new Error('unexpected ZLS identity or native capabilities');
            zlsInitialized = true;
        """.trimIndent())
        result = replaceOnce(result, "phase = 'exit'; expected = null; deadline(8000);", "zlsShutdown = true; phase = 'exit'; expected = null; deadline(8000);")
        result = replaceOnce(result, "child.stdin.end();", "child.stdin.end(() => { zlsExitEnded = true; });")
        // The inner launcher owns separately detached child groups. Give its bounded
        // TERM/KILL/reap and private cleanup time to finish before killing the owner.
        result = replaceOnce(result, "}, 200);", "}, child && child.exitCode === null && child.signalCode === null ? 5600 : 200);")
        result = replaceOnce(result, "child = spawn(argv[0], argv.slice(1), { stdio: ['pipe', 'pipe', 'pipe'], detached: true });", """
            zlsPrepareCommand();
            child = spawn(argv[0], argv.slice(1), { stdio: ['pipe', 'pipe', 'pipe'], detached: true });
        """.trimIndent())
        result = replaceOnce(result, "child.stderr.on('data', chunk => { stderr = Buffer.concat([stderr, chunk]).subarray(-65536); });", """
            child.stderr.on('data', chunk => {
              if (stderr.length + chunk.length > 65536) { zlsStderrOverflow = true; finish(false, 'ZLS stderr bound exceeded'); }
              else stderr = Buffer.concat([stderr, chunk]);
            });
        """.trimIndent())
        result = replaceOnce(result, "finish(phase === 'exit' && code === 0 && length === null && buffer.length === 0,", "finish(zlsLifecycleValid(code, signal) && length === null && buffer.length === 0,")
        result = replaceOnce(result, "processId: process.pid, rootUri: null, workspaceFolders: null,", """
            processId: null, rootUri: null, workspaceFolders: [],
            initializationOptions: { enable_build_on_save: false, prefer_ast_check_as_child_process: false, force_autofix: false },
        """.trimIndent())
        result = replaceOnce(result, "clientInfo: { name: 'Droide installer health', version: '1' }, trace: 'off',", "trace: 'off',")
        result
    }
}
