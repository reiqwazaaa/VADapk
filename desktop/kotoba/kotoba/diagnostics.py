"""Local per-task logs; authentication keys are redacted before writing."""
from datetime import datetime
from pathlib import Path
import traceback


class TaskLog:
    def __init__(self, path: Path, sink, secrets=()):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.file = path.open('x', encoding='utf-8')
        self.sink = sink
        self.secrets = tuple(s for s in secrets if s)

    def __call__(self, message):
        message = str(message)
        for secret in self.secrets: message = message.replace(secret, '[ключ скрыт]')
        line = f'{datetime.now().isoformat(timespec="seconds")}  {message}'
        self.file.write(line + '\n'); self.file.flush()
        self.sink(line)

    def exception(self):
        self(traceback.format_exc())

    def close(self):
        self.file.close()
