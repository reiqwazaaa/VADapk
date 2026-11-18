import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


class WorkerCrashUITests(unittest.TestCase):
    def test_gui_survives_worker_crash_and_keeps_transcript(self):
        try:
            import kotoba.app as mod
        except ImportError as error:
            self.skipTest(str(error))
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); state = root / 'state'; state.mkdir()
            settings = state / 'settings.json'; settings.write_text('{}')
            with patch.object(mod,'STATE',state),patch.object(mod,'SETTINGS',settings):
                try: app = mod.App()
                except Exception as error:
                    if error.__class__.__name__ == 'TclError': self.skipTest('No Tk display')
                    raise
                try:
                    source=root/'audio.mp4';source.touch()
                    app.source.set(str(source));app.output.set(str(root/'out'))
                    app.translator.set('Только расшифровка')
                    script=root/'native crash.py'
                    script.write_text("import sys,json,os\nfrom pathlib import Path\np=Path(sys.argv[2])\np.write_text(json.dumps({'event':'segment','start':0,'end':2,'text':'Preserved text'})+'\\n')\nos._exit(73)\n")
                    errors=[]
                    command=lambda req,events,fault:[sys.executable,str(script),str(req),str(events),str(fault)]
                    with patch('kotoba.asr_process.worker_command',side_effect=command),patch.object(mod.messagebox,'showerror',side_effect=lambda *a:errors.append(a)):
                        app.start()
                        deadline=time.monotonic()+10
                        while time.monotonic()<deadline:
                            app.update()
                            if app.worker and not app.worker.is_alive():app.poll();break
                            time.sleep(.01)
                    self.assertTrue(app.winfo_exists())
                    self.assertEqual(str(app.start_btn['state']),'normal')
                    self.assertTrue(errors)
                    self.assertIn('Preserved text',next((root/'out').rglob('*.txt')).read_text('utf-8-sig'))
                    log=app.last_log.read_text('utf-8')
                    self.assertIn('0x00000049',log)
                    self.assertNotIn('УСПЕХ:',log)
                finally:app.destroy()

if __name__=='__main__':unittest.main()
