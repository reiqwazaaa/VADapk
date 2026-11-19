from pathlib import Path
import json
import sys


def main(args):
    flag=args[0]
    if flag=='--live-devices':
        try:
            from .live_audio import devices
            result={'ok':True,'devices':devices()}
        except Exception as error:result={'ok':False,'error':str(error)}
        Path(args[1]).write_text(json.dumps(result,ensure_ascii=False),'utf-8')
        return 0 if result['ok'] else 1
    if flag=='--live-worker':
        from .live_worker import entry
        entry(*args[1:5]);return 1
    if flag=='--smoke-live':
        import threading,traceback
        from .live_session import LiveConfig,run_live
        output=Path(args[2]);output.mkdir(parents=True,exist_ok=True)
        config=LiveConfig(output,output/'models',language='en',target='en',model='tiny',device='cpu',
                          source='file',source_file=args[1],translator='none',profile='Быстро')
        log=[];events=[]
        try:
            folder=run_live(config,threading.Event(),log.append,lambda event,data:events.append({'event':event,'data':data}))
            result={'ok':True,'folder':str(folder),'log':log,'events':events}
        except Exception:result={'ok':False,'traceback':traceback.format_exc(),'log':log,'events':events}
        (output/'live-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
        return 0 if result['ok'] else 1
    return 1

if __name__=='__main__':raise SystemExit(main(sys.argv[1:]))
