package com.baystudio.droide.core

/** A sealed, bounded probe of the original npm language server's stdio lifecycle. */
internal object LocalUbuntuNpmLspHealth {
    const val FILE_NAME = "npm-lsp-health.cjs"
    const val TIMEOUT_MS = 30_000L

    val script: String = """
        'use strict';
        const { spawn } = require('node:child_process');
        const { TextDecoder } = require('node:util');
        const argv = process.argv.slice(2);
        const reviewedLuaShutdown = argv[0] === '--droide-reviewed-luals-shutdown';
        const reviewedJdtShutdown = argv[0] === '--droide-reviewed-jdtls-shutdown';
        if (reviewedLuaShutdown || reviewedJdtShutdown) argv.shift();
        if (!argv.length) { console.error('LSP health: missing original command'); process.exit(1); }
        const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
        const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
        let child, timer, phase = 'initialize', finished = false, expected = 1;
        let buffer = Buffer.alloc(0), length = null, received = 0, messages = 0, stderr = Buffer.alloc(0);
        const decoder = new TextDecoder('utf-8', { fatal: true });
        function kill(signal) {
          if (!child || !child.pid) return;
          try { process.kill(-child.pid, signal); }
          catch (_) { try { child.kill(signal); } catch (_) {} }
        }
        function finish(success, reason) {
          if (finished) return;
          finished = true;
          clearTimeout(timer);
          if (!success) {
            console.error('LSP health: ' + reason);
            if (stderr.length) process.stderr.write(stderr);
          }
          if (child) { child.stdin.destroy(); kill('SIGTERM'); }
          // Language servers may launch worker processes. Reap their process group even
          // when the original server has already exited after the protocol handshake.
          setTimeout(() => {
            kill('SIGKILL');
            const complete = () => {
              if (success) console.log('Droide LSP initialize/shutdown OK');
              process.exit(success ? 0 : 1);
            };
            // SIGKILL delivery is asynchronous. Reap the original child before the
            // health client exits, so callers cannot observe a surviving server.
            if (!child || child.exitCode !== null || child.signalCode !== null) complete();
            else {
              const cleanupTimer = setTimeout(() => {
                console.error('LSP health: original server cleanup timed out');
                process.exit(1);
              }, 3000);
              child.once('close', () => { clearTimeout(cleanupTimer); complete(); });
            }
          }, 200);
        }
        function deadline(milliseconds) {
          clearTimeout(timer);
          timer = setTimeout(() => finish(false, phase + ' timed out'), milliseconds);
        }
        function send(message) {
          if (finished) return;
          const body = Buffer.from(JSON.stringify(message), 'utf8');
          child.stdin.write(Buffer.concat([Buffer.from('Content-Length: ' + body.length + '\r\n\r\n', 'ascii'), body]));
        }
        function request(id, method, params) {
          const message = { jsonrpc: '2.0', id, method };
          if (params !== undefined) message.params = params;
          send(message);
        }
        function notification(method, params) {
          const message = { jsonrpc: '2.0', method };
          if (params !== undefined) message.params = params;
          send(message);
        }
        function handle(message) {
          if (!object(message) || message.jsonrpc !== '2.0') throw new Error('invalid JSON-RPC envelope');
          if (++messages > 256) throw new Error('protocol message limit exceeded');
          if (own(message, 'method')) {
            if (typeof message.method !== 'string' || !message.method.length) throw new Error('invalid protocol method');
            if (!own(message, 'id')) return;
            if (typeof message.id !== 'string' && typeof message.id !== 'number') throw new Error('invalid server request identifier');
            if (message.method === 'workspace/configuration') {
              const items = message.params && message.params.items;
              if (!Array.isArray(items) || items.length > 128) throw new Error('invalid configuration request');
              send({ jsonrpc: '2.0', id: message.id, result: items.map(() => null) });
            } else if (message.method === 'client/registerCapability' || message.method === 'client/unregisterCapability') {
              send({ jsonrpc: '2.0', id: message.id, result: null });
            } else {
              send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not supported by installer health client' } });
            }
            return;
          }
          if (!own(message, 'id') || message.id !== expected) throw new Error('unexpected response identifier');
          if (own(message, 'error') || !own(message, 'result')) throw new Error(phase + ' request failed');
          if (phase === 'initialize') {
            if (!object(message.result) || !object(message.result.capabilities)) throw new Error('initialize capabilities are missing');
            notification('initialized', {});
            phase = 'shutdown'; expected = 2; deadline(3000);
            request(2, 'shutdown');
          } else if (phase === 'shutdown') {
            // Original LuaLS returns true; original JDTLS returns an empty object.
            // Each exception is selected only by that reviewed vendor integration.
            if (message.result !== null && !(reviewedLuaShutdown && message.result === true) &&
                !(reviewedJdtShutdown && object(message.result) && Object.keys(message.result).length === 0))
              throw new Error('invalid shutdown response');
            // The original YAML server needs about five seconds to finish under
            // PRoot. Still require a clean exit, with a bounded cleanup deadline.
            phase = 'exit'; expected = null; deadline(8000);
            notification('exit');
            child.stdin.end();
          } else throw new Error('unexpected response after shutdown');
        }
        function receive(chunk) {
          if (finished) return;
          received += chunk.length;
          if (received > 8 * 1024 * 1024) throw new Error('protocol output limit exceeded');
          buffer = Buffer.concat([buffer, chunk]);
          while (!finished) {
            if (length === null) {
              const end = buffer.indexOf('\r\n\r\n');
              if (end < 0) {
                if (buffer.length > 8192) throw new Error('protocol header limit exceeded');
                return;
              }
              if (end > 8192) throw new Error('protocol header limit exceeded');
              const raw = buffer.subarray(0, end);
              if ([...raw].some(byte => byte > 127 || (byte < 32 && byte !== 9 && byte !== 10 && byte !== 13)))
                throw new Error('invalid protocol header');
              const headers = raw.toString('ascii').split('\r\n');
              let declared = null;
              for (const header of headers) {
                const match = /^([A-Za-z-]+):[ \t]*(.*)${'$'}/.exec(header);
                if (!match) throw new Error('invalid protocol header');
                if (match[1].toLowerCase() === 'content-length') {
                  if (declared !== null || !/^[0-9]+${'$'}/.test(match[2])) throw new Error('invalid Content-Length');
                  declared = Number(match[2]);
                }
              }
              if (!Number.isSafeInteger(declared) || declared <= 0 || declared > 4 * 1024 * 1024)
                throw new Error('invalid Content-Length');
              length = declared; buffer = buffer.subarray(end + 4);
            }
            if (buffer.length < length) return;
            const body = buffer.subarray(0, length);
            buffer = buffer.subarray(length); length = null;
            handle(JSON.parse(decoder.decode(body)));
          }
        }
        for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP'])
          process.on(signal, () => finish(false, 'health client canceled'));
        try {
          child = spawn(argv[0], argv.slice(1), { stdio: ['pipe', 'pipe', 'pipe'], detached: true });
          child.on('error', error => finish(false, 'cannot start original server: ' + error.message));
          child.stdin.on('error', error => finish(false, 'server stdin failed: ' + error.message));
          child.stdout.on('data', chunk => { try { receive(chunk); } catch (error) { finish(false, error.message); } });
          child.stdout.on('error', error => finish(false, 'server stdout failed: ' + error.message));
          child.stderr.on('data', chunk => { stderr = Buffer.concat([stderr, chunk]).subarray(-65536); });
          child.on('close', (code, signal) => {
            if (finished) return;
            finish(phase === 'exit' && code === 0 && length === null && buffer.length === 0,
              'server exited during ' + phase + ' (code ' + code + ', signal ' + signal + ')');
          });
          deadline(15000);
          request(1, 'initialize', {
            processId: process.pid, rootUri: null, workspaceFolders: null,
            clientInfo: { name: 'Droide installer health', version: '1' }, trace: 'off',
            capabilities: { general: { positionEncodings: ['utf-16'] },
              workspace: { workspaceFolders: false, configuration: false },
              textDocument: { synchronization: { dynamicRegistration: false } } }
          });
        } catch (error) { finish(false, error.message); }
    """.trimIndent() + "\n"
}
