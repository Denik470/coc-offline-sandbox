"""Bounded on-device graphics startup check. Always stops only our package."""
from pathlib import Path
import argparse,subprocess,time
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--label',default='latest');p.add_argument('--seconds',type=float,default=3);p.add_argument('--install',action='store_true');p.add_argument('--original-assets',action='store_true');p.add_argument('--local-home',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parent;ws=root.parents[2];adb=[str(ws/'work/deep-tools/adb36/platform-tools/adb'),'-s',a.serial]
def run(args,**kw):return subprocess.run(adb+args,timeout=kw.pop('timeout',20),**kw)
if a.install:run(['install','-r','--no-incremental',str(root/'offline-lab.apk')],check=True,timeout=600)
try:
 run(['shell','am','start','-S','-n','lab.coc.offline/.RenderActivity']+(['--ez','originalAssets','true'] if a.original_assets else [])+(['--ez','localHome','true'] if a.local_home else []),check=True)
 time.sleep(min(max(a.seconds,1),25))
 r=run(['shell','dumpsys','activity','activities'],capture_output=True,text=True)
 tops=[line.strip() for line in r.stdout.splitlines() if 'topResumedActivity=' in line];print('TOP',tops);(root/(a.label+'-foreground.txt')).write_text('\n'.join(tops))
 owned=[line.strip() for line in r.stdout.splitlines() if 'lab.coc.offline' in line];(root/(a.label+'-activity.txt')).write_text('\n'.join(owned[:40]));print('OWN ACTIVITY',owned[:12])
 if any('lab.coc.offline/.RenderActivity' in line or 'lab.coc.offline/lab.coc.offline.RenderActivity' in line for line in tops):
  r=run(['exec-out','screencap','-p'],capture_output=True);(root/(a.label+'-screen.png')).write_bytes(r.stdout)
 else:
  r=run(['shell','dumpsys','activity','exit-info','lab.coc.offline'],capture_output=True,text=True)
  (root/(a.label+'-exit-info.txt')).write_text(r.stdout+r.stderr)
 for name in ['renderer-java-status.txt','renderer-progress.txt','renderer-progress.txt.crash','native-dialog.txt']:
  r=run(['shell','run-as','lab.coc.offline','cat','files/'+name],capture_output=True,text=True)
  (root/(a.label+'-'+name)).write_text(r.stdout+r.stderr);print(name,r.stdout[-3500:])
finally:run(['shell','am','force-stop','lab.coc.offline'])
