"""Bounded local document/audio operations for Droide's owned Ubuntu command runner.

No installer, network client, shell interpolation, arbitrary filters, or in-place edits.
Only the Android coordinator publishes output from a private job into the project.
"""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
import zipfile
from xml.dom import minidom

MAX_FILE = 64 * 1024 * 1024
MAX_TEXT = 8000
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'


class MediaError(Exception):
    pass


def need(condition, message):
    if not condition:
        raise MediaError(message)


def number(request, key, default, lo, hi):
    value = request.get(key, default)
    need(isinstance(value, (int, float)) and not isinstance(value, bool) and
         math.isfinite(value) and lo <= value <= hi, 'Invalid ' + key)
    return value


def integer(request, key, default, lo, hi):
    value = number(request, key, default, lo, hi)
    need(value == int(value), key + ' must be an integer')
    return int(value)


def module(name):
    try:
        return __import__(name)
    except ImportError:
        raise MediaError('Missing dependency: ' + name + '. Load the corresponding bundled skill for installation steps; nothing was installed.') from None


def command(argv, timeout=90, limit=32768):
    # Capture into regular private files, not unbounded in-memory PIPE buffers.
    import tempfile
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        try:
            p = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                               timeout=timeout, check=False)
        except FileNotFoundError:
            raise MediaError('Missing dependency: ' + argv[0] + '. Load the audio skill for setup; nothing was installed.') from None
        except subprocess.TimeoutExpired:
            raise MediaError('Media process timed out and was stopped; no project output was published.') from None
        need(p.returncode == 0, argv[0] + ' could not process this file or codec. The original was preserved.')
        out.seek(0)
        data = out.read(limit + 1)
        need(len(data) <= limit, 'Media metadata exceeds the result limit')
        return data.decode('utf-8', 'strict')


def page_text(text, request, notice=''):
    offset = integer(request, 'offset', 0, 0, len(text))
    # Python indexes code points; this continuation is deliberately not a UTF-16 offset.
    end = min(len(text), offset + MAX_TEXT)
    # ASCII JSON can expand an astral character to twelve bytes. Leave room for metadata
    # inside the coordinator's 64 KiB envelope; continuation remains a code-point offset.
    while len(json.dumps(text[offset:end], ensure_ascii=True)) > 60000:
        end = offset + (end - offset) // 2
    return dict(text=text[offset:end], offset=offset, next_offset=end if end < len(text) else None,
                truncated=end < len(text), notice=notice)


def docx_archive(source):
    need(source.stat().st_size <= 32 * 1024 * 1024, 'DOCX exceeds 32 MiB')
    z = zipfile.ZipFile(source)
    try:
        entries = z.infolist()
        need(0 < len(entries) <= 2048, 'DOCX archive has too many entries')
        need(len({e.filename for e in entries}) == len(entries), 'Duplicate DOCX entries')
        total = 0
        for e in entries:
            total += e.file_size
            need(not e.flag_bits & 1 and e.file_size <= MAX_FILE and total <= MAX_FILE and
                 e.file_size <= max(1, e.compress_size) * 1000, 'DOCX archive is encrypted or exceeds expansion limits')
            need('..' not in Path(e.filename).parts and not e.filename.startswith('/'), 'Unsafe DOCX archive path')
        names = z.namelist()
        need('word/document.xml' in names and '[Content_Types].xml' in names, 'Not an ordinary DOCX document')
        need(not any(n.startswith('_xmlsignatures/') or n.lower().endswith('vbaproject.bin') for n in names),
             'Signed or macro-enabled documents require a dedicated editor')
        raw = z.read('word/document.xml')
        need(len(raw) <= 8 * 1024 * 1024, 'DOCX XML exceeds eight MiB')
        text = raw.decode('utf-8-sig', 'strict')
        need(not re.search(r'<!\s*(DOCTYPE|ENTITY)', text, re.I), 'DTD and entity declarations are unsupported')
        document = minidom.parseString(text)
        need(len(document.getElementsByTagName('*')) <= 200000, 'DOCX has too many XML nodes')
        return z, document
    except BaseException:
        z.close()
        raise


def docx_replace(source, output, request):
    old, new = request.get('find'), request.get('replacement')
    need(isinstance(old, str) and old and len(old) <= 8000 and isinstance(new, str) and len(new) <= 8000,
         'find and replacement must be bounded literal strings')
    need(not any(c in old + new for c in '\n\r\t') and all(ord(c) >= 32 for c in old + new),
         'Use single-line literal text for replacement; paragraph and control changes require a document editor')
    expected = integer(request, 'expected_matches', 1, 1, 10000)
    z, doc = docx_archive(source)
    try:
        plans = []
        for p in doc.getElementsByTagNameNS(W, 'p'):
            # Avoid rewriting field codes, tracked changes, drawings or nested text boxes.
            unsupported = {'fldChar', 'instrText', 'del', 'ins', 'drawing', 'pict', 'object', 'tab', 'br', 'cr', 'sym', 'sdt'}
            elements = p.getElementsByTagName('*')
            nodes = list(p.getElementsByTagNameNS(W, 't'))
            text = ''.join(''.join(c.data for c in n.childNodes if c.nodeType == c.TEXT_NODE) for n in nodes)
            if old not in text:
                continue
            need(not any(n.namespaceURI == W and n.localName in unsupported for n in elements),
                 'A matching paragraph contains fields, tracked changes or complex content; use a document editor')
            matches = list(re.finditer(re.escape(old), text))
            plans.append((nodes, matches))
        count = sum(len(matches) for _, matches in plans)
        need(count == expected, 'Literal match count differs from expected_matches; inspect the document before editing')
        for nodes, matches in plans:
            texts = [''.join(c.data for c in n.childNodes if c.nodeType == c.TEXT_NODE) for n in nodes]
            starts, pos = [], 0
            for t in texts:
                starts.append(pos)
                pos += len(t)
            # Work backwards so indices into the unchanged text remain valid.
            for match in reversed(matches):
                first = next(i for i, t in enumerate(texts) if starts[i] <= match.start() < starts[i] + len(t))
                for i in reversed(range(len(texts))):
                    a, b = max(0, match.start() - starts[i]), min(len(texts[i]), match.end() - starts[i])
                    if a < b:
                        texts[i] = texts[i][:a] + (new if i == first else '') + texts[i][b:]
            for node, value in zip(nodes, texts):
                for child in list(node.childNodes):
                    node.removeChild(child)
                node.appendChild(doc.createTextNode(value))
                node.setAttribute('xml:space', 'preserve')
        changed = doc.toxml(encoding='utf-8')
        need(len(changed) <= 8 * 1024 * 1024, 'Edited XML exceeds the limit')
        with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as out:
            for info in z.infolist():
                out.writestr(info, changed if info.filename == 'word/document.xml' else z.read(info))
        return dict(replacements=count, notice='Main-body literal replacements; other package parts and run formatting preserved. Review page layout after edits.')
    finally:
        z.close()
        doc.unlink()


def document_job(source, output, request):
    op, kind = request['operation'], request.get('extension', '')
    if op == 'create_docx':
        docx = module('docx')
        content = request.get('content')
        need(isinstance(content, str) and len(content) <= 40000, 'content must contain at most 40000 characters')
        doc = docx.Document()
        for line in content.split('\n'):
            doc.add_paragraph(line)
        doc.save(output)
        return dict(notice='New DOCX with plain paragraphs; no existing formatting was imported.')
    if kind == 'docx' and op == 'replace_text':
        return docx_replace(source, output, request)
    need(kind == 'pdf', 'Use DOCX/PDF. Legacy .doc and cloud Google Docs links require conversion/export first.')
    pdf = module('pypdf')
    reader = pdf.PdfReader(source, strict=True)
    need(not reader.is_encrypted, 'Encrypted PDF requires an unlocked copy; no password was guessed')
    count = len(reader.pages)
    need(0 < count <= 10000, 'PDF page count exceeds the limit')
    if op == 'inspect':
        return dict(pages=count, encrypted=False, text_extraction='available; scans require OCR or page rendering')
    if op == 'read':
        start = integer(request, 'page', 1, 1, count)
        pages = integer(request, 'page_count', 1, 1, min(4, count - start + 1))
        text = ''
        for i in range(start - 1, start - 1 + pages):
            text += '[Page %d]\n%s\n' % (i + 1, reader.pages[i].extract_text() or '')
            need(len(text) <= 500000, 'Extracted text exceeds the limit; read fewer pages')
        result = page_text(text, request, 'Extracted text only; order, tables, drawings and scanned text are not verified. Use render for visual inspection; OCR is not automatic.')
        result.update(pages=count, page=start, page_count=pages)
        return result
    if op == 'pdf_pages':
        selection = request.get('pages')
        need(isinstance(selection, list) and 0 < len(selection) <= 200 and
             all(isinstance(p, int) and not isinstance(p, bool) and 1 <= p <= count for p in selection),
             'pages must contain 1–200 valid one-based page numbers in desired output order')
        # Retain interactive fields/annotations and document metadata with pypdf's merge API.
        writer = pdf.PdfWriter()
        writer.append(reader, pages=[p - 1 for p in selection])
        with open(output, 'xb') as f:
            writer.write(f)
        return dict(pages=len(selection), notice='Selected/reordered pages in a new PDF. Digital signatures are not preserved. Review forms, links and bookmarks before distribution.')
    if op == 'render':
        page = integer(request, 'page', 1, 1, count)
        prefix = str(output.with_suffix(''))
        command(['pdftoppm', '-f', str(page), '-l', str(page), '-scale-to', '1600', '-singlefile', '-png', str(source), prefix])
        need(Path(prefix + '.png') == output, 'Invalid render destination')
        return dict(page=page, notice='Rendered one page; view_image must inspect the result before making visual claims.')
    raise MediaError('Unsupported document operation for this format')


def audio_job(source, output, request):
    kind = request.get('extension')
    need(kind in ('mp3', 'wav', 'm4a', 'aac', 'flac', 'ogg', 'opus'), 'Unsupported audio container')
    probe = json.loads(command(['ffprobe', '-v', 'error', '-protocol_whitelist', 'file,pipe',
                                '-format_whitelist', 'mp3,wav,mov,aac,flac,ogg',
                                '-show_format', '-show_streams', '-of', 'json', str(source)], timeout=15))
    streams = [s for s in probe.get('streams', []) if s.get('codec_type') == 'audio']
    need(streams, 'No audio stream was found')
    track = streams[0]
    duration = float(probe.get('format', {}).get('duration') or track.get('duration') or 0)
    need(math.isfinite(duration) and duration > 0, 'Audio duration is unavailable')
    if request['operation'] == 'inspect':
        return dict(duration_seconds=duration, codec=track.get('codec_name'), sample_rate=track.get('sample_rate'),
                    channels=track.get('channels'), audio_streams=len(streams), speech_transcribed=False,
                    notice='Technical metadata only; audio has not been heard or transcribed.')
    need(request['operation'] == 'edit', 'Unsupported audio operation')
    start = number(request, 'start_seconds', 0, 0, duration)
    end = number(request, 'end_seconds', duration, 0, duration)
    need(0 < end - start <= 600, 'Choose a nonempty interval of at most ten minutes')
    gain = number(request, 'gain_db', 0, -30, 30)
    rate = integer(request, 'sample_rate', 44100, 8000, 96000)
    channels = integer(request, 'channels', 2, 1, 2)
    fmt = request.get('format', 'wav')
    codecs = {'wav': ('pcm_s16le', 'wav'), 'mp3': ('libmp3lame', 'mp3'),
              'flac': ('flac', 'flac'), 'ogg': ('libvorbis', 'ogg')}
    need(fmt in codecs, 'Output format must be wav, mp3, flac or ogg')
    codec, muxer = codecs[fmt]
    command(['ffmpeg', '-v', 'error', '-nostdin', '-n', '-threads', '1', '-protocol_whitelist', 'file,pipe',
             '-format_whitelist', 'mp3,wav,mov,aac,flac,ogg', '-i', str(source), '-ss', str(start), '-t', str(end - start), '-map', '0:a:0', '-vn', '-sn', '-dn',
             '-map_metadata', '-1', '-filter_threads', '1', '-af', 'volume=%sdB' % gain, '-ar', str(rate),
             '-ac', str(channels), '-c:a', codec, '-f', muxer, str(output)])
    return dict(start_seconds=start, end_seconds=end, gain_db=gain, sample_rate=rate, channels=channels,
                format=fmt, notice='First audio stream decoded and re-encoded. Metadata removed; increasing gain may clip. Listen to the result before distribution.')


def status(kind):
    import importlib.util
    requirements = ['pypdf', 'docx'] if kind == 'document' else []
    programs = ['pdftoppm'] if kind == 'document' else ['ffprobe', 'ffmpeg']
    return dict(python=True, modules={name: importlib.util.find_spec(name) is not None for name in requirements},
                programs={name: shutil.which(name) is not None for name in programs},
                notice='On-demand local dependency check. No downloads or installations performed.')


def main():
    # Do not allow one malformed file to grow memory, CPU or output without a bound.
    resource.setrlimit(resource.RLIMIT_AS, (768 * 1024 * 1024, 768 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CPU, (90, 90))
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE, MAX_FILE))
    if len(sys.argv) == 3 and sys.argv[1] == '--status':
        result = status(sys.argv[2])
    else:
        need(len(sys.argv) == 2, 'Invalid job arguments')
        job = Path(sys.argv[1])
        need(job.is_dir() and not job.is_symlink(), 'Missing ordinary job directory')
        os.chdir(job)
        request_path = Path('request.json')
        need(not request_path.is_symlink() and request_path.stat().st_size <= 65536, 'Invalid request file')
        request = json.loads(request_path.read_text('utf-8'))
        kind = request['kind']
        source = Path('input.' + request.get('extension', 'bin'))
        output = Path('output.' + request.get('output_extension', 'bin'))
        need(re.fullmatch(r'[a-z0-9]{1,8}', request.get('extension', 'bin')) and
             re.fullmatch(r'[a-z0-9]{1,8}', request.get('output_extension', 'bin')), 'Invalid format')
        if request['operation'] != 'create_docx':
            need(source.is_file() and not source.is_symlink() and 0 < source.stat().st_size <= MAX_FILE, 'Invalid input snapshot')
            need(hashlib.sha256(source.read_bytes()).hexdigest() == request['input_sha256'], 'Input snapshot changed')
        need(not output.exists() and not output.is_symlink(), 'Output staging already exists')
        result = document_job(source, output, request) if kind == 'document' else audio_job(source, output, request)
        if output.exists():
            need(output.is_file() and not output.is_symlink() and 0 < output.stat().st_size <= MAX_FILE, 'Invalid output')
            result.update(output=output.name, bytes=output.stat().st_size,
                          sha256=hashlib.sha256(output.read_bytes()).hexdigest())
    print(json.dumps(dict(ok=True, **result), ensure_ascii=True))


if __name__ == '__main__':
    try:
        main()
    except MediaError as failure:
        print(json.dumps(dict(ok=False, error=str(failure))))
        sys.exit(2)
    except Exception:
        # No raw file content, credential, absolute app path or parser traceback in the chat.
        print(json.dumps(dict(ok=False, error='Unsupported, malformed or resource-limited media. Original preserved; no project output published.')))
        sys.exit(2)
