package com.baystudio.droide.core

/** Buf-only lifecycle/cache isolation. Original helper bytes and other families remain unchanged. */
internal object LocalUbuntuBufLspHealth {
    const val FILE_NAME = "buf-lsp-health.cjs"

    fun scriptForVersion(version: String): String {
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}")))
        return script.replace("'1.74.0'", "'$version'")
    }

    val script: String = run {
        fun replaceOnce(text: String, marker: String, replacement: String): String {
            require(text.indexOf(marker) >= 0 && text.indexOf(marker) == text.lastIndexOf(marker)) {
                "The reviewed Buf health boundary changed"
            }
            return text.replace(marker, replacement)
        }
        val setup = """const fs = require('node:fs');
const path = require('node:path');
let bufRoot = null, bufRootIdentity = null, bufRootFd = null, bufEnvironment = null;
let bufInitialized = false, bufShutdown = false, bufExitEnded = false, bufStderrOverflow = false;
function bufSameIdentity(left, right) { return left.dev === right.dev && left.ino === right.ino; }
function bufCheckRoot() {
  const current = fs.lstatSync(bufRoot);
  if (!current.isDirectory() || current.isSymbolicLink() || !bufSameIdentity(current, bufRootIdentity) ||
      !bufSameIdentity(fs.fstatSync(bufRootFd), bufRootIdentity)) throw new Error('Buf private root identity changed');
}
function bufPrepare() {
  if (argv.length !== 4 || !path.isAbsolute(argv[0]) || argv.some(value => /[\0\r\n]/.test(value)) ||
      argv[1] !== '--log-format=json' || argv[2] !== 'lsp' || argv[3] !== 'serve') throw new Error('invalid Buf health command');
  const lease = process.env.DROIDE_PROCESS_LEASE;
  if (lease !== undefined && !/^[0-9a-f]{20}${'$'}/.test(lease)) throw new Error('invalid process ownership token');
  const parent = fs.lstatSync('/tmp');
  if (!parent.isDirectory() || parent.isSymbolicLink()) throw new Error('Buf private state parent is not a real directory');
  process.umask(0o077);
  bufRoot = fs.mkdtempSync('/tmp/droide-buf-health-');
  bufRootIdentity = fs.lstatSync(bufRoot);
  bufRootFd = fs.openSync(bufRoot, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
  bufCheckRoot();
  const directory = name => { const target = path.join(bufRoot, name); fs.mkdirSync(target, { mode: 0o700 }); return target; };
  directory('work');
  bufEnvironment = {
    HOME: directory('home'), XDG_CONFIG_HOME: directory('xdg-config'), XDG_CACHE_HOME: directory('xdg-cache'),
    XDG_DATA_HOME: directory('xdg-data'), XDG_STATE_HOME: directory('xdg-state'), XDG_RUNTIME_DIR: directory('xdg-runtime'),
    BUF_CONFIG_DIR: directory('buf-config'), BUF_CACHE_DIR: directory('buf-cache'), BUF_DATA_DIR: directory('buf-data'),
    TMPDIR: directory('tmp'), PATH: '', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TERM: 'dumb', NO_COLOR: '1'
  };
  if (lease !== undefined) bufEnvironment.DROIDE_PROCESS_LEASE = lease;
}
function bufCleanup() {
  if (bufRoot === null) return;
  let entries = 0, bytes = 0;
  const stopAt = Date.now() + 2000;
  const bound = depth => {
    if (++entries > 4096 || depth > 32 || bytes > 64 * 1024 * 1024 || Date.now() > stopAt)
      throw new Error('Buf private cleanup bound exceeded');
    bufCheckRoot();
  };
  const remove = (target, depth, expected) => {
    bound(depth);
    const actual = fs.lstatSync(target);
    if (!bufSameIdentity(actual, expected)) throw new Error('Buf private entry identity changed');
    if (actual.isSymbolicLink()) { fs.unlinkSync(target); return; }
    if (actual.isFile()) { bytes += actual.size; bound(depth); fs.unlinkSync(target); return; }
    if (!actual.isDirectory()) throw new Error('Buf private tree contains an unexpected special file');
    for (const name of fs.readdirSync(target)) {
      bound(depth);
      const current = fs.lstatSync(target);
      if (!current.isDirectory() || current.isSymbolicLink() || !bufSameIdentity(current, expected))
        throw new Error('Buf private directory identity changed');
      const childPath = path.join(target, name);
      remove(childPath, depth + 1, fs.lstatSync(childPath));
    }
    const current = fs.lstatSync(target);
    if (!current.isDirectory() || current.isSymbolicLink() || !bufSameIdentity(current, expected))
      throw new Error('Buf private directory identity changed');
    fs.rmdirSync(target);
  };
  try { bufCheckRoot(); remove(bufRoot, 0, bufRootIdentity); }
  finally { if (bufRootFd !== null) { fs.closeSync(bufRootFd); bufRootFd = null; } }
}
function bufStderrValid(code) {
  if (bufStderrOverflow) return false;
  let text;
  try { text = decoder.decode(stderr); } catch (_) { return false; }
  if (text !== '' && !text.endsWith('\n')) return false;
  const lines = text === '' ? [] : text.slice(0, -1).split('\n');
  let startup = false, eof = false;
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (line === 'Failure: failed reading header line: EOF') {
      if (eof || index !== lines.length - 1 || code !== 1) return false;
      eof = true; continue;
    }
    let record;
    try { record = JSON.parse(line); } catch (_) { return false; }
    if (!object(record) || startup || Object.keys(record).sort().join(',') !== 'buf_version,level,msg,time' ||
        record.level !== 'INFO' || record.msg !== 'starting LSP server' || record.buf_version !== '1.74.0' ||
        typeof record.time !== 'string' || !Number.isFinite(Date.parse(record.time))) return false;
    startup = true;
  }
  return code === 1 ? eof : code === 0 && !eof;
}
function bufLifecycleValid(code, signal) {
  return phase === 'exit' && bufInitialized && bufShutdown && bufExitEnded && child.stdin.writableFinished &&
    signal === null && (code === 0 || code === 1) && bufStderrValid(code);
}""".trimIndent()
        var result = LocalUbuntuNpmLspHealth.script
        result = replaceOnce(result, """const argv = process.argv.slice(2);""", setup + "\nconst argv = process.argv.slice(2);")
        result = replaceOnce(result, """const reviewedLuaShutdown = argv[0] === '--droide-reviewed-luals-shutdown';""", """const reviewedLuaShutdown = false;""")
        result = replaceOnce(result, """const reviewedJdtShutdown = argv[0] === '--droide-reviewed-jdtls-shutdown';""", """const reviewedJdtShutdown = false;""")
        result = replaceOnce(result, """if (!own(message, 'id')) return;""", """throw new Error('unexpected Buf server notification or request');""")
        result = replaceOnce(result, """if (!object(message.result) || !object(message.result.capabilities)) throw new Error('initialize capabilities are missing');""", """if (!object(message.result) || !object(message.result.capabilities)) throw new Error('initialize capabilities are missing');
            const caps = message.result.capabilities;
            if (!object(message.result.serverInfo) || message.result.serverInfo.name !== 'buf-lsp' || message.result.serverInfo.version !== '1.74.0' ||
                !object(caps.textDocumentSync) || caps.textDocumentSync.openClose !== true || caps.textDocumentSync.change !== 1 ||
                !object(caps.textDocumentSync.save) || !object(caps.completionProvider) || caps.hoverProvider !== true ||
                JSON.stringify(caps.completionProvider.triggerCharacters) !== JSON.stringify(['.', '"', '/']) ||
                caps.completionProvider.resolveProvider !== true || caps.documentFormattingProvider !== true ||
                !object(caps.semanticTokensProvider) || caps.semanticTokensProvider.full !== true ||
                !object(caps.semanticTokensProvider.legend) ||
                JSON.stringify(caps.semanticTokensProvider.legend.tokenTypes) !== JSON.stringify([
                  'property', 'struct', 'variable', 'enum', 'enumMember', 'interface', 'method', 'function', 'decorator',
                  'macro', 'namespace', 'keyword', 'modifier', 'comment', 'string', 'number', 'type', 'operator']) ||
                JSON.stringify(caps.semanticTokensProvider.legend.tokenModifiers) !== JSON.stringify(['deprecated', 'defaultLibrary']))
              throw new Error('unexpected Buf identity or native capabilities');
            bufInitialized = true;""")
        result = replaceOnce(result, """phase = 'exit'; expected = null; deadline(8000);""", """bufShutdown = true; phase = 'exit'; expected = null; deadline(8000);""")
        result = replaceOnce(result, """child.stdin.end();""", """child.stdin.end(() => { bufExitEnded = true; });""")
        result = replaceOnce(result, """const complete = () => {""", """const complete = () => {
              try { bufCleanup(); } catch (error) {
                success = false; console.error('LSP health: private Buf state cleanup failed: ' + error.message);
              }""")
        result = replaceOnce(result, """child = spawn(argv[0], argv.slice(1), { stdio: ['pipe', 'pipe', 'pipe'], detached: true });""", """bufPrepare();
          child = spawn(argv[0], argv.slice(1), { stdio: ['pipe', 'pipe', 'pipe'], detached: true,
            cwd: path.join(bufRoot, 'work'), env: bufEnvironment });""")
        result = replaceOnce(result, """child.stderr.on('data', chunk => { stderr = Buffer.concat([stderr, chunk]).subarray(-65536); });""", """child.stderr.on('data', chunk => {
            if (stderr.length + chunk.length > 65536) { bufStderrOverflow = true; finish(false, 'Buf stderr bound exceeded'); }
            else stderr = Buffer.concat([stderr, chunk]);
          });""")
        result = replaceOnce(result, """finish(phase === 'exit' && code === 0 && length === null && buffer.length === 0,""", """finish(bufLifecycleValid(code, signal) && length === null && buffer.length === 0,""")
        result = replaceOnce(result, """processId: process.pid, rootUri: null, workspaceFolders: null,""", """processId: null, rootUri: null, workspaceFolders: [], initializationOptions: {},""")
        result = replaceOnce(result, """clientInfo: { name: 'Droide installer health', version: '1' }, trace: 'off',""", """trace: 'off',""")
        result
    }
}
