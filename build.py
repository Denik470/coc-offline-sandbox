"""Build a separate network-disabled native diagnostic APK using workspace SDK."""
from pathlib import Path
import os,subprocess,zipfile,shutil,struct,sys,json,hashlib,csv,io
ROOT=Path(__file__).resolve().parents[1];PROJECT=Path(__file__).resolve().parent;WS=ROOT.parents[1];SDK=WS/'work/android-sdk';BUILD=WS/'work/android-offline-build';TOOLS=SDK/'build-tools/36.0.0';JAVA=Path('/opt/homebrew/opt/openjdk/bin');NDK=SDK/'ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64'
BUILD.mkdir(parents=True,exist_ok=True)
for name in ['classes','dex','assets']:
 shutil.rmtree(BUILD/name,ignore_errors=True);(BUILD/name).mkdir()
SPLIT_APK=PROJECT/'offline-split-homeassets.apk';split_files=None
def run(args):subprocess.run([str(x) for x in args],check=True,env={**os.environ,'JAVA_HOME':str(JAVA.parent)})
sys.path.insert(0,str(ROOT/'tools'));from resource_tool import decode
if '--home-assets' in sys.argv:
 source=ROOT/'assets-current';catalog=csv.DictReader(io.StringIO(decode((source/'csv/assets.csv').read_bytes()).decode()))
 files=set();missing=[]
 for row in catalog:
  if row.get('Home','').upper()!='TRUE':continue
  relative=Path(row['Name']);item=source/relative
  if not item.is_file():missing.append(str(relative));continue
  files.add(item)
  if item.suffix=='.sc':files.update(item.parent.glob(item.stem+'_*.sctx'))
 # UI SC files (ui/sc/*.sc|sctx) are opened by graphics workers but not listed in assets.csv; observed via sc_open_failed (buttons, effects, backgrounds...)
 files.update(p for p in (source/'ui/sc').iterdir() if p.is_file())
 # Top-level UI SC (overlay, tutorial, events...) also absent from catalog; skip lazily loaded info_/chr_/hero_/background_/bb_/_dl assets
 for p in (source/'sc').glob('*.sc'):
  # info_* (troop cards) are opened by the attack screen (sc_open_failed: info_wizard.sc, info_archer.sc)
  if p.stem.startswith(('chr_','hero_','background_','bb_')) or '_dl' in p.stem or p in files:continue
  files.add(p);files.update(p.parent.glob(p.stem+'_*.sctx'))
 # Base scenery, 24 MB (sc_open_failed: background_player.sc, background_gamearea.sc once home scene starts loading; npc/war needed for battles)
 for p in (source/'sc').glob('background_*.sc'):
  if '_dl' in p.stem:continue
  files.add(p)
 # Characters (army camps at home, troops in battle; sc_open_failed: chr_wizard.sc) 100 MB + base hero SC 2 MB; downloadable hero skins (_dl) excluded
 # Base 3D models/textures (sc3d, 82 MB, no _dl skins): visual ctor 8033c0 takes the RuntimeAsset branch for 3D entities and derefs a null handle when the .glb is absent
 # All hero skins (sandbox): every sc3d model/texture/animation incl. downloadable skins (_dl), skin icons and every file referenced by logic/skins.csv.
 files.update(p for p in (source/'sc3d').iterdir() if p.is_file())
 files.update(p for p in (source/'image/skin_icons').iterdir() if p.is_file())
 for row in csv.reader(io.StringIO((ROOT/'tables-current/logic/skins.csv').read_text(encoding='utf-8',errors='ignore'))):
  for v in row:
   if v.endswith(('.sc','.sctx','.glb','.png')) and (source/v).is_file():
    files.add(source/v)
    if v.endswith('.sc'):files.update((source/v).parent.glob(Path(v).stem+'_*.sctx'))
 for p in [*(source/'sc').glob('chr_*.sc'),*(source/'sc').glob('hero_*.sc')]:
  if '_dl' in p.stem and not p.stem.startswith('hero_'):continue
  files.add(p);files.update(p.parent.glob(p.stem+'_*.sctx'))
 # Sounds: every music track (scenery ambience/music, *_dl_opt.ogg, normally downloaded on demand) and every loose sfx file, so all
 # sceneries and effects play offline on any phone without extra downloads.
 for folder in ('music','sfx'):files.update(p for p in (source/folder).rglob('*') if p.is_file())
 # Village background thumbnails (layout editor previews; checkerboard without them) and the scenery of the background selected in the captured home (selected_random_background).
 files.update(p for p in (source/'image/background_icons').iterdir() if p.is_file())
 bg_rows=[x for x in csv.reader(io.StringIO((ROOT/'tables-current/logic/village_backgrounds.csv').read_text(encoding='utf-8',errors='ignore')))][2:]
 bg_rows=[x for x in bg_rows if x and x[0]]
 for bg in json.loads((ROOT/'local-lab/capture/home-canonical.json').read_text()).get('selected_random_background',[]):
  row=bg_rows[bg-60000000] if 0<=bg-60000000<len(bg_rows) else []
  for v in row:
   if v.endswith(('.sc','.sctx')) and (source/v).is_file():
    files.add(source/v)
    if v.endswith('.sc'):files.update((source/v).parent.glob(Path(v).stem+'_*.sctx'))
 # Sandbox scenery selection uses the original picker. Include the full table closure,
 # not only the scenery selected in the original captured home.
 for row in csv.reader(io.StringIO((ROOT/'tables-current/logic/village_backgrounds.csv').read_text(encoding='utf-8'))):
  for value in row:
   if not value.endswith(('.sc','.sctx','.glb','.png')):continue
   path=source/value
   if not path.is_file():raise FileNotFoundError('Missing scenery asset: '+value)
   files.add(path)
   if value.endswith('.sc'):files.update(path.parent.glob(path.stem+'_*.sctx'))
 # RuntimeAsset (scRuntimeAssetMovieClip etc.) resolves a file GUID to attachments/<hash>.scattachment = {u32 1,u32,u32 len,path,SC data}; without it lookup returns null (deco_flagpole_base -> sc/building_bases.sc). Add attachments for files already packed.
 packed={str(p.relative_to(source)) for p in files}
 for p in (source/'attachments').glob('*.scattachment'):
  head=p.open('rb').read(512);n=int.from_bytes(head[8:12],'little')
  path=head[12:12+n].decode(errors='replace')
  # also attachment-only content (sc/decos.sc + decos_*.sctx, sfx), 45 MB without _dl: no loose copy exists in this version
  if path in packed or ('_dl' not in path and not (source/path).exists()):files.add(p)
 # Every skin is packed. --drop-skins leaves out the skins listed in skin-drop.json (only their exclusive files) to keep a single APK
 # small; the app then does not offer those skins (assets/skin-drop.json).
 if '--drop-skins' in sys.argv:
  drop=json.loads((PROJECT/'skin-drop.json').read_text());files-={source/f for f in drop['drop_files']}
 # Home assets ship in a separate split APK (installed once); base APK keeps code/tables so code iterations install fast (install-multiple -p).
 split_files=sorted(files)
 manifest={'files':[str(item.relative_to(source)) for item in sorted(files)],'bytes':sum(item.stat().st_size for item in files),'missing':missing}
 (PROJECT/'home-assets-manifest.json').write_text(json.dumps(manifest,indent=2))
 print('Home assets:',len(files),'files;',manifest['bytes'],'bytes; missing:',missing,flush=True)
registry=json.loads((ROOT/'local-lab/table-registry.json').read_text())['entries']
entries=[x for x in registry if x.get('filename')]+[{'index':4,'filename':'csv/animations.csv'},{'index':54,'filename':'logic/upgrade_tasks.csv'}]
(PROJECT/'jni/tables.h').write_text('struct TableFile {int index;const char* filename;};\nstatic TableFile tables[]={'+','.join('{'+str(x['index'])+','+json.dumps(x['filename'])+'}' for x in entries)+'};\n')
for x in entries:
 dest=BUILD/'assets'/'decoded'/x['filename'];dest.parent.mkdir(parents=True,exist_ok=True);src=ROOT/'tables-current'/x['filename'];dest.write_bytes(src.read_bytes() if src.exists() else decode((ROOT/'assets-current'/x['filename']).read_bytes()))
 canonical=BUILD/'assets'/x['filename'];canonical.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/'assets-current'/x['filename'],canonical)
# Offline table rules, repacked as unsigned LZMA (same container the client decodes):
# all super troops active at once (MAX_ACTIVE_SUPER_LICENCES 2 -> 17); deferred (_dl) files are read like regular
# files from update/ instead of waiting for the content downloader, which never starts without a server login.
def patch_table(name,old_line,new_line):
 import lzma
 path=BUILD/'assets'/name;text=decode(path.read_bytes()).decode('utf-8')
 assert old_line in text,old_line+' row not found'
 data=text.replace(old_line,new_line).encode('utf-8')
 raw=lzma.compress(data,format=lzma.FORMAT_ALONE,filters=[{'id':lzma.FILTER_LZMA1,'dict_size':262144,'lc':3,'lp':0,'pb':2}])
 path.write_bytes(raw[:5]+len(data).to_bytes(4,'little')+raw[13:])
 (BUILD/'assets'/'decoded'/name).write_bytes(data)
patch_table('logic/globals.csv','"MAX_ACTIVE_SUPER_LICENCES",2,','"MAX_ACTIVE_SUPER_LICENCES",17,')
# Sandbox village without obstacles: the client refills the village up to OBSTACLE_COUNT_MAX trees/stones (also on load) and respawns
# one every OBSTACLE_RESPAWN_SECONDS; no obstacles at all and practically no respawn.
patch_table('logic/globals.csv','"OBSTACLE_COUNT_MAX",45,','"OBSTACLE_COUNT_MAX",0,')
patch_table('logic/globals.csv','"OBSTACLE_RESPAWN_SECONDS",28800,','"OBSTACLE_RESPAWN_SECONDS",2000000000,')
# Bonus gem box: appears every AppearancePeriodHours (168) at least MinRespawnTimeHours (24) apart -> about once per 100 years.
patch_table('logic/obstacles.csv','"Clear Bonus Gems","Chest Pickup",0,,"FALSE",,1,168,24,','"Clear Bonus Gems","Chest Pickup",0,,"FALSE",,1,876000,876000,')
patch_table('csv/client_globals.csv','"DEFERRED_FILE_DOWNLOAD_ENABLED",,"TRUE"','"DEFERRED_FILE_DOWNLOAD_ENABLED",,"FALSE"')
for src in (ROOT/'assets-current').rglob('*.json'):
 dest=BUILD/'assets'/src.relative_to(ROOT/'assets-current');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dest)
# Procedural overlays use standalone image textures, not the SC atlases.
# In particular: defense range rings and the Air Sweeper's air wave/shadow.
for src in (ROOT/'assets-current/image').iterdir():
 if src.is_file():
  dest=BUILD/'assets/image'/src.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dest)
shutil.copytree(ROOT/'assets-current/shaders',BUILD/'assets/shaders',dirs_exist_ok=True)
for name in ['data/assetdependencies.flat','sc/buildings_0.sctx','sc/buildings2.sc','sc/buildings_cc.sc','csv/assets.csv','fontawesome-webfont.ttf','sc/loading.sc','localization/texts.csv','localization/texts_patch.csv','localization/ru.csv']:
 dest=BUILD/'assets'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/'assets-current'/name,dest)
shutil.copytree(ROOT/'assets-current/titan',BUILD/'assets/titan',dirs_exist_ok=True)
for name in ['assets.scdb','contentHash.txt','build.tag','khbieklakjmh.dat']:
 shutil.copyfile(ROOT/'assets-current'/name,BUILD/'assets'/name)
shutil.copytree(ROOT/'assets-current/font',BUILD/'assets/font',dirs_exist_ok=True)
# Sandbox shop art: sprites cut from the game's own UI atlases (shop.sc / ui.sc embedded ASTC textures) for the sandbox menu cards.
shutil.copytree(PROJECT/'shop-art',BUILD/'assets/sandbox-shop',dirs_exist_ok=True)
# On-device layout recognition: detector + class table from layout-recognition/export_mobile.py; ONNX Runtime from the official Android AAR.
ORT=WS/'work/onnxruntime-android/aar'
shutil.copytree(ROOT/'layout-recognition/mobile',BUILD/'assets/recognizer',dirs_exist_ok=True)
for name in ['csv/assets_logic.scindex','logic/decos_logic.scindex','logic/capital_decos_logic.scindex','logic/effects_logic.scindex','csv/particle_emitters_logic.scindex','data/assetdata.scindex']:
 dest=BUILD/'assets'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/'assets-current'/name,dest)
# The native addresses in probe.cpp belong to one libg build; a different libg (a game update) must be ported first (PORTING.md).
_pin=json.loads((PROJECT/'jni/libg-version.json').read_text())
if hashlib.sha256((ROOT/'native/libg.so').read_bytes()).hexdigest()!=_pin['libg_sha256'] and '--allow-other-libg' not in sys.argv:
 sys.exit('native/libg.so is not the game build '+_pin['game_version']+' the sandbox is made for: port the addresses first (PORTING.md), or pass --allow-other-libg to build the dump-only APK')
run([NDK/'bin/clang++','--target=aarch64-linux-android24','--sysroot='+str(NDK/'sysroot'),'-shared','-fPIC','-O2','-std=c++17','-static-libstdc++','-Wl,-z,max-page-size=16384',PROJECT/'jni/probe.cpp','-ldl','-llog','-landroid','-o',BUILD/'liboffline_probe.so'])
shutil.copyfile(ROOT/'tables-current/logic/resources.csv',BUILD/'assets/resources.csv')
shutil.copyfile(ROOT/'local-lab/capture/home-canonical.json',BUILD/'assets/home.json')
# Player avatar captured from the original client's own encoder (cd3a28): pack the 3163 write operations so probe.cpp can replay them into ClientAvatar::decode (cd5220).
# Record: u16 writer method; 0xa8: u8 present,i32,i32; 0x20/0x30/0x38: u8 present,u32 len,utf8; else i64.
def pack_avatar_ops():
 import struct as st
 ops=json.loads((ROOT/'local-lab/capture/avatar-native-operations.json').read_text())['operations'];out=bytearray(st.pack('<I',len(ops)))
 for op in ops:
  m=int(op['method'],16);out+=st.pack('<H',m)
  if m==0xa8:w=op['logic_long_words'];out+=st.pack('<Bii',1,*w) if w else st.pack('<Bii',0,0,0)
  elif m in (0x20,0x30,0x38):t=op.get('string');b=(t or '').encode('utf-8');out+=st.pack('<BI',t is not None,len(b))+b
  else:out+=st.pack('<q',op['value'])
 (BUILD/'assets/avatar-ops.bin').write_bytes(bytes(out))
pack_avatar_ops()

# Sandbox equipment catalog for OfflineTools: id, Russian name, hero, max level (character_items.csv + ru.csv); deprecated/UNUSED rows skipped.
def equipment_catalog():
 tr={}
 for row in csv.reader(io.StringIO(decode((ROOT/'assets-current/localization/ru.csv').read_bytes()).decode('utf-8','replace'))):
  if len(row)>1:tr[row[0]]=row[1]
 heroes={}
 for row in csv.reader(io.StringIO((ROOT/'tables-current/logic/heroes.csv').read_text(encoding='utf-8',errors='ignore'))):
  if row and row[0] and len(row)>3:heroes.setdefault(row[0],tr.get(next((v for v in row if v.startswith('TID_')),''),row[0]))
 r=list(csv.reader(io.StringIO((ROOT/'tables-current/logic/character_items.csv').read_text(encoding='utf-8',errors='ignore'))));ix={k:i for i,k in enumerate(r[0])}
 items=[];cur=None
 for x in r[2:]:
  if x and x[0]:cur={'id':90000000+len(items),'key':x[0],'name':tr.get(x[ix['TID']],x[0]),'hero':x[ix['AllowedCharacters']].strip(';'),'deprecated':x[ix['Deprecated']]=='TRUE','max':0};items.append(cur)
  if cur and x[ix['Level']].isdigit():cur['max']=max(cur['max'],int(x[ix['Level']]))
 out=[{'id':i['id'],'name':i['name'],'hero':heroes.get(i['hero'],i['hero']),'max':i['max']} for i in items if not i['deprecated'] and not i['key'].startswith('UNUSED') and i['max']>1]
 (BUILD/'assets/equipment.json').write_text(json.dumps(out,ensure_ascii=False))
equipment_catalog()
def unit_level_catalog():
 tr={row[0]:row[1] for row in csv.reader(io.StringIO(decode((ROOT/'assets-current/localization/ru.csv').read_bytes()).decode('utf-8','replace'))) if len(row)>1}
 out=[]
 for filename,group,prefix in [('characters','troops',4),('heroes','heroes',28),('pets','pets',73),('spells','spells',26)]:
  rows=list(csv.DictReader(io.StringIO((ROOT/f'tables-current/logic/{filename}.csv').read_text(encoding='utf-8'))))[1:]
  items=[];current=None
  for row in rows:
   if row['Name']:
    current={'id':int(row['GlobalID']) if row.get('GlobalID','').isdigit() else prefix*1000000+len(items),'group':group,'prefix':prefix,'name':tr.get(row.get('TID',''),row['Name']),'max':0,'skip':row.get('Deprecated')=='TRUE' or row['Name'].startswith('UNUSED'),'village':row.get('VillageType',''),'housing':int(row['HousingSpace']) if row.get('HousingSpace','').isdigit() else 0};items.append(current)
   if current:current['max']+=1
  out.extend({k:i[k] for k in ['id','group','prefix','name','max','village','housing']} for i in items if not i['skip'] and i['max']>0)
 (BUILD/'assets/unit-levels.json').write_text(json.dumps(out,ensure_ascii=False))
unit_level_catalog()
def building_guardian_catalog():
 tr={row[0]:row[1] for row in csv.reader(io.StringIO(decode((ROOT/'assets-current/localization/ru.csv').read_bytes()).decode('utf-8','replace'))) if len(row)>1}
 out=[]
 for filename,group,prefix,level_field in [('buildings','buildings',1,'BuildingLevel'),('guardians','guardians',107,'Level')]:
  rows=list(csv.DictReader(io.StringIO((ROOT/f'tables-current/logic/{filename}.csv').read_text(encoding='utf-8'))))[1:]
  items=[];current=None
  for row in rows:
   if row['Name']:
    current={'id':int(row['GlobalID']) if row.get('GlobalID','').isdigit() else prefix*1000000+len(items),'group':group,'name':tr.get(row.get('TID',''),row['Name']),'max':0,'merged':bool(row.get('MergeRequirement','')),'skip':row.get('Deprecated')=='TRUE'};items.append(current)
   if current:
    if group=='guardians':current['max']+=1
    elif row.get(level_field,'').isdigit():current['max']=max(current['max'],int(row[level_field]))
  out.extend({k:i[k] for k in ['id','group','name','max','merged']} for i in items if not i['skip'] and i['max']>0)
 (BUILD/'assets/building-guardian-levels.json').write_text(json.dumps(out,ensure_ascii=False))
building_guardian_catalog()
# Magic items (boosters.csv, ids 46000000 + row) and their storage limit MaxItems, kept full by the sandbox (probe.cpp refillMagicItems).
def magic_items():
 rows=list(csv.DictReader(io.StringIO((ROOT/'tables-current/logic/boosters.csv').read_text(encoding='utf-8'))))[1:]
 out=[];index=-1
 for r in rows:
  if not r['Name']:continue
  index+=1
  if r.get('MaxItems','').isdigit() and int(r['MaxItems'])>0:out.append([46000000+index,int(r['MaxItems'])])
 (BUILD/'assets/magic-items.json').write_text(json.dumps(out))
magic_items()
# Icons of the sandbox lists (assets/sandbox-icons/<data id>.webp): the game's own exports rendered from the SC2 files by tools/sc_icons.py
# (units, spells, heroes, pets, guardians, equipment: IconSWF/IconExportName; buildings: SWF/ExportName of the highest level).
# Rendered once into icon-cache/ (macOS: textures are decoded with ImageIO); later builds copy the cache.
def sandbox_icons():
 cache=PROJECT/'icon-cache';cache.mkdir(exist_ok=True);specs=[]
 def rows(name):return list(csv.DictReader(io.StringIO((ROOT/f'tables-current/logic/{name}.csv').read_text(encoding='utf-8'))))[1:]
 for filename,prefix in [('characters',4),('heroes',28),('pets',73),('spells',26),('guardians',107),('character_items',90),('buildings',1)]:
  index=-1;current=None;item_swf=None
  for row in rows(filename):
   if row['Name']:
    index+=1;current=int(row['GlobalID']) if row.get('GlobalID','').isdigit() else prefix*1000000+index;item_swf=None
   if current is None:continue
   swf,export=(row.get('SWF'),row.get('ExportName')) if filename=='buildings' else (row.get('IconSWF'),row.get('IconExportName'))
   item_swf=swf or item_swf;swf=item_swf  # level rows inherit the item's SWF
   if swf and export and swf.endswith('.sc'):
    specs=[s for s in specs if s[0]!=current] if filename=='buildings' else specs
    if filename=='buildings' or not any(s[0]==current for s in specs):specs.append((current,swf,export))
 missing=[f'{swf}:{export}={id}' for id,swf,export in specs if not (cache/f'{id}.webp').exists() and not (cache/f'{id}.fail').exists()]
 if missing:
  py=ROOT/'layout-recognition/.venv/bin/python'
  r=subprocess.run([py,'-I',PROJECT/'tools/sc_icons.py',ROOT/'assets-current',cache,'160',*missing],capture_output=True,text=True)
  for line in r.stdout.splitlines():
   if line.startswith('fail '):
    alias=next((m.split('=')[1] for m in missing if m.split('=')[0].split(':')[1]==line.split()[1]),None)
    if alias:(cache/f'{alias}.fail').write_text(line)
  print('Sandbox icons rendered:',r.stdout.count('\nok ')+r.stdout.startswith('ok '),'failed:',r.stdout.count('fail '),flush=True)
 out=BUILD/'assets/sandbox-icons';shutil.rmtree(out,ignore_errors=True);out.mkdir(parents=True)
 for p in cache.glob('*.webp'):shutil.copyfile(p,out/p.name)
sandbox_icons()
def scenery_catalog():
 rows=list(csv.DictReader(io.StringIO((ROOT/'tables-current/logic/village_backgrounds.csv').read_text(encoding='utf-8'))))
 items=[];index=0
 for row in rows[1:]:
  if not row['Name']:continue
  if row['HomeType']=='HOME':items.append({'id':60000000+index,'key':row['Name']})
  index+=1
 (BUILD/'assets/scenery.json').write_text(json.dumps(items,ensure_ascii=False))
scenery_catalog()
# Home-village trap sizes for the layout import (traps go to random free tiles): {data id: width}; builder-base traps (VillageType 1) left out.
def trap_sizes():
 rows=list(csv.DictReader(io.StringIO((ROOT/'tables-current/logic/traps.csv').read_text(encoding='utf-8'))))[1:];out={};index=0
 for row in rows:
  if not row['Name']:continue
  gid=int(row['GlobalID']) if row.get('GlobalID','').isdigit() else 12000000+index  # ids follow GlobalID, not the row order
  if row.get('VillageType','')!='1':out[str(gid)]=int(row['Width'] or 1)
  index+=1
 (BUILD/'assets/trap-sizes.json').write_text(json.dumps(out))
trap_sizes()
# Supercharge ("Суперзаряд", mini_levels.csv): number of phases per building data id (they apply to a max-level building only).
def mini_levels():
 L=ROOT/'tables-current/logic'
 rows=list(csv.DictReader(io.StringIO((L/'mini_levels.csv').read_text(encoding='utf-8'))))[1:];cur=None;count={};target={}
 for r in rows:
  if r['Name']:cur=r['Name'];target[cur]=r['TargetBuilding'];count[cur]=0
  count[cur]+=1
 ids={r['Name']:int(r['GlobalID']) for r in list(csv.DictReader(io.StringIO((L/'buildings.csv').read_text(encoding='utf-8'))))[1:] if r['Name'] and r.get('GlobalID','').isdigit()}
 (BUILD/'assets/mini-levels.json').write_text(json.dumps({str(ids[target[k]]):v for k,v in count.items() if target[k] in ids}))
mini_levels()
if '--drop-skins' in sys.argv:shutil.copyfile(PROJECT/'skin-drop.json',BUILD/'assets/skin-drop.json')
else:(BUILD/'assets/skin-drop.json').write_text(json.dumps({'drop_ids':[],'drop_files':[],'default_for':{}}))
# Town Hall profiles (sandbox "Ратуша" menu): for TH 1..18 the maximum level and allowed count of every building and trap, and the maximum
# level of every troop, spell, hero, pet and equipment item. Requirements: buildings/traps "TownHallLevel" per level; troops and spells
# the Laboratory level, pets the Pet House level, equipment the Blacksmith level, heroes "RequiredTownHallLevel" and the Hero Hall level.
# townhall_levels.csv lists counts per TH row, an empty cell keeps the previous row's count.
def th_profiles():
 L=ROOT/'tables-current/logic'
 def table(name):return list(csv.DictReader(io.StringIO((L/name).read_text(encoding='utf-8'))))[1:]
 def levels(rows,prefix,req):
  out={};cur=None;index=-1
  for r in rows:
   if r['Name']:
    index+=1;gid=int(r['GlobalID']) if r.get('GlobalID','').isdigit() else prefix*1000000+index;cur=out.setdefault(gid,{'name':r['Name'],'village':r.get('VillageType',''),'levels':[]})
   if cur is not None:cur['levels'].append(r)
  return out
 def max_level(item,ok):
  best=0
  for n,r in enumerate(item['levels'],1):
   if ok(r):best=n
  return best
 num=lambda v:int(v) if str(v).strip().lstrip('-').isdigit() else 0
 buildings=levels(table('buildings.csv'),1,'TownHallLevel');traps=levels(table('traps.csv'),12,'TownHallLevel')
 byname={v['name']:k for k,v in buildings.items()};trapname={v['name']:k for k,v in traps.items()}
 def bmax(name,th):return max_level(buildings[byname[name]],lambda r:num(r.get('TownHallLevel'))<=th)
 counts={};last={}
 for row in table('townhall_levels.csv'):
  th=num(row['Name'])
  for col,val in row.items():
   if col in byname or col in trapname:
    if str(val).strip():last[col]=num(val)
    counts.setdefault(th,{})[col]=last.get(col,0)
 units=[(levels(table('characters.csv'),4,''),'LaboratoryLevel','Laboratory'),(levels(table('spells.csv'),26,''),'LaboratoryLevel','Laboratory'),
        (levels(table('pets.csv'),73,''),'LaboratoryLevel','Pet House'),(levels(table('character_items.csv'),90,''),'RequiredBlacksmithLevel','Blacksmith')]
 heroes=levels(table('heroes.csv'),28,'')
 out={}
 for th in range(1,19):
  prof={'buildings':{},'traps':{},'units':{}}
  for gid,item in buildings.items():
   if item['village']=='1':continue
   m=max_level(item,lambda r:num(r.get('TownHallLevel'))<=th)
   if item['name']=='Town Hall':m=th  # Town Hall rows list the previous level as requirement
   prof['buildings'][str(gid)]=[m,counts.get(th,{}).get(item['name'],-1)]  # -1: no count limit listed (e.g. Town Hall)
  for gid,item in traps.items():
   if item['village']=='1':continue
   prof['traps'][str(gid)]=[max_level(item,lambda r:num(r.get('TownHallLevel'))<=th),counts.get(th,{}).get(item['name'],-1)]
  for table_items,req,building in units:
   cap=bmax(building,th)
   for gid,item in table_items.items():
    if item['village']=='1':continue
    prof['units'][str(gid)]=max_level(item,lambda r:0<num(r.get(req))<=cap or (req=='RequiredBlacksmithLevel' and not str(r.get(req)).strip() and cap>0))
  hall=bmax('Hero Hall',th)
  for gid,item in heroes.items():
   if item['village']=='1':continue
   prof['units'][str(gid)]=max_level(item,lambda r:num(r.get('RequiredTownHallLevel'))<=th and num(r.get('RequiredHeroTavernLevel'))<=hall)
  out[str(th)]=prof
 # Merged buildings (Multi Archer Tower, Ricochet Cannon, ...): MergeRequirement "Name:level:gearup;Name:level:gearup" -> the source building
 # ids, so a TH without the merged building gets its two sources back; footprint sizes for placing them.
 rows=table('buildings.csv');merge={};sizes={}
 for r in rows:
  if r['Name'] and r.get('GlobalID','').isdigit():
   sizes[r['GlobalID']]=num(r.get('Width')) or 1
   if r.get('MergeRequirement'):merge[r['GlobalID']]=[byname[part.split(':')[0]] for part in r['MergeRequirement'].split(';') if part.split(':')[0] in byname]
 out['merge']=merge;out['sizes']=sizes
 (BUILD/'assets/th-profiles.json').write_text(json.dumps(out,separators=(',',':')))
th_profiles()
# Siege machine ids for army links (siege machines have their own army slot): characters produced by the Siege Workshop.
(BUILD/'assets/siege-ids.json').write_text(json.dumps([int(r['GlobalID']) for r in list(csv.DictReader(io.StringIO((ROOT/'tables-current/logic/characters.csv').read_text(encoding='utf-8'))))[1:] if r['Name'] and r.get('ProductionBuilding')=='Siege Workshop' and r.get('GlobalID','').isdigit()]))
key=BUILD/'lab.keystore'
if not key.exists():run([JAVA/'keytool','-genkeypair','-keystore',key,'-storepass','offline-lab','-keypass','offline-lab','-alias','offline-lab','-dname','CN=Local Offline Lab','-keyalg','RSA','-validity','3650'])
def build_split():
 base_paths={str(p.relative_to(BUILD/'assets')) for p in (BUILD/'assets').rglob('*') if p.is_file()}
 chosen=[f for f in split_files if str(f.relative_to(source)) not in base_paths]
 stamp=hashlib.sha256('\n'.join(f'{f.relative_to(source)}:{f.stat().st_size}:{int(f.stat().st_mtime)}' for f in chosen).encode()).hexdigest()
 stamp_file=PROJECT/'offline-split-homeassets.stamp'
 if SPLIT_APK.exists() and stamp_file.exists() and stamp_file.read_text()==stamp:print('Split APK unchanged, reuse',SPLIT_APK,flush=True);return
 out=BUILD/'split_assets';shutil.rmtree(out,ignore_errors=True)
 for f in chosen:
  t=out/f.relative_to(source);t.parent.mkdir(parents=True,exist_ok=True)
  try:os.link(f,t)
  except OSError:shutil.copyfile(f,t)
 (BUILD/'split_manifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="lab.coc.offline" split="homeassets" android:versionCode="1" android:versionName="18.647.5"><application android:hasCode="false"/></manifest>')
 run([TOOLS/'aapt2','link','-o',BUILD/'split.apk','--manifest',BUILD/'split_manifest.xml','-I',SDK/'platforms/android-36/android.jar','-A',out])
 run([TOOLS/'zipalign','-f','-p','4',BUILD/'split.apk',BUILD/'split-aligned.apk'])
 run([TOOLS/'apksigner','sign','--ks',key,'--ks-pass','pass:offline-lab','--out',SPLIT_APK,BUILD/'split-aligned.apk'])
 stamp_file.write_text(stamp);print('Split APK built:',SPLIT_APK,len(chosen),'files',flush=True)
if split_files is not None:build_split()
# Launcher icon: the original game's adaptive icon layers (res/), compiled and linked into the base APK.
shutil.rmtree(BUILD/'res_compiled',ignore_errors=True);(BUILD/'res_compiled').mkdir()
run([TOOLS/'aapt2','compile','--dir',PROJECT/'res','-o',BUILD/'res_compiled/res.zip'])
run([TOOLS/'aapt2','link','-o',BUILD/'base.apk','--manifest',PROJECT/'AndroidManifest.xml','-I',SDK/'platforms/android-36/android.jar','-A',BUILD/'assets',BUILD/'res_compiled/res.zip'])
run([JAVA/'javac','-source','8','-target','8','-bootclasspath',str(SDK/'platforms/android-36/android.jar')+os.pathsep+str(TOOLS/'core-lambda-stubs.jar'),'-cp',ORT/'classes.jar','-d',BUILD/'classes',*(PROJECT/'src').rglob('*.java')])
run([TOOLS/'d8','--lib',SDK/'platforms/android-36/android.jar','--min-api','24','--output',BUILD/'dex',*(BUILD/'classes').rglob('*.class'),ORT/'classes.jar'])
with zipfile.ZipFile(BUILD/'base.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
 z.write(BUILD/'dex/classes.dex','classes.dex');z.write(BUILD/'liboffline_probe.so','lib/arm64-v8a/liboffline_probe.so')
 for name in ['libonnxruntime.so','libonnxruntime4j_jni.so']:z.write(ORT/'jni/arm64-v8a'/name,'lib/arm64-v8a/'+name)
 for name in ['libsupercell_clashofclans.so','libscid_sdk.so','libfmod.so','libsentry-android.so','libsentry.so','libc++_shared.so']:z.write(ROOT/'native'/name,'lib/arm64-v8a/'+name)
 blob=bytearray((ROOT/'deep/native-code-restored/libg.so').read_bytes())
 # ELF PT_LOAD virtual address to file offset; preserve original evidence untouched.
 assert hashlib.sha256(blob).hexdigest()=='7864a07552e76ab4ea7af8d8110ca015b7d0ba427f6d25114ad7dec494ba8d36'
 patches=json.loads((ROOT/'local-lab/static-initializer-patches.json').read_text())['patches']+[{'offset':0x10889d0,'bytes':'fd7bbda9'},{'offset':0x10c0e54,'bytes':'fd7bbea9'},{'offset':0xf95a10,'bytes':'1f2003d5'}]
 phoff=struct.unpack_from('<Q',blob,32)[0];phsize,phnum=struct.unpack_from('<HH',blob,54)
 for i in range(phnum):
  off=phoff+i*phsize;typ,flags,fileoff,va,pa,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',blob,off)
  if typ==2:
   for dynamic in range(fileoff,fileoff+filesz,16):
    tag,value=struct.unpack_from('<QQ',blob,dynamic)
    if tag==12:struct.pack_into('<Q',blob,dynamic+8,0xe49608)
  if typ==1:
   for patch in patches:
    address=patch['offset']
    if va<=address<va+filesz:blob[fileoff+address-va:fileoff+address-va+4]=bytes.fromhex(patch['bytes'])
 z.writestr('lib/arm64-v8a/libg_headless.so',blob)
 z.write(ROOT/'native/libg.so','lib/arm64-v8a/libg.so')
run([TOOLS/'zipalign','-f','-p','4',BUILD/'base.apk',BUILD/'aligned.apk'])
run([TOOLS/'apksigner','sign','--ks',key,'--ks-pass','pass:offline-lab','--out',PROJECT/'offline-lab.apk',BUILD/'aligned.apk'])
run([TOOLS/'apksigner','verify',PROJECT/'offline-lab.apk'])
print(PROJECT/'offline-lab.apk')
