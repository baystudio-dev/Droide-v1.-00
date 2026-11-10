"""Real-file/real-process tests for the shipped worker, no network or API account."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave
import zipfile
from docx import Document
from pypdf import PdfReader, PdfWriter
from reportlab.pdfgen.canvas import Canvas

WORKER = Path(__file__).resolve().parents[2] / 'main/assets/agent-media/worker.py'


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='media-worker-')
        self.job = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def call(self, kind, op, source=None, ext='bin', output='bin', **kw):
        req = dict(kind=kind, operation=op, extension=ext, output_extension=output, **kw)
        if source is not None:
            (self.job / ('input.' + ext)).write_bytes(source)
            req['input_sha256'] = hashlib.sha256(source).hexdigest()
        (self.job / 'request.json').write_text(json.dumps(req))
        p = subprocess.run([sys.executable, '-I', str(WORKER), str(self.job)], capture_output=True, text=True, timeout=125)
        self.assertNotEqual(-9, p.returncode)
        try:
            result = json.loads(p.stdout)
        except Exception:
            self.fail(p.stdout + p.stderr)
        self.assertEqual(result['ok'], p.returncode == 0, result)
        return result

    def docx(self, texts=('Hello ', 'world')):
        doc = Document()
        p = doc.add_paragraph()
        p.add_run(texts[0]).bold = True
        p.add_run(texts[1]).italic = True
        doc.add_paragraph('Keep this paragraph.')
        b = io.BytesIO(); doc.save(b)
        return b.getvalue()

    def pdf(self):
        b = io.BytesIO(); c = Canvas(b)
        for text in ('First page', 'Second page', 'Third page'):
            c.drawString(50, 700, text); c.showPage()
        c.save(); return b.getvalue()

    def wav(self):
        import math, struct
        b = io.BytesIO()
        with wave.open(b, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
            w.writeframes(b''.join(struct.pack('<h', int(1000 * math.sin(i * 2 * math.pi * 440 / 16000))) for i in range(32000)))
        return b.getvalue()

    def test_docx_cross_run_replacement_preserves_other_parts_and_run_styles(self):
        source = self.docx()
        result = self.call('document', 'replace_text', source, 'docx', 'docx', find='lo wor', replacement='O EAR', expected_matches=1)
        self.assertTrue(result['ok'], result)
        target = (self.job / 'output.docx').read_bytes()
        before, after = zipfile.ZipFile(io.BytesIO(source)), zipfile.ZipFile(io.BytesIO(target))
        self.assertEqual(before.namelist(), after.namelist())
        for n in before.namelist():
            if n != 'word/document.xml': self.assertEqual(before.read(n), after.read(n), n)
        doc = Document(io.BytesIO(target)); self.assertEqual('HelO EARld', doc.paragraphs[0].text)
        self.assertTrue(doc.paragraphs[0].runs[0].bold); self.assertTrue(doc.paragraphs[0].runs[1].italic)
        self.assertEqual(source, (self.job / 'input.docx').read_bytes())

    def test_replacement_count_mismatch_creates_no_output(self):
        r = self.call('document', 'replace_text', self.docx(), 'docx', 'docx', find='Hello', replacement='Bye', expected_matches=2)
        self.assertFalse(r['ok']); self.assertFalse((self.job / 'output.docx').exists())

    def test_multiple_matches_in_same_run(self):
        r = self.call('document', 'replace_text', self.docx(('ab ab ab', '!')), 'docx', 'docx', find='ab', replacement='longer', expected_matches=3)
        self.assertTrue(r['ok'], r)
        self.assertEqual('longer longer longer!', Document(self.job / 'output.docx').paragraphs[0].text)

    def test_doctype_and_duplicate_zip_are_rejected(self):
        src = self.docx(); original = zipfile.ZipFile(io.BytesIO(src)); changed = io.BytesIO()
        with zipfile.ZipFile(changed, 'w') as out:
            for n in original.namelist():
                raw = original.read(n)
                if n == 'word/document.xml': raw = raw.replace(b'<w:document', b'<!DOCTYPE x [<!ENTITY leak SYSTEM "file:///etc/passwd">]><w:document', 1)
                out.writestr(n, raw)
        r = self.call('document', 'replace_text', changed.getvalue(), 'docx', 'docx', find='Hello', replacement='Bye')
        self.assertFalse(r['ok']); self.assertNotIn('root:', json.dumps(r))

    def test_create_docx_round_trip(self):
        r = self.call('document', 'create_docx', output='docx', content='Halo, dunia 🌍\nBaris kedua')
        self.assertTrue(r['ok'], r)
        self.assertEqual(['Halo, dunia 🌍', 'Baris kedua'], [p.text for p in Document(self.job / 'output.docx').paragraphs])

    def test_pdf_extract_text_and_reorder_preserve_vector_text(self):
        source = self.pdf()
        r = self.call('document', 'read', source, 'pdf', page=2, page_count=1)
        self.assertTrue(r['ok'], r); self.assertIn('Second page', r['text']); self.assertNotIn('First page', r['text'])
        r = self.call('document', 'pdf_pages', source, 'pdf', 'pdf', pages=[3, 1])
        self.assertTrue(r['ok'], r)
        pdf = PdfReader(self.job / 'output.pdf'); self.assertEqual(2, len(pdf.pages))
        self.assertIn('Third page', pdf.pages[0].extract_text()); self.assertIn('First page', pdf.pages[1].extract_text())

    def test_pdf_bad_page_and_encryption_fail(self):
        r = self.call('document', 'pdf_pages', self.pdf(), 'pdf', 'pdf', pages=[4])
        self.assertFalse(r['ok']); self.assertFalse((self.job / 'output.pdf').exists())
        w = PdfWriter(); w.add_blank_page(100, 100); w.encrypt('private'); out = io.BytesIO(); w.write(out)
        r = self.call('document', 'inspect', out.getvalue(), 'pdf')
        self.assertFalse(r['ok']); self.assertIn('Encrypted', r['error'])

    def test_render_pdf_one_png(self):
        r = self.call('document', 'render', self.pdf(), 'pdf', 'png', page=2)
        self.assertTrue(r['ok'], r); self.assertTrue((self.job / 'output.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n'))

    def test_real_wav_inspection_trim_resample_and_mp3_encoding(self):
        source = self.wav()
        r = self.call('audio', 'inspect', source, 'wav'); self.assertTrue(r['ok'], r)
        self.assertAlmostEqual(2, r['duration_seconds']); self.assertFalse(r['speech_transcribed'])
        r = self.call('audio', 'edit', source, 'wav', 'wav', start_seconds=.5, end_seconds=1.5, channels=1, sample_rate=8000)
        self.assertTrue(r['ok'], r)
        with wave.open(str(self.job / 'output.wav')) as w:
            self.assertEqual(8000, w.getframerate()); self.assertEqual(8000, w.getnframes()); self.assertEqual(1, w.getnchannels())
        r = self.call('audio', 'edit', source, 'wav', 'mp3', format='mp3', start_seconds=0, end_seconds=1)
        self.assertTrue(r['ok'], r)
        mp3 = (self.job / 'output.mp3').read_bytes()
        r = self.call('audio', 'inspect', mp3, 'mp3'); self.assertTrue(r['ok'], r); self.assertEqual('mp3', r['codec'])
        self.assertEqual(source, (self.job / 'input.wav').read_bytes())

    def test_audio_invalid_interval_and_filter_injection_rejected(self):
        r = self.call('audio', 'edit', self.wav(), 'wav', 'wav', start_seconds=1.5, end_seconds=1)
        self.assertFalse(r['ok'])
        r = self.call('audio', 'edit', self.wav(), 'wav', 'wav', gain_db='0; touch /tmp/media-injected')
        self.assertFalse(r['ok']); self.assertFalse((self.job / 'output.wav').exists())

    def test_disguised_playlist_cannot_read_another_file(self):
        secret = self.job / 'other.wav'; secret.write_bytes(self.wav())
        playlist = ('#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n' + str(secret) + '\n#EXT-X-ENDLIST\n').encode()
        r = self.call('audio', 'inspect', playlist, 'mp3')
        self.assertFalse(r['ok']); self.assertNotIn(str(secret), json.dumps(r))

    def test_existing_output_is_never_overwritten(self):
        p = self.job / 'output.docx'; p.write_bytes(b'keep')
        r = self.call('document', 'create_docx', output='docx', content='new')
        self.assertFalse(r['ok']); self.assertEqual(b'keep', p.read_bytes())

    def test_unicode_text_pages_fit_bridge_envelope_and_continue_without_loss(self):
        import runpy
        page_text = runpy.run_path(str(WORKER))['page_text']
        text = '😀' * 12000 + ' selesai'
        offset, combined = 0, ''
        while True:
            page = page_text(text, dict(offset=offset))
            self.assertLess(len(json.dumps(page).encode('utf-8')), 65536)
            self.assertLessEqual(len(page['text']), 8000)
            combined += page['text']
            if page['next_offset'] is None:
                break
            self.assertGreater(page['next_offset'], offset)
            offset = page['next_offset']
        self.assertEqual(text, combined)

    def test_status_checks_only_local_dependencies(self):
        p = subprocess.run([sys.executable, '-I', str(WORKER), '--status', 'audio'], capture_output=True, text=True, timeout=10)
        r = json.loads(p.stdout); self.assertTrue(r['ok']); self.assertTrue(r['programs']['ffmpeg'])
        self.assertEqual([], list(self.job.iterdir()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
