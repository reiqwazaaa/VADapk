import socket
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import requests
from huggingface_hub.errors import LocalEntryNotFoundError
from kotoba.model_download import prepare_nllb, ModelDownloadError, is_network_error


class ModelDownloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name) / 'snapshot'; self.folder.mkdir()
        self.job = SimpleNamespace(models_dir=Path(self.temp.name), offline_only=False)
        self.logs = []

    def complete(self):
        for name in ('config.json', 'pytorch_model.bin', 'tokenizer_config.json', 'tokenizer.json'):
            (self.folder / name).write_text('downloaded')
        return str(self.folder)

    @patch('huggingface_hub.snapshot_download')
    def test_complete_cache_never_uses_network(self, download):
        download.return_value = self.complete()
        self.assertEqual(prepare_nllb(self.job, self.logs.append), self.folder)
        self.assertTrue(download.call_args.kwargs['local_files_only'])
        self.assertEqual(download.call_count, 1)

    @patch('huggingface_hub.snapshot_download')
    def test_strict_offline_with_partial_model_explains_how_to_finish(self, download):
        download.return_value = str(self.folder); self.job.offline_only = True
        with self.assertRaisesRegex(ModelDownloadError, 'не скачана полностью'):
            prepare_nllb(self.job, self.logs.append)
        self.assertEqual(download.call_count, 1)

    @patch('kotoba.model_download.time.sleep')
    @patch('huggingface_hub.snapshot_download')
    def test_interrupted_download_retries_without_deleting_partial_data(self, download, sleep):
        partial = self.folder / 'weights.incomplete'; partial.write_bytes(b'partial')
        attempts = []
        def fetch(*args, **kwargs):
            if kwargs.get('local_files_only'): return str(self.folder)
            self.assertEqual(partial.read_bytes(), b'partial')
            attempts.append(kwargs['cache_dir'])
            if len(attempts) == 1: raise requests.ConnectionError('DNS failed')
            return self.complete()
        download.side_effect = fetch
        self.assertEqual(prepare_nllb(self.job, self.logs.append), self.folder)
        self.assertEqual(len(attempts), 2)
        self.assertEqual(attempts[0], attempts[1])

    @patch('kotoba.model_download.time.sleep')
    @patch('huggingface_hub.snapshot_download')
    def test_network_failure_is_bounded_and_actionable(self, download, sleep):
        download.side_effect = [LocalEntryNotFoundError('missing')] + [requests.ReadTimeout('timeout')] * 3
        with self.assertRaisesRegex(ModelDownloadError, 'huggingface.co') as error:
            prepare_nllb(self.job, self.logs.append)
        self.assertNotIn('Traceback', str(error.exception))
        self.assertEqual(download.call_count, 4)

    def test_wrapped_dns_error_is_network_but_permissions_are_not(self):
        try:
            try: raise socket.gaierror(11001, 'getaddrinfo failed')
            except OSError as cause: raise RuntimeError('download failed') from cause
        except RuntimeError as error:
            self.assertTrue(is_network_error(error))
        self.assertFalse(is_network_error(PermissionError('Access denied')))

    @patch('huggingface_hub.snapshot_download')
    def test_disk_error_is_not_retried_as_network(self, download):
        download.side_effect = [LocalEntryNotFoundError('missing'), PermissionError('Access denied')]
        with self.assertRaises(PermissionError): prepare_nllb(self.job, self.logs.append)
        self.assertEqual(download.call_count, 2)
