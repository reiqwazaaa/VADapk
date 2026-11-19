from __future__ import annotations
from dataclasses import replace
from datetime import datetime
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
from contextlib import suppress
import tkinter as tk
from tkinter import ttk,messagebox
from uuid import uuid4
from .diagnostics import TaskLog
from .live_session import LiveConfig,list_devices,run_live


class Captions(tk.Toplevel):
    def __init__(self,parent):
        super().__init__(parent)
        self.title('Kotoba • живые субтитры')
        self.configure(bg='#10141d');self.attributes('-topmost',True)
        self.geometry(f'900x260+30+{max(0,self.winfo_screenheight()-350)}')
        self.font_size=18
        self.source=tk.Text(self,height=3,bg='#10141d',fg='#f1f4ff',wrap='word',relief='flat',padx=16,pady=8)
        self.source.pack(fill='both',expand=True)
        self.source.tag_configure('stable',foreground='#f1f4ff');self.source.tag_configure('draft',foreground='#9aa8bd')
        self.target=tk.Label(self,text='',bg='#10141d',fg='#92d8ff',justify='left',anchor='w',padx=16,pady=8)
        self.target.pack(fill='both',expand=True)
        self.status=tk.Label(self,text='Подготовка…',bg='#10141d',fg='#9aa8bd',anchor='w',padx=16)
        self.status.pack(fill='x')
        self.bind('<Configure>',lambda e:self.target.configure(wraplength=max(200,self.winfo_width()-40)))
        self.protocol('WM_DELETE_WINDOW',self.withdraw)
        self.set_font(18);self.source.configure(state='disabled')
    def set_font(self,size):
        self.font_size=int(float(size))
        self.source.configure(font=('Segoe UI',self.font_size))
        self.target.configure(font=('Segoe UI',self.font_size))
    def preview(self,stable,unstable):
        self.source.configure(state='normal');self.source.delete('1.0','end')
        self.source.insert('end',stable[-300:],'stable');self.source.insert('end',unstable[-300:],'draft')
        self.source.configure(state='disabled')
    def translation(self,text):self.target.configure(text=text[-400:])


class LiveWindow(tk.Toplevel):
    def __init__(self,parent,config,state):
        super().__init__(parent)
        self.title('Kotoba • живая расшифровка');self.geometry('860x720');self.minsize(700,620)
        self.live_config=config;self.state_dir=state
        self.events=queue.Queue();self.stop_event=threading.Event();self.thread=None;self.audio_devices=[]
        self.result=None;self.log_path=None;self.last_committed='';self.want_close=False;self.summary={}
        self.overlay=Captions(self);self.overlay.withdraw()
        self.source=tk.StringVar(value='Системный звук');self.selected=tk.StringVar();self.profile=tk.StringVar(value='Баланс')
        self.threshold=tk.DoubleVar(value=.004);self.status=tk.StringVar(value='Нажми «Обновить устройства», затем выбери источник звука.')
        body=ttk.Frame(self,padding=20);body.pack(fill='both',expand=True)
        ttk.Label(body,text='Живая расшифровка',font=('Segoe UI',22,'bold')).pack(anchor='w')
        ttk.Label(body,text='Языки, Whisper и переводчик берутся из главного окна. Меняй их до открытия этой панели.',wraplength=790).pack(anchor='w',pady=6)
        ttk.Label(body,text=f'{config.language} → {config.target} • {config.model} • {config.translator} • {config.device}').pack(anchor='w')
        self.translation_status=tk.StringVar(value='Перевод отключён.' if config.translator=='none' or config.language==config.target else 'Перевод появится после подтверждения фразы.')
        ttk.Label(body,textvariable=self.translation_status,wraplength=790).pack(anchor='w')
        row=ttk.Frame(body);row.pack(fill='x',pady=8)
        self.kind_box=ttk.Combobox(row,textvariable=self.source,values=['Системный звук','Микрофон'],state='readonly',width=19)
        self.kind_box.pack(side='left');self.kind_box.bind('<<ComboboxSelected>>',lambda e:self.fill_devices())
        self.refresh=ttk.Button(row,text='Обновить устройства',command=self.load_devices);self.refresh.pack(side='right')
        self.device_box=ttk.Combobox(body,textvariable=self.selected,state='readonly');self.device_box.pack(fill='x')
        row=ttk.Frame(body);row.pack(fill='x',pady=10)
        ttk.Label(row,text='Режим').pack(side='left')
        self.profile_box=ttk.Combobox(row,textvariable=self.profile,values=['Быстро','Баланс','Контекст'],state='readonly',width=14)
        self.profile_box.pack(side='left',padx=10)
        ttk.Label(row,text='Порог паузы/шума').pack(side='left')
        self.threshold_scale=ttk.Scale(row,from_=.001,to=.02,variable=self.threshold);self.threshold_scale.pack(side='left',fill='x',expand=True,padx=8)
        ttk.Label(body,text='Быстро: фразы до 6 сек.; Баланс: до 9 сек.; Контекст: до 14 сек. Сдвинь порог влево, если тихая речь не определяется.',wraplength=790).pack(anchor='w')
        overlayrow=ttk.Frame(body);overlayrow.pack(fill='x',pady=8)
        ttk.Button(overlayrow,text='Показать субтитры поверх окон',command=self.show_overlay).pack(side='left')
        ttk.Label(overlayrow,text='Шрифт').pack(side='left',padx=8)
        ttk.Scale(overlayrow,from_=12,to=30,value=18,command=lambda v:self.overlay.set_font(v)).pack(side='left',fill='x',expand=True)
        ttk.Label(overlayrow,text='Прозрачность').pack(side='left',padx=8)
        ttk.Scale(overlayrow,from_=.35,to=1,value=1,command=lambda v:self.overlay.attributes('-alpha',float(v))).pack(side='left',fill='x',expand=True)
        row=ttk.Frame(body);row.pack(fill='x',pady=8)
        self.start_button=ttk.Button(row,text='Начать слушать',command=self.start);self.start_button.pack(side='left')
        self.stop_button=ttk.Button(row,text='Остановить',state='disabled',command=self.stop);self.stop_button.pack(side='left',padx=8)
        ttk.Button(row,text='Открыть результат',command=self.open_output).pack(side='right')
        ttk.Button(row,text='Журнал',command=self.open_log).pack(side='right',padx=8)
        ttk.Label(body,textvariable=self.status,wraplength=790).pack(anchor='w',pady=8)
        self.level=ttk.Progressbar(body,maximum=.08);self.level.pack(fill='x')
        self.history=tk.Text(body,height=10,bg='#0b1018',fg='#c4d2ed',wrap='word',relief='flat',padx=10,pady=10)
        self.history.pack(fill='both',expand=True,pady=(8,0));self.history.configure(state='disabled')
        self.protocol('WM_DELETE_WINDOW',self.close)
        self.poll_id=self.after(100,self.poll)
        self.load_devices()
    def active(self):return self.thread is not None and self.thread.is_alive()
    def show_overlay(self):self.overlay.deiconify();self.overlay.lift()
    def append(self,text):
        self.history.configure(state='normal');self.history.insert('end',text+'\n');self.history.see('end');self.history.configure(state='disabled')
    def load_devices(self):
        self.refresh.configure(state='disabled');self.start_button.configure(state='disabled');self.selected.set('');self.status.set('Поиск WASAPI-устройств…')
        def work():
            try:self.events.put(('devices',list_devices()))
            except Exception as error:self.events.put(('device_error',str(error)))
        threading.Thread(target=work,daemon=True).start()
    def fill_devices(self):
        kind='system' if self.source.get()=='Системный звук' else 'mic'
        self.filtered=[d for d in self.audio_devices if d['kind']==kind]
        names=[f"{d['id']}: {d['name']}" + (' (по умолчанию)' if d.get('default') else '') for d in self.filtered]
        self.device_box.configure(values=names);self.selected.set(names[0] if names else '')
    def start(self):
        if self.active():return
        parent_worker=getattr(self.master,'worker',None)
        if parent_worker and parent_worker.is_alive():
            messagebox.showinfo('Обработка файла','Сначала заверши обработку файла.',parent=self);return
        try:
            if not self.selected.get():raise ValueError('Не найдено устройство. Обнови список или выбери другой источник.')
            index=self.device_box.current()
            if index<0:raise ValueError('Выбери аудиоустройство из списка.')
            device=self.filtered[index]
            config=replace(self.live_config,source=device['kind'],device_id=device['id'],profile=self.profile.get(),threshold=self.threshold.get())
            if config.translator in ('google','deepl') and config.language!=config.target and not config.api_key.strip():raise ValueError('Введи API-ключ в главном окне, затем заново открой живой режим.')
            if config.offline_only and config.translator in ('google','deepl'):raise ValueError('В строгом офлайн-режиме выбери локальный перевод или только расшифровку.')
            self.log_path=self.state_dir/'logs'/f'live_{datetime.now():%Y%m%d_%H%M%S}_{uuid4().hex[:8]}.log'
            logger=TaskLog(self.log_path,lambda s:self.events.put(('log',s)),[config.api_key])
        except Exception as error:messagebox.showerror('Живой режим',str(error),parent=self);return
        self.stop_event.clear();self.last_committed='';self.summary={};self.status.set('Подготовка моделей. Захват ещё не начат.')
        self.translation_status.set('Перевод отключён.' if config.translator=='none' or config.language==config.target else 'Подготовка переводчика…')
        self.overlay.preview('','');self.overlay.translation('');self.show_overlay()
        self.start_button.configure(state='disabled');self.stop_button.configure(state='normal')
        for widget in (self.kind_box,self.device_box,self.profile_box,self.threshold_scale,self.refresh):widget.configure(state='disabled')
        def work():
            try:
                logger(f'Живой режим: {config.source}; устройство {config.device_id}; языки {config.language} → {config.target}; {config.model}; {config.translator}')
                folder=run_live(config,self.stop_event,logger,lambda event,data:self.events.put((event,data)))
                self.events.put(('finished',str(folder)))
            except Exception as error:
                try:logger.exception()
                except OSError:pass
                text=str(error).replace(config.api_key,'[ключ скрыт]') if config.api_key else str(error)
                self.events.put(('failed',text))
            finally:logger.close()
        self.thread=threading.Thread(target=work,daemon=True);self.thread.start()
    def stop(self):self.stop_event.set();self.stop_button.configure(state='disabled');self.status.set('Остановка и сохранение…')
    def poll(self):
        if not self.winfo_exists():return
        try:
            while True:
                event,data=self.events.get_nowait()
                if event=='devices':
                    self.audio_devices=data;self.fill_devices();self.refresh.configure(state='normal');self.start_button.configure(state='normal');self.status.set('Устройства готовы. Захват начнётся после нажатия «Начать слушать».')
                elif event=='device_error':self.status.set('Ошибка устройства: '+data);self.refresh.configure(state='normal');self.start_button.configure(state='normal')
                elif event=='log':self.append(data)
                elif event=='folder':self.result=Path(data)
                elif event in ('status','warning'):self.status.set(data);self.overlay.status.configure(text=data)
                elif event=='translation_unavailable':
                    self.translation_status.set(data);self.append(data);self.overlay.translation('Перевод недоступен. См. журнал в панели живого режима.')
                elif event=='preview':
                    if data['text']:self.overlay.preview(data['stable'],data['unstable'])
                elif event=='commit':
                    self.last_committed=data['text'];self.overlay.preview(data['text'],'')
                    self.append(f"#{data['index']+1} [{data['start']:.1f}–{data['end']:.1f}] {data['text']}")
                elif event=='translation':self.translation_status.set('Перевод работает.');self.overlay.translation(data['text']);self.overlay.status.configure(text=f"Перевод фразы #{data['index']+1}");self.append(f"↳ #{data['index']+1}: {data['text']}")
                elif event=='level':self.level['value']=data['rms']
                elif event=='timing':
                    if data['inference']>4:self.status.set(f"Распознавание занимает {data['inference']:.1f} сек. Если отстаёт — выбери small или «Быстро».")
                elif event=='summary':self.summary=data;self.append(f"Сессия: {data['segments']} фраз, {data['translations']} переводов; состояние {data['status']}.")
                elif event in ('finished','failed'):
                    self.start_button.configure(state='normal');self.stop_button.configure(state='disabled')
                    for widget in (self.kind_box,self.device_box,self.profile_box):widget.configure(state='readonly')
                    for widget in (self.threshold_scale,self.refresh):widget.configure(state='normal')
                    if event=='failed':self.status.set('Сессия прервана. Полученный текст сохранён; смотри журнал.')
                    elif not self.summary.get('segments'):self.status.set('Сессия остановлена: речь не обнаружена. Проверь источник/уровень звука или уменьши порог.')
                    elif self.summary.get('translation_incomplete') or self.summary.get('translation_skipped'):self.status.set('Оригинал сохранён. Часть перевода не завершена; смотри журнал.')
                    else:self.status.set('Сессия остановлена и сохранена.')
                    self.overlay.status.configure(text=self.status.get())
                    if event=='failed':self.append(data);messagebox.showerror('Живой режим',data+f'\nЖурнал: {self.log_path}',parent=self)
                    if self.want_close:self.destroy();return
        except queue.Empty:pass
        self.poll_id=self.after(100,self.poll)
    def open_output(self):
        if self.result and self.result.is_dir():self.open_path(self.result)
    def open_log(self):
        if self.log_path and self.log_path.exists():
            if sys.platform=='win32':subprocess.Popen(['notepad.exe',str(self.log_path)])
            else:self.open_path(self.log_path)
    def open_path(self,path):
        if sys.platform=='win32':os.startfile(path)
        else:subprocess.Popen(['open' if sys.platform=='darwin' else 'xdg-open',str(path)])
    def close(self):
        if self.active():self.want_close=True;self.stop()
        else:self.destroy()

    def destroy(self):
        with suppress(tk.TclError):
            if getattr(self,'poll_id',None):self.after_cancel(self.poll_id)
        super().destroy()
