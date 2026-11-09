"""Generate the native method declarations observed in this exact game's Java ABI."""
from pathlib import Path
import json,re,collections
P=Path(__file__).resolve().parent;groups=collections.defaultdict(list)
for item in json.loads((P.parent/'deep/java-native-interface.json').read_text()):groups[item['class']].append(re.sub(r'@\w+\s*','',item['declaration']))
for full,decls in groups.items():
 package,name=full.rsplit('.',1);path=P/'src'/package.replace('.','/')/(name+'.java');path.parent.mkdir(parents=True,exist_ok=True)
 if path.exists():continue  # Preserve hand-restored platform methods.
 extra='';parent=''
 if name=='GameApp':
  parent=' extends android.app.Activity';extra='''
 public static GameApp instance;
 public static GameApp getInstance(){return instance;}
 public static class NotificationData {public String actionId,channelId,chronosEventId,message,notificationId,title;public int deliveryErrorCode,source;}
 public String getBuildConfigClassName(){return "lab.coc.offline.BuildConfig";}
 public String getStringResourceByName(String name){return "";}
 public String getGameDataKey(){return "offline-local";}
 '''
 elif name=='TitanApplication':parent=' extends android.app.Application'
 elif name=='PurchaseManager':extra='public static class ProductInfo {}'
 path.write_text('package '+package+';\nimport android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;\npublic class '+name+parent+' {\n'+extra+'\n'+'\n'.join(decls)+'\n}\n')
