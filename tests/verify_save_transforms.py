"""Run actual Java save-selection and layout-transform methods on local fixtures.

The small JSON containers below stand in for Android's org.json API on the host.
No game launch, phone writes or third-party dependencies are required.
"""
from pathlib import Path
import json
import subprocess
import tempfile

project = Path(__file__).resolve().parents[1]
workspace = project.parents[2]
source = (project / 'src/lab/coc/offline/OfflineTools.java').read_text()


def method(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def literal(value):
    if isinstance(value, dict):
        return 'new JSONObject()' + ''.join('.put(%s,%s)' % (json.dumps(k), literal(v)) for k, v in value.items())
    if isinstance(value, list):
        return 'array(' + ','.join(literal(v) for v in value) + ')'
    if value is None:
        return 'null'
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, int):
        return str(value) + 'L'
    return json.dumps(value, ensure_ascii=True)


save = project / 'backups/2026-10-25-before-codex'
home = json.loads((save / 'save-home.json').read_text())
catalog = json.loads((workspace / 'work/android-offline-build/assets/unit-levels.json').read_text())
arrays = ['buildings', 'traps', 'decos', 'heroFlags']
fixture = {k: home.get(k, [])[:4] for k in arrays}
fixture['buildings'] += [b for b in home['buildings'] if b.get('data') in (1000028, 1000089, 1000084, 1000085, 1000079)]
fixture['buildings2'] = home.get('buildings2', [])[:2]
fixture['guardian_manager'] = home['guardian_manager']
for fields in [
    {'attack_mode': True, 'attack_mode5': False, 'attack_mode_weapon': 1, 'attack_mode5_weapon': 2},
    {'attack_mode': False, 'attack_mode5': True, 'attack_mode_weapon': False, 'attack_mode5_weapon': True},
    {'attack_mode': 0, 'attack_mode5': 2},
]:
    fixture['buildings'].append({'x': 1, 'y': 2, 'l5x': [3, 4], **fields})
fixture.update(active_layout=3, war_layout=5, selected_defensive_layout=4)
java = '''import java.util.*;import java.nio.*;import java.nio.file.*;
class TransformCheck {
static class JSONObject extends LinkedHashMap<String,Object> {
 public JSONObject put(String key,Object value){super.put(key,value);return this;}
 public boolean has(String key){return containsKey(key);}
 public int getInt(String key){return ((Number)get(key)).intValue();}
 public long optLong(String key,long fallback){return get(key) instanceof Number?((Number)get(key)).longValue():fallback;}
 public int optInt(String key,int fallback){return get(key) instanceof Number?((Number)get(key)).intValue():fallback;}
 public JSONObject optJSONObject(String key){return get(key) instanceof JSONObject?(JSONObject)get(key):null;}
 public JSONArray optJSONArray(String key){return get(key) instanceof JSONArray?(JSONArray)get(key):null;}
}
static class JSONArray extends ArrayList<Object> {
 public int length(){return size();}
 public JSONObject getJSONObject(int i){return (JSONObject)get(i);}
 public int getInt(int i){return ((Number)get(i)).intValue();}
}
static JSONArray array(Object... values){JSONArray a=new JSONArray();Collections.addAll(a,values);return a;}
private static class Op {int method;int offset;long value;}
private static final String[] LAYOUT_ARRAYS={"buildings","traps","decos","heroFlags"};
'''
for signature in ['private static List<Op> parseOps(', 'private static int findLevelList(',
                  'private static void setValue(', 'private static int[] position(',
                  'private static JSONObject findBuilding(', 'private static void setBuildingLevel(',
                  'private static void setGuardianLevel(',
                  'private static void normalizeAttackLayout(']:
    java += method(signature) + '\n'
java += 'static JSONObject fixture(){return ' + literal(fixture) + ';}\n'
java += '''public static void main(String[] args)throws Exception {
 byte[] data=Files.readAllBytes(Paths.get(args[0]));List<Op> ops=parseOps(data);
'''
expected_counts = {'troops': 51, 'heroes': 8, 'pets': 12, 'spells': 17}
for group in expected_counts:
    java += '{Map<Long,Integer> limits=new HashMap<>();\n'
    for item in catalog:
        if item['group'] == group:
            java += f'limits.put({item["id"]}L,{item["max"]});\n'
    java += f'''int at=findLevelList(ops,limits);
if(ops.get(at).value!={expected_counts[group]})throw new AssertionError("wrong {group} list");
Op level=ops.get(at+2);byte[] edited=data.clone();setValue(edited,level,0);parseOps(edited);
for(int i=0;i<data.length;i++)if((i<level.offset||i>=level.offset+8)&&data[i]!=edited[i])throw new AssertionError("unrelated field changed");
System.out.println("{group}: correct list; edit isolated to target level");}}
'''
java += '''
for(int slot:new int[]{0,1,2,3,4,5,8,9,10}) {
 JSONObject before=fixture(),after=fixture();normalizeAttackLayout(after,slot);
 for(String key:LAYOUT_ARRAYS) {
  JSONArray a=before.optJSONArray(key),b=after.optJSONArray(key);
  for(int i=0;i<a.length();i++) {
   JSONObject original=a.getJSONObject(i),actual=b.getJSONObject(i);
   int[] wanted=position(original,slot);int x=wanted==null?-1:wanted[0],y=wanted==null?-1:wanted[1];
   if(actual.getInt("x")!=x||actual.getInt("y")!=y)throw new AssertionError(key+" wrong slot coordinates "+slot);
   if(slot!=0) {
    String suffix=slot==1?"_war":String.valueOf(slot);
    if(original.has("aim_angle")) {
     int wantedAngle=original.has("aim_angle"+suffix)?original.getInt("aim_angle"+suffix):0;
     if(actual.getInt("aim_angle")!=wantedAngle)throw new AssertionError("wrong defense direction");
    }
    for(String weapon:new String[]{"","_weapon"})if(original.has("attack_mode"+weapon)) {
     String field="attack_mode"+suffix+weapon;
     Object mode=actual.get("attack_mode"+weapon);
     if(original.has(field)) {
      if(!Objects.equals(original.get(field),mode))throw new AssertionError("defense mode value/type changed");
     } else if(original.get("attack_mode"+weapon) instanceof Boolean) {
      if(!Boolean.FALSE.equals(mode))throw new AssertionError("wrong boolean default");
     } else if(!(mode instanceof Number)||((Number)mode).intValue()!=0)throw new AssertionError("wrong numeric default");
    }
   }
   if(slot!=0)for(String field:new String[]{"m_defendingHero","m_defendingHeroDraft","m_currentGuardians","m_currentGuardiansDraft"}) {
    if(!Objects.equals(original.get(field+"_"+slot),actual.get(field+"_0")))throw new AssertionError("defender assignment mismatch");
   }
  }
 }
 if(after.getInt("active_layout")!=0||after.getInt("war_layout")!=0||after.getInt("selected_defensive_layout")!=0)throw new AssertionError("mixed layout selection");
}
System.out.println("Nine layout slots: building, trap, decoration, hero flag positions and defender assignments consistent");
System.out.println("Boolean true/false and numeric defense modes retained, including attack_mode5=false regression");
{
 JSONObject h=fixture();JSONArray buildings=h.optJSONArray("buildings");
 for(int i=0;i<buildings.length();i++) {
  JSONObject building=buildings.getJSONObject(i);if(!building.has("id")||!building.has("data"))continue;
  Map<String,Object> previous=new LinkedHashMap<>(building);
  int count=buildings.length();setBuildingLevel(h,building.optLong("id",-1),building.optLong("data",-1),1,100);
  if(building.getInt("lvl")!=0||buildings.length()!=count)throw new AssertionError("building level/unmerge failure");
  building.put("lvl",previous.get("lvl"));if(!previous.equals(building))throw new AssertionError("building identity, merge or layout changed");
 }
 JSONObject other=h.optJSONArray("buildings2").getJSONObject(0);Object level=other.get("lvl");
 JSONObject town=buildings.getJSONObject(0);
 setBuildingLevel(h,town.optLong("id",-1),town.optLong("data",-1),1,18);
 if(!Objects.equals(level,other.get("lvl")))throw new AssertionError("cross-village instance ID collision");
 for(int invalid:new int[]{0,19}) {
  try{setBuildingLevel(h,town.optLong("id",-1),town.optLong("data",-1),invalid,18);throw new AssertionError("bad level accepted");}
  catch(IllegalArgumentException expected){}
 }
 JSONObject manager=h.optJSONObject("guardian_manager");JSONArray guardians=manager.optJSONArray("m_pGuardians");
 for(int i=0;i<guardians.length();i++) {
  JSONObject guardian=guardians.getJSONObject(i);Map<String,Object> previous=new LinkedHashMap<>(guardian);
  setGuardianLevel(h,guardian.optLong("m_pGuardian",-1),1,5);
  if(guardian.getInt("m_pGuardianUpgradeLevel")!=0)throw new AssertionError("guardian level index");
  guardian.put("m_pGuardianUpgradeLevel",previous.get("m_pGuardianUpgradeLevel"));
  if(!previous.equals(guardian))throw new AssertionError("guardian metadata changed");
 }
 System.out.println("Building/merged building and guardian edits preserve identity, metadata and coordinates; village IDs disambiguated");
}
}}
'''
with tempfile.TemporaryDirectory(prefix='coc-transform-check-') as directory:
    path = Path(directory) / 'TransformCheck.java'
    path.write_text(java)
    subprocess.run(['/opt/homebrew/opt/openjdk/bin/javac', str(path)], check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk/bin/java', '-cp', directory, 'TransformCheck', str(save / 'save-avatar.bin')], check=True)
