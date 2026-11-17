from __future__ import annotations
import json
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from datetime import datetime
from uuid import uuid4
from contextlib import suppress
from pathlib import Path
from tkinter import ttk, filedialog, messagebox
from .core import Job, LANGUAGES, MODELS, Cancelled, run_job, verify_exports
from .diagnostics import TaskLog
from . import __version__

STATE = Path(os.getenv('LOCALAPPDATA', Path.home() / '.local' / 'share')) / 'Kotoba'
SETTINGS = STATE / 'settings.json'
TRANSLATORS = {'Офлайн · NLLB-200 600M': 'offline', 'Google Cloud Translation · API': 'google',
               'DeepL · API': 'deepl', 'Только расшифровка': 'none'}

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f'Kotoba {__version__} • аудио → текст → перевод')
        width = min(960, max(760, self.winfo_screenwidth() - 60))
        height = min(900, max(600, self.winfo_screenheight() - 100))
        self.geometry(f'{width}x{height}')
        self.minsize(760, 600)
        self.configure(bg='#10141d')
        self.events = queue.Queue()
        self.cancel = threading.Event()
        self.worker = None
        self.controls = []
        self.first_run = not SETTINGS.exists()
        try: self.settings = json.loads(SETTINGS.read_text('utf-8'))
        except (OSError, ValueError): self.settings = {}
        style = ttk.Style(self)
        style.theme_use('clam')
        style.configure('.', background='#10141d', foreground='#e9edf5', font=('Segoe UI' if os.name == 'nt' else 'DejaVu Sans', 10))
        style.configure('TFrame', background='#10141d')
        style.configure('TLabel', padding=3)
        style.configure('Title.TLabel', font=('Segoe UI' if os.name == 'nt' else 'DejaVu Sans', 26, 'bold'))
        style.configure('Sub.TLabel', foreground='#9caac3')
        style.configure('TButton', background='#26334b', padding=(14, 9), borderwidth=0)
        style.map('TButton', background=[('active', '#35496b')])
        style.configure('Accent.TButton', background='#5069e8', font=('Segoe UI' if os.name == 'nt' else 'DejaVu Sans', 11, 'bold'))
        style.configure('TEntry', fieldbackground='#1b2434', insertcolor='white', padding=8)
        style.configure('TCombobox', fieldbackground='#1b2434', background='#26334b', padding=7)
        style.map('TCombobox', fieldbackground=[('readonly', '#1b2434')], foreground=[('readonly', '#edf2ff')])
        style.configure('TCheckbutton', background='#10141d')
        self.option_add('*TCombobox*Listbox.background', '#1b2434')
        self.option_add('*TCombobox*Listbox.foreground', '#edf2ff')
        canvas = tk.Canvas(self, bg='#10141d', highlightthickness=0)
        scrollbar = ttk.Scrollbar(self, orient='vertical', command=canvas.yview)
        scrollbar.pack(side='right', fill='y')
        canvas.pack(side='left', fill='both', expand=True)
        canvas.configure(yscrollcommand=scrollbar.set)
        outer = ttk.Frame(canvas, padding=26)
        content = canvas.create_window((0, 0), window=outer, anchor='nw')
        outer.bind('<Configure>', lambda e: canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.bind('<Configure>', lambda e: canvas.itemconfigure(content, width=e.width))
        def wheel(event):
            if event.widget != self.logbox:
                canvas.yview_scroll(-int(event.delta / 120), 'units')
        self.bind('<MouseWheel>', wheel)
        ttk.Label(outer, text='ことば   Kotoba', style='Title.TLabel').pack(anchor='w')
        ttk.Label(outer, text='Речь становится текстом. На твоём языке.', style='Sub.TLabel').pack(anchor='w', pady=(0, 16))
        self.source = tk.StringVar()
        self.output = tk.StringVar(value=self.settings.get('output', str(Path.home() / 'Documents' / 'Kotoba')))
        self.model = tk.StringVar(value=self.settings.get('model', 'large-v3-turbo'))
        self.language = tk.StringVar(value=self.settings.get('language', '日本語'))
        self.target = tk.StringVar(value=self.settings.get('target', 'Русский'))
        self.device = tk.StringVar(value=self.settings.get('device', 'auto'))
        self.translator = tk.StringVar(value=self.settings.get('translator', next(iter(TRANSLATORS))))
        self.key = tk.StringVar()
        self.free = tk.BooleanVar(value=self.settings.get('deepl_free', True))
        self.offline = tk.BooleanVar(value=self.settings.get('offline_only', False))
        self.models_dir = tk.StringVar(value=self.settings.get('models_dir', str(STATE / 'models')))
        self.path_row(outer, '01   Видео или аудиофайл', self.source, self.pick_file)
        self.path_row(outer, 'Папка для TXT и SRT', self.output, lambda: self.pick_dir(self.output))
        grid = ttk.Frame(outer)
        grid.pack(fill='x', pady=8)
        grid.columnconfigure((0, 1), weight=1)
        self.combo(grid, '02   Язык речи', self.language, list(LANGUAGES), 0, 0)
        self.combo(grid, 'Язык результата', self.target, list(LANGUAGES), 0, 1)
        self.combo(grid, 'Модель распознавания', self.model, list(MODELS), 2, 0)
        self.combo(grid, 'Устройство · auto / cuda / cpu', self.device, ['auto', 'cuda', 'cpu'], 2, 1)
        self.recommendation = ttk.Label(outer, style='Sub.TLabel', wraplength=840)
        self.recommendation.pack(anchor='w')
        self.model.trace_add('write', lambda *_: self.update_hint())
        self.update_hint()
        self.combo(outer, '03   Перевод', self.translator, list(TRANSLATORS))
        keyrow = ttk.Frame(outer)
        keyrow.pack(fill='x', pady=5)
        ttk.Label(keyrow, text='API-ключ').pack(side='left')
        self.key_entry = ttk.Entry(keyrow, textvariable=self.key, show='•')
        self.key_entry.pack(side='left', fill='x', expand=True, padx=10)
        self.controls.append(self.key_entry)
        self.free_check = ttk.Checkbutton(keyrow, text='DeepL API Free', variable=self.free)
        self.free_check.pack(side='right')
        self.controls.append(self.free_check)
        self.online_hint = ttk.Label(outer, style='Sub.TLabel', wraplength=840)
        self.online_hint.pack(anchor='w')
        self.translator.trace_add('write', lambda *_: self.update_translator())
        self.update_translator()
        self.path_row(outer, 'Хранилище моделей', self.models_dir, lambda: self.pick_dir(self.models_dir))
        cb = ttk.Checkbutton(outer, text='Строго офлайн: использовать только уже скачанные модели', variable=self.offline)
        cb.pack(anchor='w', pady=6)
        self.controls.append(cb)
        actions = ttk.Frame(outer)
        actions.pack(fill='x', pady=10)
        self.start_btn = ttk.Button(actions, text='Распознать и сохранить  →', style='Accent.TButton', command=self.start)
        self.start_btn.pack(side='left')
        self.stop_btn = ttk.Button(actions, text='Отменить', command=self.stop, state='disabled')
        self.stop_btn.pack(side='left', padx=10)
        ttk.Button(actions, text='Открыть результат', command=self.open_output).pack(side='right')
        ttk.Button(actions, text='Журнал', command=self.open_log).pack(side='right', padx=6)
        self.progress = ttk.Progressbar(outer, mode='indeterminate')
        self.progress.pack(fill='x', pady=(0, 8))
        self.logbox = tk.Text(outer, height=7, bg='#0b1018', fg='#b8c8e6', relief='flat', wrap='word',
                              font=('Consolas', 10), padx=12, pady=10, state='disabled')
        self.logbox.pack(fill='both', expand=True)
        self.protocol('WM_DELETE_WINDOW', self.on_close)
        self.after(120, self.poll)
        if self.first_run: self.after(250, self.welcome)
    def path_row(self, parent, label, variable, command):
        ttk.Label(parent, text=label).pack(anchor='w', pady=(6, 0))
        row = ttk.Frame(parent)
        row.pack(fill='x')
        entry = ttk.Entry(row, textvariable=variable)
        entry.pack(side='left', fill='x', expand=True)
        btn = ttk.Button(row, text='Выбрать…', command=command)
        btn.pack(side='right', padx=(8, 0))
        self.controls.extend([entry, btn])
    def combo(self, parent, label, variable, values, row=None, column=None):
        frame = ttk.Frame(parent, padding=(0, 0, 10, 0))
        if row is None: frame.pack(fill='x', pady=(6, 0))
        else: frame.grid(row=row, column=column, sticky='ew', pady=3)
        ttk.Label(frame, text=label).pack(anchor='w')
        widget = ttk.Combobox(frame, textvariable=variable, values=values, state='readonly')
        widget.pack(fill='x')
        self.controls.append(widget)
    def update_hint(self):
        self.recommendation.configure(text=MODELS.get(self.model.get(), '') + ' • CUDA: int8_float16, CPU: int8')
    def update_translator(self):
        online = TRANSLATORS.get(self.translator.get()) in ('google', 'deepl')
        self.key_entry.configure(state='normal' if online else 'disabled')
        self.free_check.configure(state='normal' if TRANSLATORS.get(self.translator.get()) == 'deepl' else 'disabled')
        self.online_hint.configure(text=('Сервис получит текст расшифровки. Нужен API-ключ; возможна оплата по тарифу. Ключ не сохраняется.' if online else 'NLLB работает локально на CPU; при первом использовании скачивается ≈2,5 ГБ. Лицензия CC-BY-NC 4.0.' if TRANSLATORS.get(self.translator.get()) == 'offline' else 'Будут сохранены только исходные TXT и SRT.'))
    def pick_file(self):
        path = filedialog.askopenfilename(title='Выбери видео или аудио', filetypes=[('Медиа', '*.mp4 *.mkv *.mov *.webm *.avi *.mp3 *.wav *.flac *.m4a *.ogg *.opus *.aac'), ('Все файлы', '*.*')])
        if path: self.source.set(path)
    def pick_dir(self, variable):
        path = filedialog.askdirectory()
        if path: variable.set(path)
    def welcome(self):
        win = tk.Toplevel(self)
        win.title('Первый запуск • выбор моделей')
        win.geometry('720x470')
        win.configure(bg='#10141d')
        panel = ttk.Frame(win, padding=25)
        panel.pack(fill='both', expand=True)
        ttk.Label(panel, text='Твой стартовый набор', style='Title.TLabel').pack(anchor='w')
        text = ('RTX 3070 8 ГБ + 16 ГБ RAM\n\n'
                '• large-v3-turbo — стартовый баланс скорости и качества для RU / EN / JA.\n'
                '• large-v3 — попробуй для сложного японского и русского; медленнее.\n'
                '• small — быстрый вариант для английского и работы на CPU.\n'
                '• medium — промежуточный вариант для всех трёх языков.\n\n'
                'На CUDA используется int8_float16. Фактическая память зависит от записи\n'
                'и других программ; если памяти мало, выбери medium или CPU.\n\n'
                'Whisper скачивается при запуске задачи (примерно 0,5–3,1 ГБ).\n'
                'Для локального перевода: NLLB-200 600M на CPU, после выгрузки Whisper.\n'
                'Модели можно менять в основном окне. Интернет нужен для первой загрузки.')
        ttk.Label(panel, text=text, wraplength=660, justify='left').pack(anchor='w', pady=14)
        ttk.Combobox(panel, textvariable=self.model, values=list(MODELS), state='readonly').pack(fill='x', pady=(0, 8))
        ttk.Button(panel, text='Использовать выбранную модель', command=win.destroy).pack(anchor='w')
        win.transient(self)
        win.grab_set()
    def append_log(self, text):
        self.logbox.configure(state='normal')
        self.logbox.insert('end', text + '\n')
        self.logbox.see('end')
        self.logbox.configure(state='disabled')
    def save_settings(self):
        STATE.mkdir(parents=True, exist_ok=True)
        data = {name: getattr(self, name).get() for name in ('output', 'model', 'language', 'target', 'device', 'translator', 'models_dir')}
        data.update(deepl_free=self.free.get(), offline_only=self.offline.get())
        temp = SETTINGS.with_suffix('.tmp')
        temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), 'utf-8')
        temp.replace(SETTINGS)
    def start(self):
        if self.worker and self.worker.is_alive(): return
        try:
            if not self.source.get().strip() or not Path(self.source.get()).is_file(): raise ValueError('Выбери существующий видео- или аудиофайл.')
            if not self.output.get().strip(): raise ValueError('Выбери папку сохранения.')
            tr = TRANSLATORS[self.translator.get()]
            if self.offline.get() and tr in ('google', 'deepl'): raise ValueError('Для строгого офлайн-режима выбери локальный перевод или только расшифровку.')
            if tr in ('google', 'deepl') and self.language.get() != self.target.get() and not self.key.get().strip(): raise ValueError('Введи API-ключ выбранного сервиса.')
            job = Job(Path(self.source.get()), Path(self.output.get()), LANGUAGES[self.language.get()], LANGUAGES[self.target.get()],
                      self.model.get(), self.device.get(), tr, self.key.get().strip(), self.free.get(), Path(self.models_dir.get()), self.offline.get())
            self.save_settings()
            log_path = STATE / 'logs' / f'job_{datetime.now():%Y%m%d_%H%M%S}_{uuid4().hex[:8]}.log'
            task_log = TaskLog(log_path, lambda line: self.events.put(('log', line)), [job.api_key])
        except Exception as e:
            messagebox.showerror('Проверь настройки', str(e)); return
        self.last_output = job.output
        self.last_log = log_path
        self.logbox.configure(state='normal')
        self.logbox.delete('1.0', 'end')
        self.logbox.configure(state='disabled')
        self.cancel.clear()
        self.start_btn.configure(state='disabled')
        self.stop_btn.configure(state='normal')
        for widget in self.controls: widget.configure(state='disabled')
        self.progress.start(12)
        self.append_log('Новая задача: ' + job.source.name)
        def work():
            try:
                task_log(f'Kotoba {__version__}; источник: {job.source.resolve()}')
                task_log(f'Языки: {job.language} → {job.target}; Whisper: {job.model}; устройство: {job.device}; перевод: {job.translator}')
                task_log(f'Журнал: {log_path}')
                paths = run_job(job, self.cancel, task_log)
                sizes = verify_exports(paths)
                task_log(f'УСПЕХ: на диске проверено {len(paths)} файлов, {sum(sizes.values())} байт.')
                self.events.put(('done', (paths[0].parent, len(paths))))
            except Cancelled:
                with suppress(OSError): task_log('ОТМЕНЕНО. Уже сохранённая расшифровка остаётся в папке результата.')
                self.events.put(('cancel', 'Отменено. Уже сохранённая расшифровка остаётся в папке результата.'))
            except Exception as error:
                with suppress(OSError): task_log.exception()
                message = str(error).replace(job.api_key, '[ключ скрыт]') if job.api_key else str(error)
                self.events.put(('error', message + f'\n\nПолный журнал: {log_path}'))
            finally:
                with suppress(OSError): task_log.close()
        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()
    def stop(self):
        self.cancel.set()
        self.stop_btn.configure(state='disabled')
        self.append_log('Отмена запрошена. Ждём завершения текущего фрагмента / загрузки / HTTP-запроса…')
    def poll(self):
        try:
            while True:
                kind, data = self.events.get_nowait()
                if kind == 'log': self.append_log(data); continue
                self.progress.stop()
                self.start_btn.configure(state='normal')
                self.stop_btn.configure(state='disabled')
                for widget in self.controls:
                    widget.configure(state='readonly' if isinstance(widget, ttk.Combobox) else 'normal')
                self.update_translator()
                if kind == 'done':
                    self.last_output, count = data
                    self.append_log(f'✓ На диске проверено {count} файлов. Папка: {self.last_output}')
                else:
                    self.append_log(str(data))
                    if kind == 'error': messagebox.showerror('Задача не завершена', str(data))
        except queue.Empty: pass
        self.after(120, self.poll)
    def open_log(self):
        path = getattr(self, 'last_log', None)
        if path is None or not path.is_file():
            messagebox.showinfo('Журнал', 'Журнал появится после запуска задачи.'); return
        if sys.platform == 'win32': subprocess.Popen(['notepad.exe', str(path)])
        else: subprocess.Popen(['open' if sys.platform == 'darwin' else 'xdg-open', str(path)])
    def open_output(self):
        path = getattr(self, 'last_output', Path(self.output.get()))
        if not path.is_dir(): messagebox.showinfo('Папка результата', 'Папка появится после запуска задачи.'); return
        if sys.platform == 'win32': os.startfile(path)
        else: subprocess.Popen(['open' if sys.platform == 'darwin' else 'xdg-open', str(path)])
    def on_close(self):
        if self.worker and self.worker.is_alive():
            messagebox.showinfo('Задача выполняется', 'Нажми «Отменить» и дождись завершения текущей операции, затем закрой окно.'); return
        self.destroy()

if __name__ == '__main__': App().mainloop()
