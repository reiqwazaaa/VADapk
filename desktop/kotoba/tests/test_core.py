import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from kotoba.core import Job, Segment, Cancelled, run_job, timestamp, OnlineTranslator

class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / '日本語 видео.mp4'
        self.source.touch()
        self.job = Job(self.source, self.root / 'out')
        self.cancel = threading.Event()
        self.logs = []
    def recognize(self, *args): return [Segment(0, 1.25, 'こんにちは'), Segment(3, 5.9999, '世界')]
    def test_export_preserves_timing_and_unicode(self):
        translator = Mock()
        translator.translate.side_effect = ['Привет', 'Мир']
        paths = run_job(self.job, self.cancel, self.logs.append, self.recognize, lambda j: translator)
        self.assertEqual(len(paths), 4)
        text = paths[3].read_text('utf-8-sig')
        self.assertIn('00:00:03,000 --> 00:00:06,000\nМир', text)
        self.assertIn('こんにちは', paths[0].read_text('utf-8-sig'))
        translator.close.assert_called_once()
        other = run_job(self.job, self.cancel, self.logs.append, self.recognize, lambda j: Mock(translate=lambda t: t))
        self.assertNotEqual(paths[0].parent, other[0].parent)
    def test_failed_translation_keeps_source(self):
        translator = Mock()
        translator.translate.side_effect = RuntimeError('offline')
        with self.assertRaises(RuntimeError):
            run_job(self.job, self.cancel, self.logs.append, self.recognize, lambda j: translator)
        self.assertEqual(len(list(self.job.output.rglob('*.txt'))), 1)
        self.assertEqual(len(list(self.job.output.rglob('*.srt'))), 1)
        translator.close.assert_called_once()
    def test_cancel_after_original(self):
        def factory(j):
            self.cancel.set()
            return Mock()
        with self.assertRaises(Cancelled):
            run_job(self.job, self.cancel, self.logs.append, self.recognize, factory)
        self.assertEqual(len(list(self.job.output.rglob('*.srt'))), 1)
    def test_silence_no_empty_results(self):
        with self.assertRaisesRegex(ValueError, 'Речь не найдена'):
            run_job(self.job, self.cancel, self.logs.append, lambda *a: [])
        self.assertEqual(list(self.job.output.rglob('*.txt')), [])
    def test_same_language_no_translator(self):
        self.job.target = self.job.language
        factory = Mock()
        paths = run_job(self.job, self.cancel, self.logs.append, self.recognize, factory)
        self.assertEqual(len(paths), 2)
        factory.assert_not_called()
    def test_missing_api_key_before_recognition(self):
        self.job.translator = 'google'
        recognizer = Mock()
        with self.assertRaisesRegex(ValueError, 'API'):
            run_job(self.job, self.cancel, self.logs.append, recognizer)
        recognizer.assert_not_called()
    def test_timestamp_carry(self):
        self.assertEqual(timestamp(3599.9999), '01:00:00,000')
        self.assertEqual(timestamp(-2), '00:00:00,000')

class ApiTests(unittest.TestCase):
    @patch('requests.Session')
    def test_google_contract(self, session):
        session.return_value.post.return_value.status_code = 200
        session.return_value.post.return_value.json.return_value = {'data': {'translations': [{'translatedText': 'A &amp; B'}]}}
        translator = OnlineTranslator(Job(Path('a'), Path('b'), translator='google', api_key='secret'))
        self.assertEqual(translator.translate('test'), 'A & B')
        _, kwargs = session.return_value.post.call_args
        self.assertEqual(kwargs['json']['source'], 'ja')
        self.assertEqual(kwargs['json']['target'], 'ru')
    @patch('requests.Session')
    def test_deepl_contract_and_error(self, session):
        session.return_value.post.return_value.status_code = 200
        session.return_value.post.return_value.json.return_value = {'translations': [{'text': 'hello'}]}
        translator = OnlineTranslator(Job(Path('a'), Path('b'), target='en', translator='deepl', api_key='secret'))
        self.assertEqual(translator.translate('test'), 'hello')
        args, kwargs = session.return_value.post.call_args
        self.assertIn('api-free.deepl.com', args[0])
        self.assertEqual(kwargs['json']['target_lang'], 'EN-US')
        session.return_value.post.return_value.status_code = 403
        with self.assertRaisesRegex(RuntimeError, 'HTTP 403') as error: translator.translate('test')
        self.assertNotIn('secret', str(error.exception))

if __name__ == '__main__': unittest.main()
