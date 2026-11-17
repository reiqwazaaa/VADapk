import tempfile
import os
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from kotoba.core import Job, Segment, Cancelled, run_job, timestamp, OnlineTranslator, export, path_units, safe_stem
from kotoba.diagnostics import TaskLog

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
    def test_recognizer_failure_does_not_leave_result_folder(self):
        with self.assertRaisesRegex(RuntimeError, 'model failed'):
            run_job(self.job, self.cancel, self.logs.append, Mock(side_effect=RuntimeError('model failed')))
        self.assertEqual(list(self.job.output.iterdir()), [])

    def test_replace_failure_not_success_and_removes_empty_folder(self):
        with patch('kotoba.core.os.replace', side_effect=PermissionError('write blocked')):
            with self.assertRaisesRegex(RuntimeError, 'Ошибка сохранения'):
                run_job(self.job, self.cancel, self.logs.append, self.recognize)
        self.assertFalse(any('Готово' in line for line in self.logs))
        self.assertEqual(list(self.job.output.iterdir()), [])

    def test_readback_mismatch_never_reports_success(self):
        with patch('pathlib.Path.read_text', return_value=''):
            with self.assertRaisesRegex(RuntimeError, 'не совпало'):
                run_job(self.job, self.cancel, self.logs.append, self.recognize)
        self.assertFalse(any('Готово' in line for line in self.logs))

    def test_long_source_name_with_legacy_windows_path_limit(self):
        source = self.root / ('a' * 150 + '.mp4')
        source.touch(); self.job.source = source; self.job.translator = 'none'
        real_replace = os.replace
        def legacy_replace(src, dst):
            if path_units(dst) > 240: raise OSError('Windows path too long')
            return real_replace(src, dst)
        with patch('kotoba.core.os.replace', side_effect=legacy_replace):
            paths = run_job(self.job, self.cancel, self.logs.append, self.recognize)
        for path in paths:
            self.assertLessEqual(path_units(path), 240)
            self.assertTrue(path.is_file())
            self.assertIn('こんにちは', path.read_text('utf-8-sig'))

    def test_unicode_long_names_are_safe_and_roundtrip(self):
        paths = export(self.recognize(), self.root, '日本語' * 80 + '😀' * 30, 'ja')
        for path in paths:
            self.assertLessEqual(path_units(path.stem), 70)
            self.assertIn('こんにちは', path.read_text('utf-8-sig'))
        self.assertTrue(safe_stem('CON').startswith('_'))
        self.assertNotIn(':', safe_stem('name:question?'))

    def test_saved_file_deleted_before_finish_is_not_success(self):
        self.job.translator = 'none'
        def logger(line):
            self.logs.append(line)
            if line.startswith('Исходная расшифровка сохранена:'):
                next(self.job.output.rglob('*.txt')).unlink()
        with self.assertRaisesRegex(RuntimeError, 'проверить сохранённый файл'):
            run_job(self.job, self.cancel, logger, self.recognize)
        self.assertFalse(any('Готово' in line for line in self.logs))

    def test_timestamp_carry(self):
        self.assertEqual(timestamp(3599.9999), '01:00:00,000')
        self.assertEqual(timestamp(-2), '00:00:00,000')

class LogTests(unittest.TestCase):
    def test_api_key_is_redacted_from_file_and_ui(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'job.log'; lines = []
            log = TaskLog(path, lines.append, ['test-secret-key'])
            try:
                log('Request failed: key=test-secret-key')
                try: raise RuntimeError('HTTP key=test-secret-key')
                except RuntimeError: log.exception()
            finally: log.close()
            self.assertNotIn('test-secret-key', path.read_text('utf-8'))
            self.assertNotIn('test-secret-key', '\n'.join(lines))
            self.assertIn('RuntimeError', path.read_text('utf-8'))


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
