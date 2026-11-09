#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <sys/mman.h>
#include <unistd.h>
#include <string>
#include <ctime>
#include <vector>
#include <map>
#include <atomic>
#include <mutex>
#include <cstdarg>
#include <signal.h>
#include <ucontext.h>
#include <sys/uio.h>
#include <fcntl.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include "tables.h"
using P=uintptr_t;
static P base;
static P retainedHome,retainedAvatar,retainedManager,retainedMode;
static int crashFD=-1;
static P rendererBase;
static AAssetManager* localHomeAssets;
static bool localHomeRequested;
static std::string filesDir;
// Submit local home after graphics workers have loaded SC files referenced by assetdata.scindex (frame 180 raced them: RuntimeAsset lookup returned null).
static const int LOCAL_HOME_FRAME=600;  // latest frame for the local home submit (fallback)
// The local home is submitted as soon as the original frontend reached mode 3 (start-up finished, waiting for the server), at the
// earliest after LOCAL_HOME_MIN_FRAME; before, every start waited the fixed 600 frames (about 10 s). submitFrame = frame of the submit.
static const int LOCAL_HOME_MIN_FRAME=180;static const int LOCAL_HOME_IDLE_FRAMES=45;static int submitFrame=-1;
// Start-up readiness signals (diagnostic for an adaptive submit): frame of the first "connection error" dialog (Java bridge) and of
// every change of the original frontend mode.
static std::atomic<int> connectionErrorFrame{-1};static int frameNow;
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_RenderActivity_onConnectionError(JNIEnv*,jclass){int none=-1;connectionErrorFrame.compare_exchange_strong(none,frameNow);}
static void recordAbort(int sig,siginfo_t* info,void* context){
 auto* uc=static_cast<ucontext_t*>(context);P sp=uc->uc_mcontext.sp,fp=uc->uc_mcontext.regs[29];char out[4096];int used=snprintf(out,sizeof(out),"signal=%d libg=%llx pc=%llx lr=%llx sp=%llx fault=%p\n",sig,(unsigned long long)rendererBase,(unsigned long long)uc->uc_mcontext.pc,(unsigned long long)uc->uc_mcontext.regs[30],(unsigned long long)sp,info->si_addr);
 if(crashFD>=0)write(crashFD,out,used);used=0;
 {Dl_info di{};for(P addr:{static_cast<P>(uc->uc_mcontext.pc),static_cast<P>(uc->uc_mcontext.regs[30])})if(dladdr(reinterpret_cast<void*>(addr),&di)&&di.dli_fname){used=snprintf(out,sizeof(out),"module %s +%llx sym=%s\n",di.dli_fname,(unsigned long long)(addr-reinterpret_cast<P>(di.dli_fbase)),di.dli_sname?di.dli_sname:"?");if(crashFD>=0)write(crashFD,out,used);used=0;}}
 // SC loader diagnostic: fault in 1141f20 (null stream) called from 1012b00; x22 holds raw filename (preserved across 11425ac).
 if(rendererBase && uc->uc_mcontext.pc==rendererBase+0x1141f24 && uc->uc_mcontext.regs[30]==rendererBase+0x1012b04){
  char name[257]={0};P src=uc->uc_mcontext.regs[22];size_t got=0;
  for(size_t i=0;i<256 && src;i++){struct iovec l{name+i,1},r{reinterpret_cast<void*>(src+i),1};if(process_vm_readv(getpid(),&l,1,&r,1,0)!=1)break;if(!name[i])break;got=i+1;}
  name[got<256?got:256]=0;used=snprintf(out,sizeof(out),"sc_open_failed x22=%llx name_len=%zu name=%s\n",(unsigned long long)src,got,name);
  if(crashFD>=0)write(crashFD,out,used);used=0;
 }
 // Second SC loader path (105f6bc -> 1141f20): filename is a ScString in x23 (length +4, inline/pointer +8).
 if(rendererBase && uc->uc_mcontext.pc==rendererBase+0x1141f24 && uc->uc_mcontext.regs[30]==rendererBase+0x105f6c0){
  P s=uc->uc_mcontext.regs[23];int32_t len=0;P ptr=0;char name[129]={0};struct iovec l{&len,4},r{reinterpret_cast<void*>(s+4),4};process_vm_readv(getpid(),&l,1,&r,1,0);
  if(len>0&&len<128){P src=s+8;if(len>=8){struct iovec l2{&ptr,8},r2{reinterpret_cast<void*>(s+8),8};process_vm_readv(getpid(),&l2,1,&r2,1,0);src=ptr;}struct iovec l3{name,static_cast<size_t>(len)},r3{reinterpret_cast<void*>(src),static_cast<size_t>(len)};process_vm_readv(getpid(),&l3,1,&r3,1,0);}
  used=snprintf(out,sizeof(out),"sc_open_failed2 name=%s\n",name);if(crashFD>=0)write(crashFD,out,used);used=0;
 }
 // 2D clip fallback: scget 1062d54(file=NULL,name,..) -> 10652ec(file+0xf8) faults at +106532c. Saved by 10652ec: caller x20 (name) at sp+0x40, x21 at sp+0x38; 8033c0's x20 (logic data) at sp+0xa0.
 if(rendererBase && uc->uc_mcontext.pc==rendererBase+0x106532c){
  auto rd=[](P src,void* dst,size_t n)->bool{struct iovec l{dst,n},r{reinterpret_cast<void*>(src),n};return src&&process_vm_readv(getpid(),&l,1,&r,1,0)==(ssize_t)n;};
  P sp=uc->uc_mcontext.sp,nm=0,a3=0,dt=0;rd(sp+0x40,&nm,8);rd(sp+0x38,&a3,8);rd(sp+0xa0,&dt,8);char n1[97]={0},n2[97]={0},n3[97]={0};rd(nm,n1,96);rd(a3,n2,96);P tid=0;rd(dt+0x28,&tid,8);rd(tid,n3,96);
  for(char* s:{n1,n2,n3})for(int i=0;i<96;i++)if(s[i]&&(s[i]<32||s[i]>126)){s[i]=0;break;}
  // 803e88 (vslot 3f8) -> ffe650(data+0x1a8 swf file, data+0x1b8 export). ScString: int cap at +4 (>7 => heap ptr at +8, else inline at +8).
  char sf[97]={0},ex[97]={0};for(int k=0;k<2;k++){char* dst=k?ex:sf;P s=dt+(k?0x1b8:0x1a8);int cap=0;P p=0;rd(s+4,&cap,4);if(cap>7){rd(s+8,&p,8);rd(p,dst,96);}else rd(s+8,dst,8);for(int i=0;i<96;i++)if(dst[i]&&(dst[i]<32||dst[i]>126)){dst[i]=0;break;}}
  used=snprintf(out,sizeof(out),"clip_file_missing name=%s arg3=%s data=%llx data_tid=%s swf=%s export=%s\n",n1,n2,(unsigned long long)dt,n3,sf,ex);
  if(crashFD>=0)write(crashFD,out,used);used=0;
 }
 // RuntimeAsset lookup diagnostic: fault in 10bcbe8 (null handle) from 8caf00; x20 = caller's sp, key {vtable,ptr} at +0x10. Dump key object and strings it points to.
 if(rendererBase && uc->uc_mcontext.pc==rendererBase+0x10bcbf8){
  auto rd=[](P src,void* dst,size_t n)->bool{struct iovec l{dst,n},r{reinterpret_cast<void*>(src),n};return src&&process_vm_readv(getpid(),&l,1,&r,1,0)==(ssize_t)n;};
  P x20=uc->uc_mcontext.regs[20],key[2]={0,0};rd(x20+0x10,key,16);
  used=snprintf(out,sizeof(out),"runtime_asset_missing x20=%llx keyvt=%llx(g+%llx) keyptr=%llx\n",(unsigned long long)x20,(unsigned long long)key[0],(unsigned long long)(key[0]-rendererBase),(unsigned long long)key[1]);
  // Lookup 10bcc9c: id = *keyptr; export name = libc++ string at id+0x18 (short: flag byte bit0=0, chars at +0x19; long: size +0x20, data +0x28).
  {P idp=0;unsigned char id[0x40]={0};char name[129]={0};if(rd(key[1],&idp,8)&&rd(idp,id,sizeof(id))){if(id[0x18]&1){P sz=*reinterpret_cast<P*>(id+0x20),dp=*reinterpret_cast<P*>(id+0x28);rd(dp,name,sz<128?sz:128);}else memcpy(name,id+0x19,id[0x18]>>1<23?id[0x18]>>1:22);
   used+=snprintf(out+used,sizeof(out)-used,"runtime_asset_export id=%llx name=%s idbytes8=%016llx\n",(unsigned long long)idp,name,*reinterpret_cast<unsigned long long*>(id+8));}}
  // Visual ctor 8033c0: param_1 (x19, saved by 8caf00 at fault sp+0x68) -> [1] logic object -> +0x18 logic data; data vslot +0x30 picks RuntimeAsset branch.
  {P self=0,logic=0,ldata=0,lvt=0,slot30=0;rd(uc->uc_mcontext.sp+0x68,&self,8);rd(self+8,&logic,8);rd(logic+0x18,&ldata,8);rd(ldata,&lvt,8);rd(lvt+0x30,&slot30,8);
   used+=snprintf(out+used,sizeof(out)-used,"visual_self=%llx logic=%llx data=%llx data_vt=g+%llx vslot30=g+%llx logic_vt=g+%llx\n",(unsigned long long)self,(unsigned long long)logic,(unsigned long long)ldata,(unsigned long long)(lvt-rendererBase),(unsigned long long)(slot30-rendererBase),(unsigned long long)(rd(logic,&lvt,8)?lvt-rendererBase:0));
   P dd[0x30];if(rd(ldata,dd,sizeof(dd)))for(int i=0;i<0x30&&used<2500;i++){char s[49]={0};P in2=0;if(rd(dd[i],s,48)){int k=0;while(k<48&&s[k]>=32&&s[k]<127)k++;if(k>=3){s[k]=0;used+=snprintf(out+used,sizeof(out)-used," d+%x\"%s\"",i*8,s);continue;}}if(rd(dd[i]+8,&in2,8)&&rd(in2,s,48)){int k=0;while(k<48&&s[k]>=32&&s[k]<127)k++;if(k>=3){s[k]=0;used+=snprintf(out+used,sizeof(out)-used," d+%x->\"%s\"",i*8,s);}}}
   if(used<4000)out[used++]='\n';}
  P obj[32];if(rd(key[1],obj,sizeof(obj)))for(int i=0;i<32&&used<3800;i++){char s[49]={0};used+=snprintf(out+used,sizeof(out)-used," +%02x=%llx",i*8,(unsigned long long)obj[i]);if(rd(obj[i],s,48)){int k=0;while(k<48&&s[k]>=32&&s[k]<127)k++;if(k>=4){s[k]=0;used+=snprintf(out+used,sizeof(out)-used,"\"%s\"",s);continue;}}
   P inner[4]={0,0,0,0};if(i<2&&rd(obj[i],inner,32))for(int j=0;j<4;j++)if(rd(inner[j],s,48)){int k=0;while(k<48&&s[k]>=32&&s[k]<127)k++;if(k>=4){s[k]=0;used+=snprintf(out+used,sizeof(out)-used,"[%d]->\"%s\"",j,s);}}}
  if(used<3900){used+=snprintf(out+used,sizeof(out)-used,"\n ascii=");const char* c=reinterpret_cast<const char*>(obj);for(int i=0;i<256&&used<4000;i++)out[used++]=(c[i]>=32&&c[i]<127)?c[i]:'.';}
  if(used<4090)out[used++]='\n';
  if(crashFD>=0)write(crashFD,out,used);used=0;
  // Visual ctor 8033c0 keeps the logic data object in x20; 8caf00 saved it at its sp+0x30 (= fault sp+0x60). Dump it with 2-level string probe to name the 3D entity.
  P data=0;rd(uc->uc_mcontext.sp+0x60,&data,8);P d[0x40];
  used=snprintf(out,sizeof(out),"visual_data=%llx vt=g+%llx\n",(unsigned long long)data,(unsigned long long)(rd(data,d,8)?d[0]-rendererBase:0));
  if(rd(data,d,sizeof(d)))for(int i=0;i<0x40&&used<3900;i++){char s[49]={0};P inner[2]={0,0};
   if(rd(d[i],s,48)){int k=0;while(k<48&&s[k]>=32&&s[k]<127)k++;if(k>=3){s[k]=0;used+=snprintf(out+used,sizeof(out)-used," +%x\"%s\"",i*8,s);continue;}}
   if(rd(d[i]+8,inner,8)&&rd(inner[0],s,48)){int k=0;while(k<48&&s[k]>=32&&s[k]<127)k++;if(k>=3){s[k]=0;used+=snprintf(out+used,sizeof(out)-used," +%x->\"%s\"",i*8,s);}}}
  if(used<4090)out[used++]='\n';
  if(crashFD>=0)write(crashFD,out,used);used=0;
 }
 for(int i=0;i<24 && fp>=sp && fp+16<sp+65536 && !(fp&15) && used<3500;i++){P* frame=reinterpret_cast<P*>(fp);used+=snprintf(out+used,sizeof(out)-used,"frame=%llx return=%llx relative_g=%llx\n",(unsigned long long)fp,(unsigned long long)frame[1],(unsigned long long)(frame[1]-rendererBase));P next=frame[0];if(next<=fp)break;fp=next;}
 if(crashFD>=0)write(crashFD,out,used);_exit(128+sig);
}

static FILE* progress;
static P q(P p){return *reinterpret_cast<P*>(p);}
template<typename... A> static P call(P offset,A... args){return reinterpret_cast<P(*)(A...)>(base+offset)(args...);}
static void stage(const std::string& message){if(progress){fprintf(progress,"%s\n",message.c_str());fflush(progress);}__android_log_print(ANDROID_LOG_INFO,"OfflineProbe","%s",message.c_str());}
static P block(size_t size){return reinterpret_cast<P>(calloc(1,size));}
// Instrument only this APK process to report the Java ABI requested by native code.
static const JNINativeInterface* originalJNI;
static jmethodID applicationInfoMethod;
static JNINativeInterface tracedJNI;
static const JNIInvokeInterface* originalVM;
static JNIInvokeInterface tracedVM;
static bool nativeTraceCaller(P caller){return !rendererBase||(caller>=rendererBase&&caller<rendererBase+0x18b8000);}
static jstring tracedNewString(JNIEnv* env,const char* value){if(nativeTraceCaller(reinterpret_cast<P>(__builtin_return_address(0)))&&value&&strlen(value)<512)stage(std::string("JNI startup string: ")+value);return originalJNI->NewStringUTF(env,value);}
static jclass tracedFindClass(JNIEnv* env,const char* name){jclass c=originalJNI->FindClass(env,name);if(nativeTraceCaller(reinterpret_cast<P>(__builtin_return_address(0))))stage(std::string(c?"JNI class ":"JNI MISSING CLASS ")+name);return c;}
static jmethodID tracedMethod(JNIEnv* env,jclass cls,const char* name,const char* signature){if(nativeTraceCaller(reinterpret_cast<P>(__builtin_return_address(0))))stage(std::string("JNI method ")+name+signature+(cls?"":" NULL CLASS"));jmethodID result=originalJNI->GetMethodID(env,cls,name,signature);if(!strcmp(name,"getApplicationInfo"))applicationInfoMethod=result;return result;}
static jmethodID tracedStaticMethod(JNIEnv* env,jclass cls,const char* name,const char* signature){if(nativeTraceCaller(reinterpret_cast<P>(__builtin_return_address(0))))stage(std::string("JNI static ")+name+signature+(cls?"":" NULL CLASS"));return originalJNI->GetStaticMethodID(env,cls,name,signature);}
static jobject tracedObjectV(JNIEnv* env,jobject object,jmethodID method,va_list args){
 if(method==applicationInfoMethod){va_list copy;va_copy(copy,args);jstring name=va_arg(copy,jstring);va_end(copy);if(name){const char* value=originalJNI->GetStringUTFChars(env,name,nullptr);if(value){stage(std::string("Package lookup: ")+value);originalJNI->ReleaseStringUTFChars(env,name,value);}}}
 jobject result=originalJNI->CallObjectMethodV(env,object,method,args);if(method==applicationInfoMethod)stage(std::string("Package lookup result: ")+(result?"object":"null")+(originalJNI->ExceptionCheck(env)?"; pending Java exception":""));return result;
}
static jfieldID tracedStaticField(JNIEnv* env,jclass cls,const char* name,const char* sig){if(nativeTraceCaller(reinterpret_cast<P>(__builtin_return_address(0))))stage(std::string("JNI static field ")+name+sig);return originalJNI->GetStaticFieldID(env,cls,name,sig);}
static void traceEnv(JNIEnv* env){if(env&&env->functions!=&tracedJNI)env->functions=&tracedJNI;}
static jint tracedAttach(JavaVM* vm,JNIEnv** env,void* args){jint r=originalVM->AttachCurrentThread(vm,env,args);if(r==JNI_OK)traceEnv(*env);return r;}
static jint tracedAttachDaemon(JavaVM* vm,JNIEnv** env,void* args){jint r=originalVM->AttachCurrentThreadAsDaemon(vm,env,args);if(r==JNI_OK)traceEnv(*env);return r;}
static jint tracedGetEnv(JavaVM* vm,void** env,jint version){jint r=originalVM->GetEnv(vm,env,version);if(r==JNI_OK&&version>=JNI_VERSION_1_1&&version<=JNI_VERSION_1_6)traceEnv(static_cast<JNIEnv*>(*env));return r;}
static void traceJava(JNIEnv* env,JavaVM* vm){if(originalJNI){traceEnv(env);return;}originalJNI=env->functions;tracedJNI=*originalJNI;tracedJNI.GetStaticFieldID=tracedStaticField;tracedJNI.NewStringUTF=tracedNewString;tracedJNI.CallObjectMethodV=tracedObjectV;tracedJNI.FindClass=tracedFindClass;tracedJNI.GetMethodID=tracedMethod;tracedJNI.GetStaticMethodID=tracedStaticMethod;originalVM=vm->functions;tracedVM=*originalVM;tracedVM.AttachCurrentThread=tracedAttach;tracedVM.AttachCurrentThreadAsDaemon=tracedAttachDaemon;tracedVM.GetEnv=tracedGetEnv;vm->functions=&tracedVM;traceEnv(env);}
static P readAsset(AAssetManager* assets,const char* name,size_t& length){
 AAsset* file=AAssetManager_open(assets,name,AASSET_MODE_BUFFER);if(!file)throw std::string("Missing asset: ")+name;
 length=AAsset_getLength64(file);P data=block(length+1);int n=AAsset_read(file,reinterpret_cast<void*>(data),length);AAsset_close(file);if(n!=length)throw std::string("Short asset: ")+name;return data;
}
struct ScString {int32_t capacity,length;union{char small[8];const char* pointer;};};
static P node(AAssetManager* assets,const char* name){size_t size;std::string decodedName=std::string("decoded/")+name;P data=readAsset(assets,decodedName.c_str(),size);ScString filename{};filename.capacity=-1;filename.length=strlen(name);if(filename.length<8)memcpy(filename.small,name,filename.length);else filename.pointer=name;P result=block(0x68);call(0x1087dd8,result,data,static_cast<int>(size),&filename);return result;}
static P allocate(P,size_t size,size_t alignment){void* result=nullptr;if(posix_memalign(&result,alignment<sizeof(void*)?sizeof(void*):alignment,size))return 0;memset(result,0,size);return reinterpret_cast<P>(result);}
static P offlineFeature(P,int id){stage("Offline feature "+std::to_string(id)+" = false");return 0;}
static void release(P,P p){free(reinterpret_cast<void*>(p));}
extern "C" JNIEXPORT jstring JNICALL Java_lab_coc_offline_MainActivity_probe(JNIEnv* env,jclass,jstring library,jobject assetObject,jstring progressPath){
 try{
 const char* progressName=env->GetStringUTFChars(progressPath,nullptr);progress=fopen(progressName,"w");env->ReleaseStringUTFChars(progressPath,progressName);
 AAssetManager* assets=AAssetManager_fromJava(env,assetObject);
 const char* path=env->GetStringUTFChars(library,nullptr);stage("dlopen begin");void* handle=dlopen(path,RTLD_NOW|RTLD_LOCAL);env->ReleaseStringUTFChars(library,path);
 if(!handle)throw std::string(dlerror());Dl_info info{};void* entry=dlsym(handle,"JNI_OnLoad");if(!entry||!dladdr(entry,&info))throw std::string("Missing library base");base=reinterpret_cast<P>(info.dli_fbase);stage("dlopen succeeded");
 P source=block(16);call(0xe4960c,source);P manager=q(source+8);*reinterpret_cast<P*>(base+0x1844678)=source;
 int csvCount=0;
 for(const auto& file:tables){stage("CSV "+std::to_string(file.index)+" "+file.filename);call(0xdf7edc,manager,node(assets,file.filename),file.index);csvCount++;}
 // The same nine serializers and allocator ABI used by the native asset owner.
 static P allocatorVT[]={reinterpret_cast<P>(&allocate),reinterpret_cast<P>(&release)};static P allocator=reinterpret_cast<P>(allocatorVT);
 P assetManager=block(0x1f8);call(0x10c585c,assetManager,reinterpret_cast<P>(&allocator));P modifyHandle=block(8);call(0x10c716c,modifyHandle,assetManager);P guard=block(80);call(0x10c7274,guard,modifyHandle,1,block(48));
 struct Loader {P getter,got;int size;};
 Loader loaders[]={{0xfe2f44,0x177fab0,16},{0xfe7690,0x177fab8,32},{0xfe98e4,0x177fac8,40},{0xfea870,0x177fad0,16},{0xfef1d8,0x177fad8,16}};
 for(auto l:loaders){P descriptor=q(call(l.getter)),loader=block(l.size);*reinterpret_cast<P*>(loader)=q(base+l.got)+16;if(l.size>16)*reinterpret_cast<P*>(loader+8)=q(base+0x177fac0)+16;if(l.size==40)*reinterpret_cast<P*>(loader+16)=q(base+0x177fab8)+16;P unique=block(8);*reinterpret_cast<P*>(unique)=loader;call(0x10c5b68,assetManager,unique,descriptor);}
 struct Core {P descriptor,name,factory,got;int size;};Core cores[]={{0x18369e8,0x21c4c9,0x7724ec,0x177fae0,8},{0x1836a10,0x220a71,0x772610,0x177fae8,16},{0x1836a58,0x28f944,0x772930,0x177faf0,8},{0x1836a80,0x2743d1,0x77293c,0x177faf8,8}};
 for(auto c:cores){call(0x10bfaac,base+c.descriptor,base+c.name,0,base+c.factory);P loader=block(c.size);*reinterpret_cast<P*>(loader)=q(base+c.got)+16;P unique=block(8);*reinterpret_cast<P*>(unique)=loader;call(0x10c5b68,assetManager,unique,base+c.descriptor);}
 P wrapper=block(16);*reinterpret_cast<P*>(wrapper)=assetManager;P arg=block(8);*reinterpret_cast<P*>(arg)=wrapper;P provider=block(16);call(0xe49688,provider,arg);call(0x108a8c0,provider);
 const char* indices[]={"csv/assets_logic.scindex","logic/decos_logic.scindex","logic/capital_decos_logic.scindex","logic/effects_logic.scindex","csv/particle_emitters_logic.scindex","data/assetdata.scindex"};
 for(const char* name:indices){stage(std::string("Index ")+name);size_t length;P data=readAsset(assets,name,length);P index=block(0x100);*reinterpret_cast<P*>(index)=q(base+0x1780ef0)+16;*reinterpret_cast<P*>(index+8)=assetManager;*reinterpret_cast<P*>(index+16)=base+0x16b8888;P context=block(24);*reinterpret_cast<P*>(context)=assetManager+0x90;*reinterpret_cast<P*>(context+8)=assetManager;*reinterpret_cast<P*>(context+16)=index;P stream=block(0x48);call(0x10c51f0,stream,context,data,length);if(!(call(0x10c8290,index,stream)&1)||q(stream+0x10)!=length)throw std::string("Index decode mismatch: ")+name;call(0x10c1690,assetManager+0x90,index,0);}
 for(P f:{0xdfa4ec,0xdf9e2c,0xdfaa3c,0xdfaf8c})call(f,manager);
 *reinterpret_cast<uint8_t*>(q(base+0x177fb00))=1;
 for(int i=0;i<119;i++){stage("Read "+std::to_string(i));call(0xdf80b0,manager,i,0);}
 // Minimal offline locale settings: default region, no JNI device discovery.
 P offlineSettings=block(0x310);*reinterpret_cast<uint8_t*>(offlineSettings+0x14a)=1;*reinterpret_cast<P*>(base+0x1832cb0)=offlineSettings;
 P offlineProvider=block(0x1300),offlineVT=block(0x100);*reinterpret_cast<P*>(offlineVT+0x78)=reinterpret_cast<P>(&offlineFeature);*reinterpret_cast<P*>(offlineProvider)=offlineVT;*reinterpret_cast<P*>(base+0x1846f48)=offlineProvider;
 for(int i=0;i<119;i++){stage("Link "+std::to_string(i));call(0xdf8140,manager,i,0);}
 stage("Finalize tables");call(0xdf81d4,manager,0);
 retainedManager=manager;
 int resourceCount=*reinterpret_cast<int*>(call(0xdf80a0,manager,2)+0x14);
 stage("Create ClientAvatar");P avatar=block(0x800);call(0xcd2cd4,avatar);
 stage("Create GameMode");P mode=block(0x98);call(0xf92500,mode);P game=q(mode+0x10);
 size_t homeSize;P homeRaw=readAsset(assets,"home.json",homeSize);ScString* homeJSON=reinterpret_cast<ScString*>(block(16));homeJSON->capacity=-1;homeJSON->length=homeSize;homeJSON->pointer=reinterpret_cast<const char*>(homeRaw);P home=block(0x78);call(0xefbb58,home);call(0xefbcb8,home,homeJSON);
 P calendar=block(16),events=block(16);call(0x11fce50,calendar);call(0x11fce50,events);call(0xfcb698,home+0x38,calendar);call(0xfcb698,home+0x50,events);
 stage("Load complete home state");call(0xf94734,mode,home,avatar,0,0,0,0,0,block(8),0);stage("Home state loaded, mode="+std::to_string(*reinterpret_cast<int*>(mode)));
 retainedHome=home;retainedAvatar=avatar;retainedMode=mode;
 std::string objectCounts;
 for(int villageOffset:{0x68,0x70}){P objects=q(game+villageOffset);objectCounts+="\nVillage "+std::to_string(villageOffset)+":";for(int i=0;i<11;i++){P list=q(objects+i*8);objectCounts+=" "+std::to_string(*reinterpret_cast<int*>(list+0xc));}}
 std::string result="Native Android bootstrap PASS\nCSV: "+std::to_string(csvCount)+"\nCompiled indices: 6\nTable read/link passes: 119/119\nResources: "+std::to_string(resourceCount)+objectCounts;stage(result);return env->NewStringUTF(result.c_str());
 }catch(const std::string& error){stage(error);return env->NewStringUTF(error.c_str());}
}

extern "C" JNIEXPORT jstring JNICALL Java_lab_coc_offline_MainActivity_battle(JNIEnv* env,jclass){
 if(!retainedHome)return env->NewStringUTF("Home bootstrap required");
 stage("Create NPC avatar");P npc=block(0x268);call(0xce6ed8,npc);P table=call(0xdf80a0,retainedManager,16);P npcData=q(q(table+8)+2*8);call(0xce7024,npc,npcData,0);
 stage("Create attack GameMode");P mode=block(0x98);call(0xf92500,mode);
 struct NativeUnique{P pointer;~NativeUnique(){}};
 stage("Clone player avatar");NativeUnique player=reinterpret_cast<NativeUnique(*)(P)>(base+0xf94534)(retainedMode);
 stage("Load NPC attack state");call(0xf95774,mode,retainedHome,npc,player.pointer,0,0);
 P game=q(mode+0x10);int before=*reinterpret_cast<int*>(game+0x60);
 P characters=call(0xdf80a0,retainedManager,3),barbarian=q(q(characters+8));call(0xcbedbc,player.pointer,0,barbarian,10);
 int deployResult=-999;int width=*reinterpret_cast<int*>(game+0x18),height=*reinterpret_cast<int*>(game+0x1c);
 stage("Try native PlaceAttacker command, map "+std::to_string(width)+"x"+std::to_string(height));
 for(int y=256;y<height&&deployResult!=0;y+=512){for(int x=256;x<width&&deployResult!=0;x+=512){P command=block(0x28);call(0xd7da40,command,barbarian,x,y,0,0);deployResult=static_cast<int>(call(0xd7da68,command,game));}}
 stage("PlaceAttacker result="+std::to_string(deployResult));
 P objectManager=q(game+0x68),units=q(objectManager+8);int unitsBefore=*reinterpret_cast<int*>(units+0xc);P firstUnit=0;for(int i=0;i<unitsBefore;i++){P unit=q(q(units)+i*8);if(q(unit+0x18)==barbarian){firstUnit=unit;break;}}
 int xBefore=firstUnit?*reinterpret_cast<int*>(firstUnit+0x20):0,yBefore=firstUnit?*reinterpret_cast<int*>(firstUnit+0x24):0;
 int hpBefore=firstUnit&&q(firstUnit+0x48)?*reinterpret_cast<int*>(q(firstUnit+0x48)+0x20):0;
 bool moved=false;int lastHP=hpBefore;stage("Run 2048 native battle ticks");
 for(int i=0;i<2048;i++){call(0xf93030,mode);int count=*reinterpret_cast<int*>(units+0xc);if(count>0){for(int j=0;j<count;j++){P unit=q(q(units)+j*8);if(unit==firstUnit){moved|=*reinterpret_cast<int*>(unit+0x20)!=xBefore||*reinterpret_cast<int*>(unit+0x24)!=yBefore;if(q(unit+0x48))lastHP=*reinterpret_cast<int*>(q(unit+0x48)+0x20);}}}}
 int unitsAfter=*reinterpret_cast<int*>(units+0xc);bool attackerPresent=false;for(int i=0;i<unitsAfter;i++)attackerPresent|=q(q(units)+i*8)==firstUnit;
 stage("Units "+std::to_string(unitsBefore)+" -> "+std::to_string(unitsAfter)+", moved="+std::to_string(moved)+", HP "+std::to_string(hpBefore)+" -> "+std::to_string(lastHP)+", attacker present="+std::to_string(attackerPresent));
 int after=*reinterpret_cast<int*>(game+0x60);
 std::string report="Native battle ticks: "+std::to_string(before)+" -> "+std::to_string(after)+"\nUnits: "+std::to_string(unitsBefore)+" -> "+std::to_string(unitsAfter)+", moved="+std::to_string(moved)+", HP="+std::to_string(hpBefore)+" -> "+std::to_string(lastHP)+", attacker present="+std::to_string(attackerPresent)+"\nPlaceAttacker result="+std::to_string(deployResult)+"\nNPC attack state returned. Mode="+std::to_string(*reinterpret_cast<int*>(mode));stage(report);return env->NewStringUTF(report.c_str());
}
// File trace (diagnostic): libc open/stat/access entry points are overwritten with a jump to a logger that performs the syscall itself.
#include <sys/syscall.h>
#include <sys/stat.h>
#include <cerrno>
static int traceFD=-1;
static bool tracedPath(const char* p){return p&&(strstr(p,"/update")||strstr(p,"_dl")||strstr(p,"skin")||strstr(p,"music"));}
// Start-up file activity: every hooked call counts (the client stats "update/<file>" before loading each asset); the local home is
// submitted once this count stops changing, i.e. when start-up loading is finished, on any device speed.
static std::atomic<long> fileCalls{0};
static void traceFile(const char* kind,const char* p,long r){fileCalls.fetch_add(1,std::memory_order_relaxed);if(traceFD<0||!tracedPath(p))return;char line[640];int n=snprintf(line,sizeof(line),"%s %s -> %ld\n",kind,p,r<0?-static_cast<long>(errno):r);syscall(SYS_write,traceFD,line,n);}
static int hOpenat(int d,const char* p,int f,int m){long r=syscall(SYS_openat,d,p,f|O_LARGEFILE,m);traceFile("openat",p,r);return r;}
static int hOpen(const char* p,int f,int m){return hOpenat(AT_FDCWD,p,f,m);}
static int hOpen2(const char* p,int f){return hOpenat(AT_FDCWD,p,f,0);}
static int hOpenat2(int d,const char* p,int f){return hOpenat(d,p,f,0);}
static int hFstatat(int d,const char* p,struct stat* b,int f){long r=syscall(SYS_newfstatat,d,p,b,f);traceFile("stat",p,r);return r;}
static int hStat(const char* p,struct stat* b){return hFstatat(AT_FDCWD,p,b,0);}
static int hLstat(const char* p,struct stat* b){return hFstatat(AT_FDCWD,p,b,AT_SYMLINK_NOFOLLOW);}
static int hFaccessat(int d,const char* p,int m,int f){long r=syscall(SYS_faccessat,d,p,m,f);traceFile("access",p,r);return r;}
static int hAccess(const char* p,int m){return hFaccessat(AT_FDCWD,p,m,0);}
static bool jumpTo(const char* name,void* target){
 P a=reinterpret_cast<P>(dlsym(RTLD_DEFAULT,name));if(!a)return false;P page=a&~static_cast<P>(4095);
 if(mprotect(reinterpret_cast<void*>(page),8192,PROT_READ|PROT_WRITE|PROT_EXEC))return false;
 uint32_t* i=reinterpret_cast<uint32_t*>(a);i[0]=0x58000050u;i[1]=0xd61f0200u;memcpy(i+2,&target,8);
 __builtin___clear_cache(reinterpret_cast<char*>(a),reinterpret_cast<char*>(a+16));mprotect(reinterpret_cast<void*>(page),8192,PROT_READ|PROT_EXEC);return true;
}
static void installFileTrace(bool log){
 if(log&&!filesDir.empty())traceFD=open((filesDir+"/file-trace.txt").c_str(),O_WRONLY|O_CREAT|O_TRUNC|O_APPEND,0600);
 std::string r="File trace:";
 struct H{const char* n;void* f;};H hs[]={{"openat",(void*)hOpenat},{"open",(void*)hOpen},{"open64",(void*)hOpen},{"__open_2",(void*)hOpen2},{"__openat_2",(void*)hOpenat2},{"fstatat",(void*)hFstatat},{"fstatat64",(void*)hFstatat},{"stat",(void*)hStat},{"stat64",(void*)hStat},{"lstat",(void*)hLstat},{"faccessat",(void*)hFaccessat},{"access",(void*)hAccess}};
 for(auto h:hs)r+=std::string(" ")+h.n+"="+(jumpTo(h.n,h.f)?"1":"0");stage(r);
}
// Porting aid (PORTING.md): with files/dump-libg.txt present, the decrypted libg (it decrypts its code when loaded) is copied from memory
// to files/runtime-code/libg.so.<file offset>.bin with manifest.json, before any sandbox hook touches it; the start then stops there.
// tools/libg_restore.py merges the copies into a decrypted libg for tools/libg_port.py.
static bool dumpLibgIfRequested(){
 if(filesDir.empty()||access((filesDir+"/dump-libg.txt").c_str(),F_OK)!=0)return false;
 unlink((filesDir+"/dump-libg.txt").c_str());std::string dir=filesDir+"/runtime-code";mkdir(dir.c_str(),0700);
 FILE* maps=fopen("/proc/self/maps","r");if(!maps)return false;std::string manifest="[";char line[512];
 while(fgets(line,sizeof line,maps)){unsigned long start,end,offset;char perms[8],path[400]={0};
  if(sscanf(line,"%lx-%lx %7s %lx %*s %*s %399s",&start,&end,perms,&offset,path)<4)continue;
  // libg may be mapped straight from the APK (path base.apk), so its regions are taken by address: executable mappings within 32 MB
  // of the library base; "va" is the address relative to that base (libg_restore.py turns it into a file offset).
  if(perms[2]!='x'||start<rendererBase||start>=rendererBase+0x2000000)continue;unsigned long va=start-rendererBase;(void)offset;
  std::string name="libg.so.0x"+[&]{char b[24];snprintf(b,sizeof b,"%lx",va);return std::string(b);}()+".bin";
  FILE* out=fopen((dir+"/"+name).c_str(),"wb");if(!out)continue;std::vector<uint8_t> buf(end-start);
  struct iovec l{buf.data(),buf.size()},r{reinterpret_cast<void*>(start),buf.size()};ssize_t got=process_vm_readv(getpid(),&l,1,&r,1,0);
  if(got>0)fwrite(buf.data(),1,static_cast<size_t>(got),out);fclose(out);
  char entry[300];snprintf(entry,sizeof entry,"%s{\"library\":\"libg.so\",\"va\":%lu,\"permissions\":\"%s\",\"bytes\":%ld,\"file\":\"%s\"}",manifest.size()>1?",":"",va,perms,static_cast<long>(got),name.c_str());
  manifest+=entry;}
 fclose(maps);manifest+="]";FILE* m=fopen((dir+"/manifest.json").c_str(),"w");if(m){fputs(manifest.c_str(),m);fclose(m);}
 stage("libg dumped for porting");return true;
}
extern "C" JNIEXPORT jstring JNICALL Java_lab_coc_offline_RenderActivity_prepareRenderer(JNIEnv* env,jclass,jstring library,jstring progressPath){
 const char* progressName=env->GetStringUTFChars(progressPath,nullptr);progress=fopen(progressName,"w");env->ReleaseStringUTFChars(progressPath,progressName);
 const char* path=env->GetStringUTFChars(library,nullptr);stage("Renderer library initialized through System.loadLibrary");void* handle=dlopen(path,RTLD_NOW|RTLD_LOCAL);env->ReleaseStringUTFChars(library,path);
 if(!handle)return env->NewStringUTF(dlerror());Dl_info renderInfo{};dladdr(dlsym(handle,"JNI_OnLoad"),&renderInfo);rendererBase=reinterpret_cast<P>(renderInfo.dli_fbase);const char* diagnosticPath=env->GetStringUTFChars(progressPath,nullptr);std::string crashPath=std::string(diagnosticPath)+".crash";{std::string d(diagnosticPath);size_t slash=d.rfind('/');if(slash!=std::string::npos)filesDir=d.substr(0,slash);}env->ReleaseStringUTFChars(progressPath,diagnosticPath);if(dumpLibgIfRequested())return env->NewStringUTF("libg dumped to files/runtime-code (porting)");installFileTrace(!filesDir.empty()&&access((filesDir+"/file-trace-on").c_str(),F_OK)==0);/* counting always; file log with files/file-trace-on */crashFD=open(crashPath.c_str(),O_WRONLY|O_CREAT|O_TRUNC,0600);struct sigaction action{};action.sa_sigaction=recordAbort;action.sa_flags=SA_SIGINFO|SA_RESETHAND;sigemptyset(&action.sa_mask);sigaction(SIGABRT,&action,nullptr);sigaction(SIGSEGV,&action,nullptr);sigaction(SIGBUS,&action,nullptr);sigaction(SIGILL,&action,nullptr);sigaction(SIGTRAP,&action,nullptr);JavaVM* vm=nullptr;env->GetJavaVM(&vm);traceJava(env,vm);stage("Renderer OS loader JNI ready");return env->NewStringUTF("JNI initialized through Android library loader");
}

extern "C" JNIEXPORT jstring JNICALL Java_lab_coc_offline_RenderActivity_diagnoseSupport(JNIEnv* env,jclass,jstring library,jstring progressPath,jboolean initialize){
 const char* log=env->GetStringUTFChars(progressPath,nullptr);progress=fopen(log,"w");env->ReleaseStringUTFChars(progressPath,log);
 const char* path=env->GetStringUTFChars(library,nullptr);void* handle=dlopen(path,RTLD_NOW|RTLD_LOCAL);env->ReleaseStringUTFChars(library,path);if(!handle)return env->NewStringUTF(dlerror());
 void* onload=dlsym(handle,"JNI_OnLoad");Dl_info info{};dladdr(onload,&info);P origin=reinterpret_cast<P>(info.dli_fbase);P actual=q(origin+0x677140);stage("Support dispatch record offset="+std::to_string(actual-origin));
 if(actual>=origin && actual+8<=origin+0x69002b)actual=q(actual);stage("Support JNI code offset="+std::to_string(actual-origin));
 P bootstrap=q(origin+0x68dd60);stage("Support bootstrap callback offset="+std::to_string(bootstrap-origin));
 if(actual>=origin && actual+0x80<=origin+0x69002b){char hex[257];for(int i=0;i<128;i++)sprintf(hex+i*2,"%02x",*reinterpret_cast<unsigned char*>(actual+i));stage(std::string("Generated startup bytes: ")+hex);}
 if(initialize){static bool attempted=false;if(attempted)return env->NewStringUTF("Repeated support initialization refused");attempted=true;JavaVM* vm=nullptr;env->GetJavaVM(&vm);traceJava(env,vm);int result=reinterpret_cast<jint(*)(JavaVM*,void*)>(onload)(vm,nullptr);stage("Single JNI initialization result="+std::to_string(result));env->functions=originalJNI;vm->functions=originalVM;if(env->ExceptionCheck()){env->ExceptionDescribe();env->ExceptionClear();}}
 return env->NewStringUTF("Support inspection completed; see own diagnostic log.");
}

extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_RenderActivity_configureLocalHome(JNIEnv* env,jclass,jobject assets,jboolean enabled){localHomeAssets=AAssetManager_fromJava(env,assets);localHomeRequested=enabled;}
// Replay of the original avatar encoder capture (avatar-ops.bin) into ClientAvatar::decode cd5220 through a stream object whose vtable returns recorded values.
// Reader vtable offset -> writer method recorded: 128->a8 LogicLong*, e0->40 bool, c0->30/38 ScString*, b8->20 ScString by value (x8), f0->50, f8->58, 110->88, 118->90, 120->a0.
static P readLocal(const char* saveName,const char* assetName,size_t& length);
static void plog(const std::string& line);
namespace avatarReplay {
 struct Op{uint16_t method;bool present;int32_t words[2];int64_t value;std::string text;};
 static std::vector<Op> ops;static size_t cursor;static bool mismatch;static const char* fileOverride;
 struct ScValue{int32_t capacity,length;union{char small[8];const char* pointer;};~ScValue(){}};
 static P libNew(size_t n){return reinterpret_cast<P(*)(size_t)>(rendererBase+0x16b2f40)(n);}
 static const Op* take(uint16_t expected,uint16_t alt=0){if(cursor>=ops.size()){mismatch=true;return nullptr;}const Op& op=ops[cursor];if(op.method!=expected&&(!alt||op.method!=alt)){mismatch=true;return nullptr;}cursor++;return &op;}
 static void fill(ScValue* s,const std::string& t){s->capacity=-1;s->length=t.size();if(t.size()<8){memset(s->small,0,8);memcpy(s->small,t.data(),t.size());}else{char* d=reinterpret_cast<char*>(libNew(t.size()+1));memcpy(d,t.data(),t.size());d[t.size()]=0;s->pointer=d;}}
 static P readLong(P){const Op* op=take(0xa8);if(!op||!op->present)return 0;int32_t* v=reinterpret_cast<int32_t*>(libNew(8));v[0]=op->words[0];v[1]=op->words[1];return reinterpret_cast<P>(v);}
 static P readBool(P){const Op* op=take(0x40);return op?op->value!=0:0;}
 static P readStringPtr(P){const Op* op=take(0x30,0x38);if(!op||!op->present)return 0;ScValue* s=reinterpret_cast<ScValue*>(libNew(16));fill(s,op->text);return reinterpret_cast<P>(s);}
 static ScValue readStringValue(P){ScValue s;const Op* op=take(0x20);fill(&s,op&&op->present?op->text:std::string());return s;}
 template<uint16_t W> static P readNumber(P){const Op* op=take(W);return op?static_cast<P>(op->value):0;}
 static P unexpected(P){mismatch=true;return 0;}
 static P vtable[64];static P stream[4];
 static bool decodeInto(P avatar,bool preferSave){
  size_t size;P raw=preferSave?readLocal(fileOverride?fileOverride:"save-avatar.bin","avatar-ops.bin",size):readAsset(localHomeAssets,"avatar-ops.bin",size);if(!raw||size<4)return false;const uint8_t* b=reinterpret_cast<const uint8_t*>(raw);const uint8_t* e=b+size;uint32_t n;memcpy(&n,b,4);b+=4;ops.clear();ops.reserve(n);
  for(uint32_t i=0;i<n&&b+2<=e;i++){Op op{};memcpy(&op.method,b,2);b+=2;
   if(op.method==0xa8){op.present=*b++;memcpy(op.words,b,8);b+=8;}
   else if(op.method==0x20||op.method==0x30||op.method==0x38){op.present=*b++;uint32_t len;memcpy(&len,b,4);b+=4;op.text.assign(reinterpret_cast<const char*>(b),len);b+=len;}
   else{memcpy(&op.value,b,8);b+=8;}ops.push_back(op);}
  for(auto& slot:vtable)slot=reinterpret_cast<P>(&unexpected);
  vtable[0x128/8]=reinterpret_cast<P>(&readLong);vtable[0xe0/8]=reinterpret_cast<P>(&readBool);vtable[0xc0/8]=reinterpret_cast<P>(&readStringPtr);vtable[0xb8/8]=reinterpret_cast<P>(&readStringValue);
  vtable[0xf0/8]=reinterpret_cast<P>(&readNumber<0x50>);vtable[0xf8/8]=reinterpret_cast<P>(&readNumber<0x58>);vtable[0x110/8]=reinterpret_cast<P>(&readNumber<0x88>);vtable[0x118/8]=reinterpret_cast<P>(&readNumber<0x90>);vtable[0x120/8]=reinterpret_cast<P>(&readNumber<0xa0>);
  stream[0]=reinterpret_cast<P>(vtable);cursor=0;mismatch=false;call(0xcd5220,avatar,reinterpret_cast<P>(stream));
  stage("Local home: avatar replay consumed="+std::to_string(cursor)+"/"+std::to_string(ops.size())+" mismatch="+std::to_string(mismatch));return !mismatch&&cursor==ops.size();}
}
// (filesDir declared with the globals) Local persistence in the app files dir: save-home.json (LogicLevel::saveToJSON f10a4c + JSONObject::writeToString vslot 3) and save-avatar.bin (ClientAvatar encoder cd3a28 recorded in avatar-ops.bin format).
static P readLocal(const char* saveName,const char* assetName,size_t& length){
 if(!filesDir.empty()){std::string path=filesDir+"/"+saveName;FILE* f=fopen(path.c_str(),"rb");if(f){fseek(f,0,SEEK_END);long n=ftell(f);fseek(f,0,SEEK_SET);P data=block(n+1);size_t got=fread(reinterpret_cast<void*>(data),1,n,f);fclose(f);if(n>0&&got==static_cast<size_t>(n)){length=n;stage(std::string("Local save loaded: ")+saveName+" bytes="+std::to_string(n));plog(std::string("loaded ")+saveName+" bytes="+std::to_string(n));return data;}}}
 return readAsset(localHomeAssets,assetName,length);
}
static bool writeLocal(const char* name,const void* data,size_t n){
 std::string path=filesDir+"/"+name,tmp=path+".tmp";FILE* f=fopen(tmp.c_str(),"wb");if(!f)return false;bool ok=fwrite(data,1,n,f)==n;ok=fflush(f)==0&&ok;fsync(fileno(f));fclose(f);if(!ok){unlink(tmp.c_str());return false;}return rename(tmp.c_str(),path.c_str())==0;
}
namespace avatarRecord {
 static std::vector<uint8_t> out;static uint32_t count;
 static void put(const void* p,size_t n){const uint8_t* b=reinterpret_cast<const uint8_t*>(p);out.insert(out.end(),b,b+n);}
 static void head(uint16_t m){put(&m,2);count++;}
 static void putString(uint16_t m,P s){head(m);uint8_t present=s!=0;uint32_t len=0;const char* text="";if(s){int32_t l=*reinterpret_cast<int32_t*>(s+4);if(l<0||l>1000000)l=0;len=l;text=l>=8?*reinterpret_cast<const char**>(s+8):reinterpret_cast<const char*>(s+8);}put(&present,1);put(&len,4);put(text,len);}
 static P writeLong(P,P v){head(0xa8);uint8_t present=v!=0;int32_t w[2]={0,0};if(v)memcpy(w,reinterpret_cast<void*>(v),8);put(&present,1);put(w,8);return 0;}
 static P writeBool(P,P v){head(0x40);int64_t x=v&1;put(&x,8);return v&1;}
 template<uint16_t M> static P writeString(P,P s){putString(M,s);return 0;}
 template<uint16_t M> static P writeNumber(P,P v){head(M);int64_t x=static_cast<int64_t>(v);put(&x,8);return 0;}
 static P ignored(P,P){return 0;}
 static P vtable[64];static P stream[4];
 static bool encode(P avatar,std::vector<uint8_t>& result){
  for(auto& slot:vtable)slot=reinterpret_cast<P>(&ignored);
  vtable[0xa8/8]=reinterpret_cast<P>(&writeLong);vtable[0x40/8]=reinterpret_cast<P>(&writeBool);vtable[0x20/8]=reinterpret_cast<P>(&writeString<0x20>);vtable[0x30/8]=reinterpret_cast<P>(&writeString<0x30>);vtable[0x38/8]=reinterpret_cast<P>(&writeString<0x38>);
  vtable[0x50/8]=reinterpret_cast<P>(&writeNumber<0x50>);vtable[0x58/8]=reinterpret_cast<P>(&writeNumber<0x58>);vtable[0x88/8]=reinterpret_cast<P>(&writeNumber<0x88>);vtable[0x90/8]=reinterpret_cast<P>(&writeNumber<0x90>);vtable[0xa0/8]=reinterpret_cast<P>(&writeNumber<0xa0>);
  out.assign(4,0);count=0;stream[0]=reinterpret_cast<P>(vtable);call(0xcd3a28,avatar,reinterpret_cast<P>(stream));memcpy(out.data(),&count,4);result.swap(out);return count>0;}
}
static P liveAvatar;
static P findLevel(P avatar){
 P controller=q(rendererBase+0x1838e88);P mode=controller?q(controller+0x160):0;P game=mode?q(mode+0x10):0;if(!game)return 0;
 for(int i=0;i<0x300;i+=8){P c=q(game+i);if(c<0x10000||(c&7))continue;struct iovec l[2],r[2];P v[2];l[0]={&v[0],8};r[0]={reinterpret_cast<void*>(c+0x48),8};l[1]={&v[1],8};r[1]={reinterpret_cast<void*>(c+0x50),8};
  if(process_vm_readv(getpid(),l,2,r,2,0)!=16)continue;if(v[0]==avatar||v[1]==avatar){static int logged=-1;if(logged!=i){stage("Local save: level at LogicGame+"+std::to_string(i));logged=i;}return c;}}
 return 0;
}
static bool battleActive;
// Battle speed (sandbox x2/x4, like the replay speed of the original): extra logic sub-ticks of the attack game mode (f93030, the same
// one-tick update the native battle diagnostic uses) on every frame while a battle runs. The battle timer runs faster accordingly.
static P findChild(P popup,const char* name){return reinterpret_cast<P(*)(P,const char*)>(rendererBase+0x1072d0c)(popup,name);}
static void hideClip(P clip){if(clip)*reinterpret_cast<uint8_t*>(clip+8)=0;}
static volatile int battleSpeed=1;

extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setBattleSpeed(JNIEnv*,jclass,jint speed){battleSpeed=speed<1?1:speed>8?8:speed;}
static bool externalEdit;
static void saveLocalState(){
 if(externalEdit)return;
 // No autosave from battle start until the relaunch: the pre-battle live avatar would overwrite the saved battle result.
 if(filesDir.empty()||!liveAvatar||battleActive)return;P level=findLevel(liveAvatar);if(!level){static bool warned;if(!warned){stage("Local save: level not found");warned=true;}return;}
 P json=reinterpret_cast<P(*)(size_t)>(rendererBase+0x16b2f40)(0x50);call(0x108f9a4,json);call(0xf10a4c,level,json);
 struct Builder{int32_t length,capacity;char* data;} builder{1,4096,reinterpret_cast<char*>(reinterpret_cast<P(*)(size_t)>(rendererBase+0x16b30c0)(4096))};builder.data[0]=0;
 reinterpret_cast<P(*)(P,Builder*)>(q(q(json)+0x18))(json,&builder);
 bool homeOk=builder.length>2&&builder.data[0]=='{'&&writeLocal("save-home.json",builder.data,builder.length-1);
 reinterpret_cast<void(*)(char*)>(rendererBase+0x16b3090)(builder.data);reinterpret_cast<void(*)(P)>(q(q(json)+8))(json);
 std::vector<uint8_t> avatarBytes;bool avatarOk=avatarRecord::encode(liveAvatar,avatarBytes)&&writeLocal("save-avatar.bin",avatarBytes.data(),avatarBytes.size());
 static int saves=0;if(saves++<3||!homeOk||!avatarOk)stage("Local save: home="+std::to_string(homeOk)+" bytes="+std::to_string(builder.length-1)+" avatar="+std::to_string(avatarOk)+" ops="+std::to_string(avatarBytes.size()>=4?*reinterpret_cast<uint32_t*>(avatarBytes.data()):0));
}
// Battle replay: the attack game mode (controller+0x160) records the battle in a replay object at mode+0x50 (built by fa4fe0: +8 the replay
// JSON {level, attacker, defender, end_tick, timestamp, battleModifier, cmd, ...}). Serialized like the home save (JSON vslot 3).
static bool readable(P a,void* out,size_t n);
static bool writeReplayJson(const std::string& name){
 P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;P rec=m?q(m+0x50):0;P json=rec?q(rec+8):0;if(!json)return false;
 struct Builder{int32_t length,capacity;char* data;} builder{1,4096,reinterpret_cast<char*>(reinterpret_cast<P(*)(size_t)>(rendererBase+0x16b30c0)(4096))};builder.data[0]=0;
 reinterpret_cast<P(*)(P,Builder*)>(q(q(json)+0x18))(json,&builder);
 // The recorder is created before the sandbox sets the battle modifier, so its "battleModifier" is added here (read back by the replay load).
 std::string text(builder.data,builder.length>0?builder.length-1:0);P bmod=m?q(m+0x80):0;int modId=0;
 if(bmod&&readable(bmod+0x18,&modId,4)&&modId>0&&text.size()>1&&text[0]=='{'&&text.find("\"battleModifier\"")==std::string::npos)text.insert(1,"\"battleModifier\":"+std::to_string(modId)+",");
 bool ok=text.size()>2&&text[0]=='{'&&writeLocal(name.c_str(),text.data(),text.size());
 reinterpret_cast<void(*)(char*)>(rendererBase+0x16b3090)(builder.data);plog("replay json "+name+" bytes="+std::to_string(builder.length-1));return ok;
}
static void replayDumpHook(){if(filesDir.empty())return;std::string t=filesDir+"/dump-replay.txt";if(access(t.c_str(),F_OK)!=0)return;unlink(t.c_str());writeReplayJson("replay-dump.json");}
// Local NPC attack: what the client does on NpcDataMessage (decode f7d484: +90 int, +98 ClientHome, +a0 player ClientAvatar, +a8 NPC avatar, +b0 flag, +b4 int)
// via GUI manager b8c768(manager, npcHome, &npcAvatar, &playerAvatar, npcId, 2, flag<<3, 0, &zero8, -1, b4, 0, 0, &zero16, 0).
// NPC level JSONs are server-side only (no level/ files in the client), so the target village is a copy of the player's current saved home until real NPC maps are captured.
// Deep copy of a ClientAvatar through its own encoder (cd3a28) and decoder (cd5220) using the record/replay streams.
static P attackAvatar;static P battleDefender;
static P copyAvatar(P source){
 std::vector<uint8_t> bytes;if(!source||!avatarRecord::encode(source,bytes))return 0;P copy=block(0x800);call(0xcd2cd4,copy);
 avatarReplay::ops.clear();const uint8_t* b=bytes.data()+4;const uint8_t* e=bytes.data()+bytes.size();
 while(b+2<=e){avatarReplay::Op op{};memcpy(&op.method,b,2);b+=2;if(op.method==0xa8){op.present=*b++;memcpy(op.words,b,8);b+=8;}else if(op.method==0x20||op.method==0x30||op.method==0x38){op.present=*b++;uint32_t len;memcpy(&len,b,4);b+=4;op.text.assign(reinterpret_cast<const char*>(b),len);b+=len;}else{memcpy(&op.value,b,8);b+=8;}avatarReplay::ops.push_back(op);}
 avatarReplay::cursor=0;avatarReplay::mismatch=false;for(auto& slot:avatarReplay::vtable)if(!slot)slot=reinterpret_cast<P>(&avatarReplay::unexpected);avatarReplay::stream[0]=reinterpret_cast<P>(avatarReplay::vtable);call(0xcd5220,copy,reinterpret_cast<P>(avatarReplay::stream));
 return avatarReplay::mismatch||avatarReplay::cursor!=avatarReplay::ops.size()?0:copy;
}
// Local "go home" after a battle: the client waits for OwnHomeData (LogicGameMode state 2, global 17885a0 == 4 after "Home");
// its handler ba82a4 re-enters through b7fe94(controller, home, avatar, ..., kind 1, ...), which is repeated here with the saved home and the attacker avatar after the battle.
static bool restartRequested;
static bool readable(P a,void* out,size_t n);
// Find objects with the clouds vtable (170db20) reachable from libg globals (1832000..1846000), two pointer levels deep.
static void findClouds(std::vector<std::pair<P,P>>& found){
 const P want=rendererBase+0x170db20;
 for(P g=rendererBase+0x1832000;g<rendererBase+0x1846000;g+=8){P o=0;if(!readable(g,&o,8)||o<0x10000||(o&7))continue;
  for(int i=0;i<0x800;i+=8){P a=0;if(!readable(o+i,&a,8)||a<0x10000||(a&7))continue;P v=0;if(readable(a,&v,8)&&v==want){found.push_back({o+i,a});continue;}
   for(int j=0;j<0x200;j+=8){P b=0;if(!readable(a+j,&b,8)||b<0x10000||(b&7))continue;if(readable(b,&v,8)&&v==want)found.push_back({a+j,b});}}
  if(found.size()>8)break;}
}
static bool goHomePending;static int cloudsReleaseAt=-1;
static const int CLOUDS_RELEASE_DELAY=30; // short fade after native mode=1, no pending home and progress=1; avoid the former fixed six-second wait
// After the in-place go-home the battle-exit clouds (ctor b60968, vtable 170db20) stay at scene+0x188; the scene cleanup 74598c would destroy them
// (b60b0c + operator delete, field cleared). Find the owner among GameMain/controller/GUI-manager fields and do exactly that once the home is live again.
static bool cloudsCleanupPending;
static bool removeStaleClouds(){
 P roots[3]={q(rendererBase+0x1832cb0),q(rendererBase+0x1838e88),q(rendererBase+0x1832ed8)};
 for(P root:roots){if(!root)continue;for(int i=0;i<0x600;i+=8){P obj=q(root+i);if(obj<0x10000||(obj&7))continue;P v[1];struct iovec l{v,8},r{reinterpret_cast<void*>(obj+0x188),8};if(process_vm_readv(getpid(),&l,1,&r,1,0)!=8)continue;P clouds=v[0];if(clouds<0x10000||(clouds&7))continue;struct iovec l2{v,8},r2{reinterpret_cast<void*>(clouds),8};if(process_vm_readv(getpid(),&l2,1,&r2,1,0)!=8||v[0]!=rendererBase+0x170db20)continue;
  call(0xb60b0c,clouds);reinterpret_cast<void(*)(P)>(rendererBase+0x16b2f20)(clouds);*reinterpret_cast<P*>(obj+0x188)=0;stage("Local go-home: removed stale clouds from root field +"+std::to_string(i));return true;}}
 return false;
}
// persist.log: append-only log in files dir that survives the relaunch (renderer-progress.txt is truncated on start).
static void plog(const std::string& line){if(filesDir.empty())return;FILE* f=fopen((filesDir+"/persist.log").c_str(),"a");if(!f)return;fprintf(f,"%ld %s\n",static_cast<long>(time(nullptr)),line.c_str());fclose(f);}
static std::vector<uint8_t> battleStartBytes;static uint8_t battleStartRaw[0x800];static P battleStartAvatar;
static P levelAttacker(){P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;P g=m?q(m+0x10):0;P l=g?q(g+136):0;for(int off:{0x48,0x50}){P a=l?q(l+off):0;if(a&&q(a)==rendererBase+0x172e500)return a;}return 0;}
// Compare two avatar-ops encodings op by op (numeric values only) and log differences.
static void logAvatarDiff(const char* tag,const std::vector<uint8_t>& a,const std::vector<uint8_t>& b){
 auto parse=[](const std::vector<uint8_t>& v){std::vector<std::pair<uint16_t,int64_t>> r;if(v.size()<4)return r;const uint8_t* p=v.data()+4;const uint8_t* e=v.data()+v.size();while(p+2<=e){uint16_t m;memcpy(&m,p,2);p+=2;int64_t val=0;if(m==0xa8){p+=9;}else if(m==0x20||m==0x30||m==0x38){p++;uint32_t len;memcpy(&len,p,4);p+=4+len;}else{memcpy(&val,p,8);p+=8;}r.push_back({m,val});}return r;};
 auto x=parse(a),y=parse(b);int diffs=0;std::string d;for(size_t i=0;i<x.size()&&i<y.size();i++)if(x[i]!=y[i]){if(diffs<12)d+=" #"+std::to_string(i)+":"+std::to_string(x[i].second)+"->"+std::to_string(y[i].second);diffs++;}
 plog(std::string(tag)+" ops "+std::to_string(x.size())+"->"+std::to_string(y.size())+" diffs="+std::to_string(diffs)+d);
}
// Free full clan castle (OfflineTools.fillCastle): files/cc-fill.bin is the alliance unit list of the army's castle preset
// (0x50 N, N x (0x50 id, 0x88 count, 0x88 level)); after a battle it replaces the used castle in the avatar save.
static bool spliceAllianceList(std::vector<uint8_t>& b,const std::vector<uint8_t>& list){
 if(b.size()<4)return false;uint32_t count;memcpy(&count,b.data(),4);
 struct Rec{size_t off;uint16_t m;int64_t v;};std::vector<Rec> ops;ops.reserve(count);size_t p=4;
 for(uint32_t i=0;i<count;i++){if(p+2>b.size())return false;uint16_t m;memcpy(&m,&b[p],2);size_t at=p;p+=2;int64_t v=0;
  if(m==0xa8)p+=9;else if(m==0x20||m==0x30||m==0x38){if(p+5>b.size())return false;int32_t len;memcpy(&len,&b[p+1],4);if(len<0)return false;p+=5+static_cast<size_t>(len);}
  else{if(p+8>b.size())return false;memcpy(&v,&b[p],8);p+=8;}ops.push_back({at,m,v});}
 for(size_t i=0;i+1<ops.size();i++){int64_t n=ops[i].v;if(ops[i].m!=0x50||n<1||n>50||i+3*n>=ops.size())continue;bool ok=true;
  for(int64_t k=0;k<n&&ok;k++){const Rec& id=ops[i+1+3*k];ok=id.m==0x50&&((id.v>=4000000&&id.v<5000000)||(id.v>=26000000&&id.v<27000000))&&ops[i+2+3*k].m==0x88&&ops[i+3+3*k].m==0x88;}
  if(!ok)continue;
  size_t start=ops[i].off,end=i+1+3*n<ops.size()?ops[i+1+3*n].off:b.size();int64_t fresh=0;memcpy(&fresh,&list[2],8);
  std::vector<uint8_t> r(b.begin(),b.begin()+start);r.insert(r.end(),list.begin(),list.end());r.insert(r.end(),b.begin()+end,b.end());
  uint32_t total=count-static_cast<uint32_t>(3*n)+static_cast<uint32_t>(3*fresh);memcpy(r.data(),&total,4);b.swap(r);return true;}
 return false;
}
static int guiHomeFields[3];static bool guiHomeFieldsKnown;
static void returnHomeLocal(){
 // Battle units are consumed on the attacker avatar in the attack level (LogicGame+136 -> +0x48/+0x50, vtable 172e500); copy it before the frontend switch frees it.
 P battleAvatar=0;{P controller=q(rendererBase+0x1838e88);P mode=controller?q(controller+0x160):0;P game=mode?q(mode+0x10):0;P level=game?q(game+136):0;
  // The home owner (defender) is skipped: with defense-avatar.bin it carries the sandbox's defending castle troops, not the player's own.
  {char line[160];snprintf(line,sizeof line,"go-home avatars: +48 %p +50 %p defender %p",reinterpret_cast<void*>(level?q(level+0x48):0),reinterpret_cast<void*>(level?q(level+0x50):0),reinterpret_cast<void*>(battleDefender));plog(line);}
  for(int off:{0x48,0x50}){P a=level?q(level+off):0;if(a&&a!=battleDefender&&q(a)==rendererBase+0x172e500){battleAvatar=a;break;}}}
 P avatar=copyAvatar(battleAvatar?battleAvatar:attackAvatar?attackAvatar:liveAvatar);attackAvatar=0;
 stage("Local go-home: attacker avatar from level="+std::to_string(battleAvatar!=0)+" copy="+std::to_string(avatar!=0));
 if(!avatar){restartRequested=true;return;}
 {std::vector<uint8_t> now;if(battleAvatar)avatarRecord::encode(battleAvatar,now);logAvatarDiff("level attacker start vs end",battleStartBytes,now);
  if(battleAvatar){std::string d;int n=0;const uint8_t* now8=reinterpret_cast<const uint8_t*>(battleAvatar);for(int i=0;i<0x800;i+=8){uint64_t a,b;memcpy(&a,battleStartRaw+i,8);memcpy(&b,now8+i,8);if(a!=b&&(a>>40)==0&&(b>>40)==0){if(n<16)d+=" +"+std::to_string(i)+":"+std::to_string(a)+"->"+std::to_string(b);n++;}}plog("attacker raw diff count="+std::to_string(n)+d);}else plog("attacker raw diff: avatar pointer changed");
  std::vector<uint8_t> bytes;bool enc=avatarRecord::encode(avatar,bytes);
  // Note: unit counts are decremented and saved here (verified 10 -> 9), but the home load refills the army to the active loadout (free/instant training in this version), so x10 after returning is the game rule, not a lost save.
  bool refill=false;
  {size_t n=0;P raw=enc&&access((filesDir+"/cc-fill.bin").c_str(),R_OK)==0?readLocal("cc-fill.bin","cc-fill.bin",n):0;
   if(raw&&n>=10){std::vector<uint8_t> list(reinterpret_cast<uint8_t*>(raw),reinterpret_cast<uint8_t*>(raw)+n);refill=spliceAllianceList(bytes,list);}}
  bool wr=enc&&writeLocal("save-avatar.bin",bytes.data(),bytes.size());
  // The home then gets the refilled castle: the avatar is decoded again from the save just written.
  if(wr&&refill){P fresh=block(0x800);call(0xcd2cd4,fresh);if(avatarReplay::decodeInto(fresh,true)){avatar=fresh;plog("go-home: clan castle refilled");}}plog("go-home save: battleAvatar="+std::to_string(battleAvatar!=0)+" copy="+std::to_string(avatar!=0)+" encoded="+std::to_string(enc)+" written="+std::to_string(wr)+" bytes="+std::to_string(bytes.size()));}
 // Return home the way the client enters any village: GUI manager b8c768 stores the pending home (gui+0x48 home, +0x50 avatar, +0x68 kind) and requests the
 // transition; its frontend switch (b8bbdc) then calls b7fe94 with kind 1 behind the normal clouds. Falls back to a relaunch if the manager is missing.
 P manager=q(rendererBase+0x1832ed8);if(!manager){restartRequested=true;return;}
 size_t homeSize;P homeRaw=readLocal("save-home.json","home.json",homeSize);P home=block(0x78);call(0xefbb58,home);ScString* json=reinterpret_cast<ScString*>(block(16));json->capacity=-1;json->length=homeSize;json->pointer=reinterpret_cast<const char*>(homeRaw);call(0xefbcb8,home,json);
 P calendar=block(16),events=block(16);call(0x11fce50,calendar);call(0x11fce50,events);call(0xfcb698,home+0x38,calendar);call(0xfcb698,home+0x50,events);
 int now=static_cast<int>(time(nullptr));P avatarHolder=avatar,nullHolder=0;
 reinterpret_cast<void(*)(P,P,P*,P*,int,int,int,int,P,P,P,P,P,P,P)>(rendererBase+0xb8c768)(manager,home,&avatarHolder,&nullHolder,now,1,0,0,block(8),0,static_cast<P>(now),0,0,block(16),0);
 call(0xb8d264,manager,now);
 liveAvatar=avatar;battleActive=false;cloudsReleaseAt=-1;goHomePending=true;stage("Local go-home: home requested through b8c768 kind 1");plog("go-home: in-place via b8c768");
}
// In-place reload after an offline-tools edit of the save files: the edited avatar and home enter through the same GUI manager path as the go-home
// (b8c768 kind 1 behind clouds), so resources, levels and equipment change without restarting the process.
static bool reloadHomeRequested;
static bool reloadHomeFromSave(){
 P manager=q(rendererBase+0x1832ed8);if(!manager)return false;
 P avatar=block(0x800);call(0xcd2cd4,avatar);if(!avatarReplay::decodeInto(avatar,true)){plog("reload: avatar decode failed");return false;}
 size_t homeSize;P homeRaw=readLocal("save-home.json","home.json",homeSize);P home=block(0x78);call(0xefbb58,home);ScString* json=reinterpret_cast<ScString*>(block(16));json->capacity=-1;json->length=homeSize;json->pointer=reinterpret_cast<const char*>(homeRaw);call(0xefbcb8,home,json);
 P calendar=block(16),events=block(16);call(0x11fce50,calendar);call(0x11fce50,events);call(0xfcb698,home+0x38,calendar);call(0xfcb698,home+0x50,events);
 int now=static_cast<int>(time(nullptr));P avatarHolder=avatar,nullHolder=0;
 reinterpret_cast<void(*)(P,P,P*,P*,int,int,int,int,P,P,P,P,P,P,P)>(rendererBase+0xb8c768)(manager,home,&avatarHolder,&nullHolder,now,1,0,0,block(8),0,static_cast<P>(now),0,0,block(16),0);
 call(0xb8d264,manager,now);
 liveAvatar=avatar;cloudsReleaseAt=-1;goHomePending=true;plog("reload: home requested through b8c768 kind 1");return true;
}
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_requestReloadHome(JNIEnv*,jclass){reloadHomeRequested=true;}
// Live edits (no save rewrite, no reload): applied on the logic thread to the avatar of the running home with the game's own setters;
// the autosave then stores them. Resources: LogicAvatar::setResourceCount cbdf8c(avatar, resource data, count), table 2 = resources.csv.
static std::atomic<long long> liveResourceRequest{0};  // (resource row << 32) | count; 0 = none
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setLiveResource(JNIEnv*,jclass,jint row,jint count){liveResourceRequest=(static_cast<long long>(row)<<32)|static_cast<uint32_t>(count);}
// Unit levels (troops, spells, heroes, pets): LogicAvatar::setUnitUpgradeLevel cbf588(avatar, data, level index); data = table (id prefix - 1),
// row (id % 1000000). Queue of (global id, level index) pairs.
static std::mutex liveUnitLock;static std::vector<std::pair<int,int>> liveUnitRequests;
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setLiveUnitLevel(JNIEnv*,jclass,jint id,jint level){std::lock_guard<std::mutex> g(liveUnitLock);liveUnitRequests.push_back({id,level});}
static P dataById(int id){P source=q(rendererBase+0x1844678);P manager=source?q(source+8):0;int t=id/1000000-1,row=id%1000000;
 P table=manager&&t>=0?reinterpret_cast<P(*)(P,int)>(rendererBase+0xdf80a0)(manager,t):0;if(!table||row>=*reinterpret_cast<int*>(table+0x14))return 0;return q(q(table+8)+8*row);}
// Building levels: LogicBuilding::setUpgradeLevel e696bc(building, level index) (clamps to the data's level count, updates its components).
// The building is found by instance id in the home's building list (game+0x68 object manager -> +0x0: pointer array, count at +0xc;
// instance id at object+0x30).
static std::vector<std::pair<int,int>> liveBuildingRequests;  // (instance id, level index), guarded by liveUnitLock
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setLiveBuildingLevel(JNIEnv*,jclass,jint id,jint level){std::lock_guard<std::mutex> g(liveUnitLock);liveBuildingRequests.push_back({id,level});}
static P homeObjectById(int id){P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;P g=m?q(m+0x10):0;P om=g?q(g+0x68):0;P list=om?q(om):0;if(!list)return 0;
 int n=*reinterpret_cast<int*>(list+0xc);P items=q(list);for(int i=0;i<n;i++){P o=q(items+8*i);int oid=0;if(o&&readable(o+0x30,&oid,4)&&oid==id)return o;}return 0;}
// Hero levels: the avatar's unit level (setUnitUpgradeLevel) and the hero hall's hero component (hall object+0xe0, vtable 0x17475d8): its
// +0x28 vector holds one level index per hero slot (saved as heroLevelN). Requests: hall instance id, slot, hero id, level index.
struct HeroRequest{int hall,slot,hero,level;};static std::vector<HeroRequest> liveHeroRequests;
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setLiveHeroLevel(JNIEnv*,jclass,jint hall,jint slot,jint hero,jint level){std::lock_guard<std::mutex> g(liveUnitLock);liveHeroRequests.push_back({hall,slot,hero,level});}
// Gems: LogicClientAvatar diamonds (+0x2a0) and free diamonds (+0x2a4); -1 = no request.
static std::atomic<int> liveGemsRequest{-1};
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setLiveGems(JNIEnv*,jclass,jint gems){liveGemsRequest=gems;}
// Battle modifiers (battle_modifiers.csv, table 91: 0 Hard Mode, 1 Minus One, 2 Minus Two, 3 Minus Three = Legendary 1/2/3): the client's
// setter f94d18(mode, data) stores it at mode+0x80 and its id in the game (+0x40 -> +0x2a0), as the friendly-challenge screen does after
// creating the battle. Applied once to every sandbox attack mode; -1 = none.
static std::atomic<int> battleModifierRow{-1};
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setBattleModifier(JNIEnv*,jclass,jint row){battleModifierRow=row;}
static void applyBattleModifier(){static P applied;P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;if(!m||*reinterpret_cast<int*>(m)!=2||m==applied)return;applied=m;
 int row=battleModifierRow.load();if(row<0)return;P data=dataById(92000000+row);if(!data){plog("modifier: data missing");return;}
 reinterpret_cast<void(*)(P,P)>(rendererBase+0xf94d18)(m,data);plog("modifier: row "+std::to_string(row)+" applied");}
// Hero equipment levels: LogicAvatar m_pCharacterItemUpgrade (avatar+0x210 -> items +0, count +0xc; checksum writer cc46d0) holds
// LogicDataSlots {item data, level index at +8}; the level is written in place (the save encoder reads it from there).
// (item global id, level index) requests; items not owned yet are added by the Java side through the save.
static std::vector<std::pair<int,int>> liveEquipmentRequests;  // guarded by liveUnitLock
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setLiveEquipmentLevel(JNIEnv*,jclass,jint id,jint level){std::lock_guard<std::mutex> g(liveUnitLock);liveEquipmentRequests.push_back({id,level});}
// Every pointer is checked with readable(): the encoder copy (liveAvatar) does not always carry the same objects.
static bool setEquipmentLevel(P avatar,P data,int level){P list=0,items=0,slot=0,d=0;int n=0;
 if(!avatar||!readable(avatar+0x210,&list,8)||!list||!readable(list,&items,8)||![&]{int two[2];if(!readable(list+8,two,8))return false;n=two[1];return true;}()||!items||n<=0||n>500){
  char line[160];snprintf(line,sizeof line,"equipment list missing: avatar %p vt %lx list %p items %p n %d",reinterpret_cast<void*>(avatar),static_cast<unsigned long>(avatar?q(avatar)-rendererBase:0),reinterpret_cast<void*>(list),reinterpret_cast<void*>(items),n);plog(line);return false;}
 int dataId=0;readable(data+0x18,&dataId,4);
 for(int i=0;i<n;i++){if(!readable(items+8*i,&slot,8)||!slot||!readable(slot,&d,8))continue;int id=0;readable(d+0x18,&id,4);if(d==data||id==dataId){*reinterpret_cast<int*>(slot+8)=level;return true;}}
 {int first=0;P s0=0,d0=0;if(readable(items,&s0,8)&&s0&&readable(s0,&d0,8))readable(d0+0x18,&first,4);plog("equipment list: "+std::to_string(n)+" items, first "+std::to_string(first)+", wanted "+std::to_string(dataId));}
 return false;}
// Diagnostic (files/find-ptr.txt "<global id>"): breadth-first walk from the home avatar over heap pointers (4 levels, 0x300 bytes per
// object) and log every place holding that data object's pointer, with the 16 bytes after it.
static P homeAvatar();
static void findPtrHook(){
 std::string t=filesDir+"/find-ptr.txt";FILE* f=fopen(t.c_str(),"r");if(!f)return;int id=0;if(fscanf(f,"%d",&id)!=1)id=0;fclose(f);unlink(t.c_str());
 P target=dataById(id);P root=homeAvatar();if(!target||!root){plog("findptr: no target/root");return;}
 plog("findptr: target "+std::to_string(id));
 struct N{P a;int depth;std::string path;};std::vector<N> queue{{root,0,"av"}};std::vector<P> seen{root};size_t head=0;int hits=0;uint8_t buf[0x300];
 while(head<queue.size()&&queue.size()<40000&&hits<40){N n=queue[head++];if(!readable(n.a&0x00ffffffffffffffull,buf,sizeof(buf)))continue;
  for(int o=0;o+8<=0x300;o+=8){P v;memcpy(&v,buf+o,8);
   if((v&0x00ffffffffffffffull)==(target&0x00ffffffffffffffull)){int a=0,b=0,c=0;memcpy(&a,buf+o+8<buf+0x300?buf+o+8:buf,4);if(o+16<=0x300){memcpy(&b,buf+o+8,4);memcpy(&c,buf+o+12,4);}
    char line[256];snprintf(line,sizeof line,"findptr hit %s+0x%x next %d %d (obj %llx)",n.path.c_str(),o,b,c,(unsigned long long)n.a);plog(line);hits++;(void)a;}
   if(n.depth<4&&(v>>56)==0xb4){P va=v;bool dup=false;for(size_t i=seen.size()>4000?seen.size()-4000:0;i<seen.size();i++)if(seen[i]==va){dup=true;break;}
    if(!dup){seen.push_back(va);char ps[32];snprintf(ps,sizeof ps,">%x",o);queue.push_back({va,n.depth+1,n.path+ps});}}}}
 plog("findptr: done nodes="+std::to_string(head)+" hits="+std::to_string(hits));
}
static P homeAvatar(){P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;P g=m?q(m+0x10):0;P level=g?q(g+136):0;
 for(int off:{0x48,0x50}){P a=level?q(level+off):0;if(a&&q(a)==rendererBase+0x172e500)return a;}return liveAvatar;}
// Home HUD pieces the sandbox replaces or does not use: the build and shop buttons (HUD +0x110 / +0x120, created by 88af5c from
// hud.sc hud_button_build / hud_button_shop), the clan button (GameButton id 0x2a, hud_button_clan), the gems "+" (resource bars
// HUD+8 -> +0x10, add_diamonds_button) and the shield counter (bars +0xc0 child "shield_counter"; the game itself removes it in some
// modes, 89c8a4). All GameButtons (vtable 16ed0e8, id at +0x180) are listed in the vector at 1838770 (begin, end, capacity; 9f91ac); hidden through the display
// object's visible byte (+8) every frame, as the HUD re-shows them on its own updates.
static P rq(P a);
static P homeHud;
// Hidden for good: visible byte off and the transform scaled to zero (the HUD turns some buttons visible again on its own updates).
static void collapse(P o){if(!o)return;float m[4];if(!readable(o+0x10,m,16))return;hideClip(o);float* t=reinterpret_cast<float*>(o+0x10);t[0]=t[1]=t[2]=t[3]=0;}
static bool isButton(P b,int id){P vt=0;int bid=-1;return readable(b,&vt,8)&&vt==rendererBase+0x16ed0e8&&readable(b+0x180,&bid,4)&&bid==id;}
static P findHomeHud(P build){
 P roots[]={q(rendererBase+0x1832ed8),q(rendererBase+0x1832f98),q(rendererBase+0x1832cb0)};
 for(P r:roots){if(!r)continue;for(int i=0;i<0x800;i+=8){P a=rq(r+i);if(!a)continue;if(rq(a+0x110)==build)return a;
  for(int j=0;j<0x400;j+=8){P b=rq(a+j);if(b&&rq(b+0x110)==build)return b;}}}
 return 0;
}
// Screen transform of a display object: its matrix (+0x10: a, b, c, d, tx, ty) composed up the parent chain (+0x38) to the stage.
// out = {a, d, tx, ty} of the composed matrix (HUD objects are not rotated); false when the chain is broken.
static bool screenTransform(P o,float out[4]){
 float A=1,B=0,C=0,D=1,TX=0,TY=0;int depth=0;
 for(P n=o;n&&depth<40;n=rq(n+0x38),depth++){float m[6];if(!readable(n+0x10,m,24))return false;
  float a=m[0]*A+m[2]*B,b=m[1]*A+m[3]*B,c=m[0]*C+m[2]*D,d=m[1]*C+m[3]*D,tx=m[0]*TX+m[2]*TY+m[4],ty=m[1]*TX+m[3]*TY+m[5];
  A=a;B=b;C=c;D=d;TX=tx;TY=ty;}
 out[0]=A;out[1]=D;out[2]=TX;out[3]=TY;return depth<40;
}
// HUD anchors in stage units (the HUD stage is 640 units high; Java scales by screen height / 640): x, y of build, clan, gems "+".
static std::atomic<float> hudAnchors[6];
extern "C" JNIEXPORT jfloatArray JNICALL Java_lab_coc_offline_OfflineTools_hudAnchors(JNIEnv* env,jclass){
 jfloatArray r=env->NewFloatArray(6);float v[6];for(int i=0;i<6;i++)v[i]=hudAnchors[i].load();env->SetFloatArrayRegion(r,0,6,v);return r;}
// HUD shown: the build button (id 0x39, itself hidden by the sandbox) has a visible, non-collapsed parent chain and sits on the stage
// (640 units high, width from the screen aspect). Written every home frame by hideHomeHud, cleared whenever the home HUD is absent.
static std::atomic<bool> hudShownFlag;static std::atomic<float> screenAspect{2800.f/1272.f};
extern "C" JNIEXPORT jboolean JNICALL Java_lab_coc_offline_OfflineTools_hudShown(JNIEnv*,jclass){return hudShownFlag.load();}
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setScreenAspect(JNIEnv*,jclass,jfloat a){if(a>0.5f&&a<4.f)screenAspect.store(a);}
static bool hudChainShown(P button,float stageW){
 int depth=0;for(P n=rq(button+0x38);n&&depth<40;n=rq(n+0x38),depth++){uint8_t v=0;if(!readable(n+8,&v,1)||!v)return false;}
 float t[4];if(depth>=40||!screenTransform(button,t))return false;
 return t[0]>0.05f&&t[1]>0.05f&&t[2]>0&&t[2]<stageW&&t[3]>0&&t[3]<640.f;
}
static void hideHomeHud(){
 P begin=q(rendererBase+0x1838770),end=q(rendererBase+0x1838778);if(!begin||end<begin||end-begin>0x10000){hudShownFlag.store(false);return;}
 P build=0;
 for(P it=begin;it<end;it+=8){P b=rq(it);if(!b)continue;
  if(isButton(b,0x2a)||isButton(b,7))collapse(b);  // clan button, gems "+"
  else if(isButton(b,0x39)){build=b;hideClip(b);}}  // only hidden: collapsing it moves the neighbouring HUD buttons
 {static bool logged;if(!logged){logged=true;plog("home HUD: "+std::to_string((end-begin)/8)+" buttons, build "+(build?"found":"missing"));}}
 // Anchors for the sandbox's own HUD buttons (Java): stage position of the game's build (id 0x39), clan (0x2a) and gems "+" (7) buttons.
 for(P it=begin;it<end;it+=8){P b=rq(it);int id=-1;if(!b||!readable(b+0x180,&id,4))continue;int slot=id==0x39?0:id==0x2a?1:id==7?2:-1;float t[4];
  if(slot>=0&&screenTransform(b,t)){hudAnchors[slot*2].store(t[2]);hudAnchors[slot*2+1].store(t[3]);}}
 hudShownFlag.store(build&&hudChainShown(build,640.f*screenAspect.load()));  // one store per frame: the Java poll never sees a transient false
 if(!build)return;
 if(!homeHud||rq(homeHud+0x110)!=build){static int tries;if(tries++%60)return;homeHud=findHomeHud(build);
  if(homeHud){char line[200];snprintf(line,sizeof line,"home HUD found %p vt=%lx +8=%p +110=%p +118=%p +120=%p",reinterpret_cast<void*>(homeHud),static_cast<unsigned long>(rq(homeHud)-rendererBase),reinterpret_cast<void*>(rq(homeHud+8)),reinterpret_cast<void*>(rq(homeHud+0x110)),reinterpret_cast<void*>(rq(homeHud+0x118)),reinterpret_cast<void*>(rq(homeHud+0x120)));plog(line);
   P bars=rq(homeHud+8);snprintf(line,sizeof line,"bars vt=%lx +10=%p +c0=%p",static_cast<unsigned long>(rq(bars)-rendererBase),reinterpret_cast<void*>(rq(bars+0x10)),reinterpret_cast<void*>(rq(bars+0xc0)));plog(line);}}
 if(!homeHud)return;
 if(P shop=rq(homeHud+0x120))collapse(shop);
 // Resource bars (class with the gems "+" GameButton id 7 at +0x10; not HUD+8): found among the HUD's fields, its +0xc0 clip holds "shield_counter".
 static P bars,shield;
 if(!bars||!isButton(rq(bars+0x10),7)){bars=0;shield=0;static int tries;if(tries++%120)return;
  for(int i=0;i<0x400&&!bars;i+=8){P x=rq(homeHud+i);if(x&&isButton(rq(x+0x10),7))bars=x;}
  if(bars){P clip=rq(bars+0xc0);P vt=clip?rq(clip):0;if(vt&&rq(vt))shield=reinterpret_cast<P(*)(P,const char*)>(rendererBase+0x103e0e0)(clip,"shield_counter");}
  plog(std::string("home HUD: bars ")+(bars?"found":"missing")+", shield counter "+(shield?"found":"missing"));}
 if(!bars)return;
 collapse(rq(bars+0x10));
 if(shield)collapse(shield);
}
// Diagnostic (replay vs battle divergence): the game's own out-of-sync dump. LogicGameMode checksum f961f0(mode, json, 1, 1) fills a
// LogicJSONObject (ctor 108f9a4) with every value it hashes (as b830c8 does for OutOfSync); 108fd7c writes it as text. Every 300
// subticks of a battle (state 2) or replay (state 5) it is saved to files/cs-battle-<tick>.json / cs-replay-<tick>.json.
static bool stateDumps=false;  // diagnostic switch
static void dumpBattleState(){
 P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;P g=m?q(m+0x10):0;if(!g)return;int st=*reinterpret_cast<int*>(m);if(st!=2&&st!=5){return;}
 int tick=*reinterpret_cast<int*>(g+0x60);static P lastMode;static int lastBucket;if(m!=lastMode){lastMode=m;lastBucket=-1;}
 int bucket=tick/300;if(bucket==lastBucket)return;lastBucket=bucket;
 P json=block(0x100);call(0x108f9a4,json);int cs=reinterpret_cast<int(*)(P,P,int,int)>(rendererBase+0xf961f0)(m,json,1,1);
 ScString text{};call(0x12001d0,reinterpret_cast<P>(&text));call(0x108fd7c,json,reinterpret_cast<P>(&text));
 const char* body=text.length<8?text.small:text.pointer;char probe;if(text.length>=8&&!readable(reinterpret_cast<P>(body),&probe,1)){plog("state dump: unreadable text");return;}
 std::string name=std::string("cs-")+(st==2?"battle":"replay")+"-"+std::to_string(bucket*300)+".json";
 if(body&&text.length>0)writeLocal(name.c_str(),body,text.length);
 plog(name+" tick "+std::to_string(tick)+" checksum "+std::to_string(cs)+" bytes "+std::to_string(text.length));
}
// Social popup ("Мой профиль", "Мой клан", "Кланы", "Общение", "Входящие", "Настройки"; tab buttons vector +0x1f8, current tab +0x2a8,
// tab switch 698cbc): offline only the profile and settings tabs are kept; the clan tabs are collapsed and "Настройки" takes the place of
// "Мой клан". The profile page waits for the server, so the sandbox draws its own profile over it (Java, socialTab()).
static std::atomic<int> socialTabState{-1};
extern "C" JNIEXPORT jint JNICALL Java_lab_coc_offline_OfflineTools_socialTab(JNIEnv*,jclass){return socialTabState.load();}
static void socialPopupTick(){
 P pm=q(rendererBase+0x1832f98);P begin=pm?rq(pm+0x178):0,end=pm?rq(pm+0x180):0;int state=-1;
 // The tab owner is the popup itself or one of its fields (the popup in the manager's list wraps it).
 auto isTabOwner=[](P o){P tabs=o?rq(o+0x1f8):0,tabsEnd=o?rq(o+0x200):0;if(!tabs||tabsEnd-tabs!=6*8)return false;
  P vt0=rq(rq(tabs));if(!vt0||vt0<rendererBase||vt0>rendererBase+0x2000000)return false;
  for(int k=0;k<6;k++){P b=rq(tabs+8*k);if(!b||rq(b)!=vt0)return false;}return true;};
 static P cachedPopup,cachedOwner;
 for(P it=begin;begin&&it<end&&end-begin<0x400;it+=8){P wrapper=rq(it);if(!wrapper)continue;
  P popup=0;if(wrapper==cachedPopup&&isTabOwner(cachedOwner))popup=cachedOwner;
  else{if(isTabOwner(wrapper))popup=wrapper;for(int f=0;!popup&&f<0x600;f+=8){P o=rq(wrapper+f);if(o&&isTabOwner(o))popup=o;}
   if(popup){cachedPopup=wrapper;cachedOwner=popup;char line[120];snprintf(line,sizeof line,"social popup: tabs owner %lx from popup vt %lx, tab vt %lx",static_cast<unsigned long>(popup-wrapper),static_cast<unsigned long>(rq(wrapper)-rendererBase),static_cast<unsigned long>(rq(rq(rq(popup+0x1f8)))-rendererBase));plog(line);}}
  if(!popup)continue;
  P tabs=rq(popup+0x1f8);
  int tab=-1;readable(popup+0x2a8,&tab,4);state=tab<0?0:tab;{static int logged=-99;if(tab!=logged){logged=tab;plog("social popup tab "+std::to_string(tab));}}
  P myClan=rq(tabs+8),settings=rq(tabs+40);float clanX=0,setX=0;
  if(readable(myClan+0x10+16,&clanX,4)&&readable(settings+0x10+16,&setX,4)){
   static float slotX=-1e9f;if(clanX!=0||slotX==-1e9f)slotX=slotX==-1e9f?clanX:slotX;
   if(setX!=slotX)*reinterpret_cast<float*>(settings+0x10+16)=slotX;}
  for(int k=1;k<=4;k++)collapse(rq(tabs+8*k));
  break;}
 socialTabState=state;
}
// Endless magic items: LogicAvatar m_pBoosterCount (avatar+0x128 -> slots +0, count +0xc; checksum writer cc4508) holds LogicDataSlots
// {item data, count at +8}. Every half second each item is refilled to its storage limit (boosters.csv MaxItems, from Java).
static std::mutex magicLock;static std::map<int,int> magicCaps;
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_setMagicItemCaps(JNIEnv* env,jclass,jintArray ids,jintArray caps){
 std::lock_guard<std::mutex> g(magicLock);magicCaps.clear();jsize n=env->GetArrayLength(ids);std::vector<jint> a(n),b(n);
 env->GetIntArrayRegion(ids,0,n,a.data());env->GetIntArrayRegion(caps,0,n,b.data());for(jsize i=0;i<n;i++)magicCaps[a[i]]=b[i];}
static P homeAvatar();
static void refillMagicItems(){
 std::map<int,int> caps;{std::lock_guard<std::mutex> g(magicLock);caps=magicCaps;}if(caps.empty())return;
 P av=homeAvatar();
 for(P avatar:{av,liveAvatar!=av?liveAvatar:0}){if(!avatar)continue;
  P list=0,items=0;int n=0;int two[2];
  if(!readable(avatar+0x128,&list,8)||!list||!readable(list,&items,8)||!readable(list+8,two,8))continue;n=two[1];if(!items||n<=0||n>200)continue;
  for(int i=0;i<n;i++){P slot=0,d=0;int id=0,count=0;
   if(!readable(items+8*i,&slot,8)||!slot||!readable(slot,&d,8)||!d||!readable(d+0x18,&id,4)||!readable(slot+8,&count,4))continue;
   auto c=caps.find(id);if(c!=caps.end()&&count<c->second)*reinterpret_cast<int*>(slot+8)=c->second;}}
}
static void applyLiveEdits(){
 {std::string t=filesDir+"/live-resource.txt";FILE* f=fopen(t.c_str(),"r");if(f){int row=0,count=0;if(fscanf(f,"%d %d",&row,&count)==2)liveResourceRequest=(static_cast<long long>(row)<<32)|static_cast<uint32_t>(count);fclose(f);unlink(t.c_str());}}  // diagnostic
 findPtrHook();
 {std::vector<std::pair<int,int>> eq;{std::lock_guard<std::mutex> g(liveUnitLock);eq.swap(liveEquipmentRequests);}
  for(auto& e:eq){P data=dataById(e.first);P av=homeAvatar();if(!data)plog("live equipment: no data "+std::to_string(e.first));bool ok=data&&setEquipmentLevel(av,data,e.second);if(ok&&liveAvatar&&liveAvatar!=av)setEquipmentLevel(liveAvatar,data,e.second);
   plog("live equipment "+std::to_string(e.first)+(ok?" level index "+std::to_string(e.second):std::string(": not owned")));}}
 {std::string t=filesDir+"/live-equipment.txt";FILE* f=fopen(t.c_str(),"r");if(f){int id=0,lv=0;if(fscanf(f,"%d %d",&id,&lv)==2){std::lock_guard<std::mutex> g(liveUnitLock);liveEquipmentRequests.push_back({id,lv});}fclose(f);unlink(t.c_str());}}  // diagnostic
 {std::string t=filesDir+"/live-hero.txt";FILE* f=fopen(t.c_str(),"r");if(f){HeroRequest r{};if(fscanf(f,"%d %d %d %d",&r.hall,&r.slot,&r.hero,&r.level)==4){std::lock_guard<std::mutex> g(liveUnitLock);liveHeroRequests.push_back(r);}fclose(f);unlink(t.c_str());}}  // diagnostic
 {std::string t=filesDir+"/live-building.txt";FILE* f=fopen(t.c_str(),"r");if(f){int id=0,lv=0;if(fscanf(f,"%d %d",&id,&lv)==2){std::lock_guard<std::mutex> g(liveUnitLock);liveBuildingRequests.push_back({id,lv});}fclose(f);unlink(t.c_str());}}  // diagnostic
 {std::string t=filesDir+"/live-unit.txt";FILE* f=fopen(t.c_str(),"r");if(f){int id=0,lv=0;if(fscanf(f,"%d %d",&id,&lv)==2){std::lock_guard<std::mutex> g(liveUnitLock);liveUnitRequests.push_back({id,lv});}fclose(f);unlink(t.c_str());}}  // diagnostic
 {std::vector<std::pair<int,int>> units;{std::lock_guard<std::mutex> g(liveUnitLock);units.swap(liveUnitRequests);}
  P avatar=units.empty()?0:homeAvatar();
  for(auto& u:units){P data=dataById(u.first);if(!data||!avatar){plog("live unit "+std::to_string(u.first)+": unavailable");continue;}
   reinterpret_cast<void(*)(P,P,int)>(rendererBase+0xcbf588)(avatar,data,u.second);if(liveAvatar&&liveAvatar!=avatar)reinterpret_cast<void(*)(P,P,int)>(rendererBase+0xcbf588)(liveAvatar,data,u.second);plog("live unit "+std::to_string(u.first)+" level index "+std::to_string(u.second));}}
 {std::vector<std::pair<int,int>> blds;{std::lock_guard<std::mutex> g(liveUnitLock);blds.swap(liveBuildingRequests);}
  for(auto& b:blds){P o=homeObjectById(b.first);if(!o){plog("live building "+std::to_string(b.first)+": not found");continue;}
   reinterpret_cast<void(*)(P,int)>(rendererBase+0xe696bc)(o,b.second);
   // The level's game listener (level+0x38) is told the building is finished, as the construction finish e6af9c does after
   // setUpgradeLevel (vtable +0xa68, building, globals+0x744); the client then rebuilds the building's visual for its new level.
   {P level=q(o+8);P listener=level?q(level+0x38):0;P globals=reinterpret_cast<P(*)()>(rendererBase+0xdf6884)();
    if(listener&&q(listener))reinterpret_cast<void(*)(P,P,int)>(q(q(listener)+0xa68))(listener,o,globals?*reinterpret_cast<int*>(globals+0x744):0);}
   plog("live building "+std::to_string(b.first)+" level index "+std::to_string(*reinterpret_cast<int*>(o+0x150)));}}
 {std::vector<HeroRequest> hs;{std::lock_guard<std::mutex> g(liveUnitLock);hs.swap(liveHeroRequests);}
  for(auto& h:hs){P data=dataById(h.hero);P avatar=homeAvatar();P hall=homeObjectById(h.hall);P comp=hall?q(hall+0xe0):0;
   if(!data||!avatar||!comp||q(comp)!=rendererBase+0x17475d8){plog("live hero "+std::to_string(h.hero)+": unavailable");continue;}
   reinterpret_cast<void(*)(P,P,int)>(rendererBase+0xcbf588)(avatar,data,h.level);if(liveAvatar&&liveAvatar!=avatar)reinterpret_cast<void(*)(P,P,int)>(rendererBase+0xcbf588)(liveAvatar,data,h.level);
   P vec=q(comp+0x28);int n=vec?*reinterpret_cast<int*>(vec+0xc):0;if(h.slot>=0&&h.slot<n)reinterpret_cast<int*>(q(vec))[h.slot]=h.level;
   plog("live hero "+std::to_string(h.hero)+" slot "+std::to_string(h.slot)+" level index "+std::to_string(h.level));}}
 {std::string t=filesDir+"/live-gems.txt";FILE* f=fopen(t.c_str(),"r");if(f){int v=-1;if(fscanf(f,"%d",&v)==1)liveGemsRequest=v;fclose(f);unlink(t.c_str());}}  // diagnostic
 if(int gems=liveGemsRequest.exchange(-1);gems>=0){P av=homeAvatar();
  // Through the client's own "add diamonds" path cde680(avatar, delta): adds to both counters and notifies the HUD; the free counter is
  // aligned first with its plain setter, so both end at the target.
  for(P a:{av,liveAvatar!=av?liveAvatar:0})if(a){int cur=reinterpret_cast<int(*)(P)>(rendererBase+0xcde668)(a);reinterpret_cast<void(*)(P,int)>(rendererBase+0xcde670)(a,cur);reinterpret_cast<void(*)(P,int)>(rendererBase+0xcde680)(a,gems-cur);}
  if(av)plog("live gems "+std::to_string(gems));}
 long long r=liveResourceRequest.exchange(0);if(!r)return;
 P source=q(rendererBase+0x1844678);P manager=source?q(source+8):0;P table=manager?reinterpret_cast<P(*)(P,int)>(rendererBase+0xdf80a0)(manager,2):0;
 int row=static_cast<int>(r>>32),count=static_cast<int>(r&0xffffffff);P data=table?q(q(table+8)+8*row):0;P avatar=homeAvatar();
 if(!data||!avatar){plog("live resource: unavailable");return;}
 reinterpret_cast<void(*)(P,P,int)>(rendererBase+0xcbdf8c)(avatar,data,count);if(liveAvatar&&liveAvatar!=avatar)reinterpret_cast<void(*)(P,P,int)>(rendererBase+0xcbdf8c)(liveAvatar,data,count);  // the autosave encodes liveAvatarplog("live resource row "+std::to_string(row)+" = "+std::to_string(count));
}
// Replay viewer: when the transition clouds close, the controller (b7e0c0, vtable slot 0x60) loads a replay instead of a village if a
// replay JSON string is set at controller+0x190 (recorded replay, receiver b7fb6c) or +0x198 (live spectating, receiver b7f8d4).
// The sandbox uses the recorded one: b7e0c0 reads end_tick and the "cmd" list and runs LogicGameMode::loadReplay (f95310), which clears
// mode+0xd. The live branch (f94ee0) sets mode+0xd, and the HUD then shows the "under attack" villager (b8b518 via b81034) and TID_LIVE_REPLAY.
// The sandbox sets the saved replay there and starts the usual home transition. While it plays, the autosave is off, and its "home"
// button reloads the village from the saves (no avatar is taken from the replay).
static std::string replayRequested,replayFile;static bool replayActive;static bool replayTickAssist=false;/* experiment: extra ticks move the battle clock but not the recorded actions */static P replayJson,replayOldController;
static volatile bool replayExitRequested;
extern "C" JNIEXPORT jboolean JNICALL Java_lab_coc_offline_OfflineTools_isReplayActive(JNIEnv*,jclass){return replayActive;}
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_requestReplayExit(JNIEnv*,jclass){replayExitRequested=true;}
// Replays: a recording is re-run inside a live sandbox attack against the recorded base (attack-home.json, written by OfflineTools). The "cmd" items of a saved replay are turned into command objects with the
// client's own JSON loader (d72b90) and queued in the attack's command manager (mode+0x18, d72078); they run at their recorded ticks.
static std::string injectRequested;static bool rerunActive;
static void injectReplayCommands(){
 std::string name=injectRequested;injectRequested.clear();
 P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;int ms=m?*reinterpret_cast<int*>(m):-1;if(ms!=2&&ms!=5){plog("inject: no battle");return;}
 size_t size=0;if(access((filesDir+"/"+name).c_str(),R_OK)!=0){plog("inject: missing");return;}P raw=readLocal(name.c_str(),"home.json",size);
 ScString* js=reinterpret_cast<ScString*>(block(16));js->capacity=-1;js->length=static_cast<int32_t>(size);js->pointer=reinterpret_cast<const char*>(raw);
 P root=reinterpret_cast<P(*)(P,P,int)>(rendererBase+0x1092d4c)(reinterpret_cast<P>(js),0,100);if(!root){plog("inject: parse failed");return;}
 ScString key{};call(0x11fd244,reinterpret_cast<P>(&key),reinterpret_cast<P>("cmd"));
 P arr=reinterpret_cast<P(*)(P,P,int)>(rendererBase+0x1090064)(root,reinterpret_cast<P>(&key),1);if(!arr){plog("inject: no cmd");return;}
 int n=*reinterpret_cast<int*>(arr+0x14),added=0;P mgr=q(m+0x18);
 for(int i=0;i<n;i++){P item=q(q(arr+8)+8*i);P cmd=0;
  // d72b90 returns the command through x8 (indirect result): model it as a function returning a one-pointer struct of non-trivial type.
  struct Out{P p;Out(){p=0;}~Out(){}};Out o=reinterpret_cast<Out(*)(P)>(rendererBase+0xd72b90)(item);cmd=o.p;
  if(cmd){P holder=cmd;reinterpret_cast<void(*)(P,P*)>(rendererBase+0xd72078)(mgr,&holder);added++;}}
rerunActive=true;
 plog("inject: "+std::to_string(added)+"/"+std::to_string(n)+" commands queued at tick "+std::to_string(*reinterpret_cast<int*>(q(m+0x10)+0x60)));
}
static void injectHook(){if(filesDir.empty())return;std::string t=filesDir+"/inject-replay.txt";FILE* f=fopen(t.c_str(),"r");if(!f)return;char b[256]={0};fgets(b,sizeof(b)-1,f);fclose(f);
 P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;if(!m||*reinterpret_cast<int*>(m)!=2)return;unlink(t.c_str());std::string n(b);while(!n.empty()&&(n.back()=='\n'||n.back()=='\r'))n.pop_back();injectRequested=n;injectReplayCommands();}
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_requestReplay(JNIEnv* env,jclass,jstring name){const char* c=env->GetStringUTFChars(name,nullptr);replayRequested=c;env->ReleaseStringUTFChars(name,c);}
static void startReplay(){
 std::string name=replayRequested;replayRequested.clear();replayFile=name;
 P controller=q(rendererBase+0x1838e88);if(!controller){plog("replay: no controller");return;}
 if(access((filesDir+"/"+name).c_str(),R_OK)!=0){plog("replay: missing "+name);return;}
 size_t size=0;P raw=readLocal(name.c_str(),"home.json",size);if(!raw||size<2){plog("replay: cannot read "+name);return;}
 ScString* json=reinterpret_cast<ScString*>(block(16));json->capacity=-1;json->length=static_cast<int32_t>(size);json->pointer=reinterpret_cast<const char*>(raw);
 // The client's own recorded-replay receiver b7fb6c(controller, &json, &extra): stores the JSON at +0x190, derives the replay type (+0x114),
 // sets the pending kind 5 (replay) and starts the cloud transition; b7e0c0 then loads and plays it.
 // The cloud transition re-initialises the controller with a new game mode, so the replay is handed over once the new mode exists (replayJson, see the tick).
 replayJson=reinterpret_cast<P>(json);replayOldController=q(controller+0x160);  // old game mode
 externalEdit=true;replayActive=true;
 // Close the clouds through the GUI manager like any village change, but without a pending avatar: its frontend switch (b8bbdc) then skips
 // the village load (gui+0x50 == 0) and the controller, finding the pending replay, plays it once the clouds are closed.
 P manager=q(rendererBase+0x1832ed8);
 if(manager){size_t homeSize;P homeRaw=readLocal("save-home.json","home.json",homeSize);P home=block(0x78);call(0xefbb58,home);ScString* hj=reinterpret_cast<ScString*>(block(16));hj->capacity=-1;hj->length=homeSize;hj->pointer=reinterpret_cast<const char*>(homeRaw);call(0xefbcb8,home,hj);
  P calendar=block(16),events=block(16);call(0x11fce50,calendar);call(0x11fce50,events);call(0xfcb698,home+0x38,calendar);call(0xfcb698,home+0x50,events);
  int now=static_cast<int>(time(nullptr));P nullA=0,nullB=0;
  reinterpret_cast<void(*)(P,P,P*,P*,int,int,int,int,P,P,P,P,P,P,P)>(rendererBase+0xb8c768)(manager,home,&nullA,&nullB,now,1,0,0,block(8),0,static_cast<P>(now),0,0,block(16),0);
  call(0xb8d264,manager,now);}
 plog("replay: started "+name+" bytes="+std::to_string(size));
}
// Offline tools (Java) edit the save files: flush the current state once, then stop autosaving until the relaunch.
static bool externalEditRequested,externalEditReady;
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_requestSaveAndPause(JNIEnv*,jclass){externalEditReady=false;externalEditRequested=true;}
extern "C" JNIEXPORT jboolean JNICALL Java_lab_coc_offline_OfflineTools_isSaveAndPauseDone(JNIEnv*,jclass){return externalEditReady;}
static bool localAttackRequested;
static volatile bool matchmakingWaiting;
// Open popups: popup manager (global 1832f98) keeps them in a vector at +0x178/+0x180 (as in 8723e4 "any popup open").
extern "C" JNIEXPORT jint JNICALL Java_lab_coc_offline_OfflineTools_openPopupCount(JNIEnv*,jclass){P m=rendererBase?q(rendererBase+0x1832f98):0;return m?static_cast<jint>((q(m+0x180)-q(m+0x178))/8):-1;}
extern "C" JNIEXPORT jboolean JNICALL Java_lab_coc_offline_OfflineTools_isMatchmakingWaiting(JNIEnv*,jclass){return matchmakingWaiting;}
// Diagnostic: find retained ClientHome objects (+0x20 = ScString* JSON starting with {"npc_maps_seen") reachable from GUI/game globals, two levels deep.
static bool readable(P a,void* out,size_t n){struct iovec l{out,n},r{reinterpret_cast<void*>(a),n};return a>0x10000&&!(a&7)&&process_vm_readv(getpid(),&l,1,&r,1,0)==static_cast<ssize_t>(n);}
static bool isClientHome(P h){P js;if(!readable(h+0x20,&js,8))return false;int32_t len;P txt;if(!readable(js+4,&len,4)||len<16||!readable(js+8,&txt,8))return false;char head[16];if(!readable(txt,head,15))return false;return memcmp(head,"{\"npc_maps_seen",15)==0;}
static void findClientHomes(){
 struct Root{const char* name;P addr;} roots[]={{"gamemain",rendererBase+0x1832cb0},{"ctrl",rendererBase+0x1838e88},{"gui",rendererBase+0x1832ed8},{"holder",rendererBase+0x1832e98}};
 for(auto& r:roots){P o=q(r.addr);if(!o)continue;for(int i=0;i<0x800;i+=8){P a;if(!readable(o+i,&a,8))continue;if(isClientHome(a)){plog(std::string("ClientHome at ")+r.name+"+"+std::to_string(i));continue;}
  for(int j=0;j<0x400;j+=8){P b;if(!readable(a+j,&b,8))continue;if(isClientHome(b))plog(std::string("ClientHome at ")+r.name+"+"+std::to_string(i)+"->+"+std::to_string(j));}}}
}
static const int ATTACK_TYPE=5;
static void startLocalAttack(){
 // Answer to the original matchmaking ("Подбор" -> "Идёт подбор противников", step global 17885a0 == 2, home GameMode already destroyed):
 // The live avatar is gone at this point, so the attacker is decoded from the latest autosave; the target is the saved home (active layout).
 P manager=q(rendererBase+0x1832ed8);if(!manager){stage("Local attack refused: no GUI manager");return;}
 // Attacker: attack-avatar.bin (friendly-challenge army list replaced by the main army, written by OfflineTools), else the latest autosave.
 P attacker=block(0x800);call(0xcd2cd4,attacker);avatarReplay::fileOverride="attack-avatar.bin";bool decoded=avatarReplay::decodeInto(attacker,true);avatarReplay::fileOverride=nullptr;
 if(!decoded){attacker=block(0x800);call(0xcd2cd4,attacker);if(!avatarReplay::decodeInto(attacker,true)){stage("Local attack refused: attacker avatar decode failed");return;}}
 // Target village: attack-home.json prepared by OfflineTools (chosen layout moved to slot 0, friendly army = main army), else the saved home.
 size_t homeSize;P homeRaw=readLocal("attack-home.json","home.json",homeSize);P home=block(0x78);call(0xefbb58,home);ScString* json=reinterpret_cast<ScString*>(block(16));json->capacity=-1;json->length=homeSize;json->pointer=reinterpret_cast<const char*>(homeRaw);call(0xefbcb8,home,json);
 P calendar=block(16),events=block(16);call(0x11fce50,calendar);call(0x11fce50,events);call(0xfcb698,home+0x38,calendar);call(0xfcb698,home+0x50,events);
 attackAvatar=attacker;battleActive=true;int now=static_cast<int>(time(nullptr));P attackerHolder=attacker;
 stage("Local attack: matchmaking answered through b8c768 mode 2 type "+std::to_string(ATTACK_TYPE)+" (target = saved home)");plog("attack: matchmaking answered");
 // Multiplayer attack (dispatcher branch ba8a1c): b8c768 mode 2, attack type w6 = 5 (6 when the message flag f7b428 is set), defender = ClientAvatar of the target village.
 // Gives the full home-village battlefield with timer and defender/attacker banners. Mode 1 (EnemyHomeData ba7b60) loads but never starts; mode 4 shows the scout view; NPC mode 2/type 0 uses the small single-player map.
 // Defender: defense-avatar.bin (the save with the sandbox's chosen defending clan castle troops, written by OfflineTools), else the save.
 P defender=block(0x800);call(0xcd2cd4,defender);bool defense=access((filesDir+"/defense-avatar.bin").c_str(),R_OK)==0;
 if(defense){avatarReplay::fileOverride="defense-avatar.bin";defense=avatarReplay::decodeInto(defender,true);avatarReplay::fileOverride=nullptr;}
 if(!defense){defender=block(0x800);call(0xcd2cd4,defender);avatarReplay::decodeInto(defender,true);}
 P defenderHolder=defender;battleDefender=defender;
 reinterpret_cast<void(*)(P,P,P*,P*,int,int,int,int,P,P,P,P,P,P,P)>(rendererBase+0xb8c768)(manager,home,&defenderHolder,&attackerHolder,now,2,ATTACK_TYPE,0,block(8),0,0,0,0,block(16),0);
 stage("Local attack: b8c768 returned");
}
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_RenderActivity_requestLocalAttack(JNIEnv*,jclass){localAttackRequested=true;}
// Dataset generator fast cycle: a new local attack straight from a running one (no surrender / home screen). The GUI manager's village change
// b8c768 (kind 2) is the call startLocalAttack makes from matchmaking; from inside a battle the transition is started explicitly with b8d264, as go-home does.
static bool restartAttackRequested;
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_requestRestartAttack(JNIEnv*,jclass){restartAttackRequested=true;}
static void restartLocalAttack(){
 P manager=q(rendererBase+0x1832ed8);if(!manager){plog("restart attack: no GUI manager");return;}
 plog("restart attack: from battle");startLocalAttack();call(0xb8d264,manager,static_cast<int>(time(nullptr)));
}
extern "C" JNIEXPORT jboolean JNICALL Java_lab_coc_offline_OfflineTools_isBattleActive(JNIEnv*,jclass){return battleActive||localAttackRequested;}
// Runtime code patches (code is decrypted in memory, original bytes verified).
static bool patchCode(P address,uint32_t expected,uint32_t replacement){
 uint32_t* insn=reinterpret_cast<uint32_t*>(address);if(*insn!=expected)return false;
 P page=address&~static_cast<P>(4095);if(mprotect(reinterpret_cast<void*>(page),4096,PROT_READ|PROT_WRITE|PROT_EXEC))return false;
 *insn=replacement;__builtin___clear_cache(reinterpret_cast<char*>(address),reinterpret_cast<char*>(address+4));mprotect(reinterpret_cast<void*>(page),4096,PROT_READ|PROT_EXEC);return true;
}
static uint32_t branchTo(P from,P to){return 0x14000000u|((static_cast<uint32_t>((to-from)>>2))&0x3ffffffu);}
static int sandboxPatches;  // applied patch count (10 = all)
static void patchLayoutSlots(){
 // The layout editor keeps the game's own tabs: home slots on the home tab, war slots on the war tab (no slot-list patch).
 int ok=0;P b=rendererBase;
 // Star bonus widget updater (8f89b0, only caller the attack-menu update 8f5904) re-shows the stars every frame; the sandbox has no star bonus, so it returns at once.
 ok+=patchCode(b+0x8f89b0,0xd10203ffu,0xd65f03c0u);
 // Downloadable (_dl) content: the per-file readiness check ba0164 (skin/scenery models via bf3310, skin icons, event art) waits for the
 // content downloader, which never starts offline. All _dl files are already in update/, so report every file as ready (mov w0,#1; ret).
 ok+=patchCode(b+0xba0164,0xd10183ffu,0x52800020u);ok+=patchCode(b+0xba0168,0xa9027bfdu,0xd65f03c0u);
 // War-slot test ed00c4 (slots 1,4,5,8..11, mask 0xf32): editing such a slot takes the "war base" branch, which needs the war-base home the
 // server sends (game+0x598) and otherwise shows the main village. Offline there is no war base, so all slots (war tab too) are edited
 // like home layouts (mov w0,#0; ret); positions come from each building's per-slot layout data as for slots 0,2,3.
 ok+=patchCode(b+0xed00c4,0x5281e648u,0x52800000u);ok+=patchCode(b+0xed00c8,0x7100301fu,0xd65f03c0u);
 // Battle fast forward (the original x2/x4 button of the battle HUD): available from the start of every battle instead of after the
 // cooldown f14e38 (battle tick - start tick > 59): mov w0,#1; ret.
 ok+=patchCode(b+0xf14e38,0xb943f809u,0x52800020u);ok+=patchCode(b+0xf14e3c,0xb940600au,0xd65f03c0u);
 // Replays: the replay recorder (mode+0x50) gets the player's commands only for battle types in mask 0x1204 (f1d4e8); the sandbox attack
 // is type 3, so the mask also takes bit 3 (mov w10,#0x120c) and every sandbox battle is recorded with its commands.
 ok+=patchCode(b+0xf1d4f0,0x5282408au,0x5282418au);
 // f1d4e8 also needs an attacker check that fails offline; fa4fd8 is the client's "always record replay" switch (mov w0,wzr -> mov w0,#1).
 ok+=patchCode(b+0xfa4fd8,0x2a1f03e0u,0x52800020u);
 // Top battle HUD update (8957cc): the TID_TOP_HUD_FRIENDLY_BATTLE label (hud+0x28) is shown for friendly battles (f03604), which every
 // sandbox battle and replay is; keep it hidden (strb w8,[x9,#8] -> strb wzr,[x9,#8]).
 ok+=patchCode(b+0x8958e8,0x39002128u,0x3900213fu);
 sandboxPatches+=ok;stage("Sandbox code patches: "+std::to_string(ok)+"/10");plog("sandbox code patches "+std::to_string(ok)+"/10");
}
// Sandbox attack menu (popup ctor 8f1c00, "attack_menu_2025"): hide the single-player/training tabs, the ranked card and the star bonus bar (popup+0x398 / +0x3a0),
// keep "Сражение"/"Подбор" and "Изменить армию". Children are found with the popup's own recursive name lookup 1072d0c and hidden via the display-object visible byte (+8).
static bool readable(P a,void* out,size_t n);
static volatile bool attackMenuOpen,layoutEditorRequested;


static P rq(P a){P v=0;return readable(a,&v,8)?v:0;}
// Selection path used by the original isObjectSelected callback (7e7f84):
// selector global1837688 -> +20 -> selected visual+c8 -> logic object+8.
// Only publish immutable IDs to Java; never expose a live pointer to the UI thread.
static std::atomic<jlong> selectedBuildingSnapshot{0};
static void sampleSelectedBuilding(){
 P selector=rq(rendererBase+0x1837688),state=selector?rq(selector+0x20):0;
 P visual=state?rq(state+0xc8):0,object=visual?rq(visual+8):0;
 P data=object?rq(object+0x18):0;int dataId=0,id=0;jlong value=0;
 if(data&&readable(data+0x18,&dataId,4)&&dataId>=1000000&&dataId<2000000&&readable(object+0x50,&id,4)&&id>=500000000&&id<600000000)
  value=static_cast<jlong>((static_cast<uint64_t>(dataId)<<32)|static_cast<uint32_t>(id));
 jlong old=selectedBuildingSnapshot.exchange(value);
 if(value&&old!=value)stage("Selected building: id="+std::to_string(id)+" data="+std::to_string(dataId));
}
extern "C" JNIEXPORT jlong JNICALL Java_lab_coc_offline_OfflineTools_selectedBuilding(JNIEnv*,jclass){return selectedBuildingSnapshot.load();}
static bool clipNamed(P parent,int index,const char* name){P names=rq(parent+0x98);if(!names)return false;P nm=rq(names+index*8);char t[40]={0};return nm&&readable(nm,t,39)&&strcmp(t,name)==0;}
static bool hasChildNamed(P clip,const char* name,int depth){
 int16_t n=0;if(!clip||depth<0||!readable(clip+0xc0,&n,2)||n<=0||n>200)return false;P kids=rq(clip+0x90);if(!kids)return false;
 for(int i=0;i<n;i++){if(clipNamed(clip,i,name))return true;P kid=rq(kids+i*8);if(kid&&hasChildNamed(kid,name,depth-1))return true;}return false;
}
// Hide every child of clip except the branch that leads to the child named keep (descending through wrappers).
static void hideAllExcept(P clip,const char* keep,int depth){
 int16_t n=0;if(!clip||depth<0||!readable(clip+0xc0,&n,2)||n<=0||n>200)return;P kids=rq(clip+0x90);if(!kids)return;
 for(int i=0;i<n;i++){P kid=rq(kids+i*8);if(!kid||clipNamed(clip,i,keep))continue;if(hasChildNamed(kid,keep,depth))hideAllExcept(kid,keep,depth-1);else hideClip(kid);}
}
static void dumpClip(P clip,int depth,std::string& out){
 if(!clip||depth>4||out.size()>6000)return;int16_t n;if(!readable(clip+0xc0,&n,2)||n<0||n>200)return;P kids=q(clip+0x90),names=q(clip+0x98);if(!kids)return;
 for(int i=0;i<n;i++){P kid,nm=0;if(!readable(kids+i*8,&kid,8)||!kid)continue;if(names)readable(names+i*8,&nm,8);char t[32]={0};if(nm)readable(nm,t,31);for(char& c:t)if(c&&(c<32||c>126))c='?';
  out+=std::string(depth,'.')+(t[0]?t:"_")+"("+std::to_string(*reinterpret_cast<uint8_t*>(kid+8))+") ";dumpClip(kid,depth+1,out);}
}
static const float PANEL_WIDTH=0.56f; // card width + margins relative to the original two-card panel
static void sandboxAttackMenu(){
 P pm=q(rendererBase+0x1832f98);bool open=false;if(!pm){attackMenuOpen=false;return;}
 // Vector entries point at the popup sub-object at +0xc8 (the one passed to 86fd78).
 for(P it=q(pm+0x178),end=q(pm+0x180);it&&it<end;it+=8){P popup=q(it);if(!popup)continue;popup-=0xc8;
  if(q(popup)!=rendererBase+0x16dcfb8)continue; // attack menu vtable
  open=true;
  // tab_2 / tab_3 buttons (+0x250/+0x258), ranked card (+0x2b8), star bonus bar and text (+0x398/+0x3a0) — fields set by ctor 8f1c00, 9f687c-style card setup and 8f3298.
  for(int off:{0x250,0x258,0x2b8})hideClip(q(popup+off));
  // Narrow panel: the ranked card is gone, so the wooden background clip (matrix a at +0x10) and the tab strip are shrunk horizontally once per popup.
  // Background is scaled around its centre, so tx (+0x20) is shifted left by the lost half width to keep the left edge in place (width from the clip bounds getter vslot 0x60).
  {static P narrowed;if(narrowed!=popup){narrowed=popup;P c=findChild(popup,"background");if(c){float* m=reinterpret_cast<float*>(c+0x10);float w=reinterpret_cast<float(*)(P)>(q(q(c)+0x60))(c);
    if(m[0]>0.9f){float lost=w*(1-PANEL_WIDTH)*0.5f;m[0]*=PANEL_WIDTH;m[4]-=lost;char b[96];snprintf(b,sizeof(b),"attack menu narrowed w=%.1f tx=%.1f",w,m[4]);plog(b);}}}}
  // Tab strip (local x from -760 to 0): stretch strips are scaled towards the left edge, decorations beyond the new right edge hidden.
  {static P tabsDone;P tabs=findChild(popup,"tabs");if(tabs&&tabsDone!=popup){tabsDone=popup;const float left=-760,right=left+760*PANEL_WIDTH;int16_t n=0;readable(tabs+0xc0,&n,2);P kids=rq(tabs+0x90);
   for(int i=0;i<n&&i<40&&kids;i++){P k=rq(kids+i*8);if(!k||clipNamed(tabs,i,"tab_1"))continue;float* mm=reinterpret_cast<float*>(k+0x10);float w=reinterpret_cast<float(*)(P)>(q(q(k)+0x60))(k);
    if(w>500){mm[0]*=PANEL_WIDTH;mm[4]=left+(mm[4]-left)*PANEL_WIDTH;}else if(mm[4]>right-60)hideClip(k);}}}
  // Star bonus: the active bottom bar (a root child holding StarBonusActive, star1..5, the "i"/label and button_edit_army) — hide every direct child except the army button branch.
  {P root=rq(popup+0x90);int16_t n=0;readable(root+0xc0,&n,2);P kids=rq(root+0x90);
   for(int i=0;i<n&&i<64&&kids;i++){P bar=rq(kids+i*8);if(!bar||!hasChildNamed(bar,"StarBonusActive",1))continue;int16_t m=0;readable(bar+0xc0,&m,2);P sub=rq(bar+0x90);
    (void)m;(void)sub;hideAllExcept(bar,"button_edit_army",4);}}
 }
 attackMenuOpen=open;
}
// Layout editor popup as opened by the game (7c4bf0): new 0x240, ctor ca505c(popup, -1), popup manager 86fd78(mgr, popup+0xc8, 1, 1, 0, 0).
// Diagnostic: skin selector state (update b07b50: entry vector +0x180 stride 0x40, index +0x1c0, preview +0x2a0, stage +0x1d0).
static void debugSkinPopup(){
 P pm=rq(rendererBase+0x1832f98);if(!pm)return;static std::string last;std::string out;
 for(P it=rq(pm+0x178),end=rq(pm+0x180);it&&it<end;it+=8){P popup=rq(it);if(!popup)continue;popup-=0xc8;P vt=rq(popup);char b[512];
  int idx=0,stage=0;float f1cc=0,f100=0;readable(popup+0x1c0,&idx,4);readable(popup+0x1d0,&stage,4);readable(popup+0x1cc,&f1cc,4);readable(popup+0x100,&f100,4);
  P entries=rq(popup+0x180),e[8]={0};if(entries&&idx>=0&&idx<1000)readable(entries+idx*0x40,e,64);int dl=-1;if(e[2])readable(e[2]+0x18,&dl,4);
  snprintf(b,sizeof(b),"popup vt=%lx idx=%d stage=%d f1cc=%.2f f100=%.2f prev=%lx e=%lx,%lx,%lx,%lx,%lx,%lx,%lx,%lx dl=%d; ",(unsigned long)(vt-rendererBase),idx,stage,f1cc,f100,(unsigned long)rq(popup+0x2a0),(unsigned long)e[0],(unsigned long)e[1],(unsigned long)e[2],(unsigned long)e[3],(unsigned long)e[4],(unsigned long)e[5],(unsigned long)e[6],(unsigned long)e[7],dl);out+=b;}
 if(out!=last){last=out;plog("skin "+out);}
}
// Diagnostic for the layout-recognition generator: files/gen-dump.txt -> files/gen-dump-N.bin with 0x1000 bytes of every object reachable
// (two pointer levels) from GameMain / controller / GUI manager, to locate the battle camera (zoom, position) by diffing two zoom levels.
static void genMemoryDump(){
 if(filesDir.empty())return;std::string trig=filesDir+"/gen-dump.txt";if(access(trig.c_str(),F_OK)!=0)return;unlink(trig.c_str());static int dumps=0;int n=++dumps;
 FILE* f=fopen((filesDir+"/gen-dump-"+std::to_string(n)+".bin").c_str(),"wb");if(!f)return;
 P roots[3]={rq(rendererBase+0x1832cb0),rq(rendererBase+0x1838e88),rq(rendererBase+0x1832ed8)};std::vector<P> seen;uint8_t buf[0x1000];
 auto dump=[&](P o,int tag,int path){if(o<0x10000||(o&7))return;for(P x:seen)if(x==o)return;seen.push_back(o);if(!readable(o,buf,sizeof(buf)))return;uint64_t head[3]={o,static_cast<uint64_t>(tag),static_cast<uint64_t>(path)};fwrite(head,8,3,f);fwrite(buf,1,sizeof(buf),f);};
 for(int r=0;r<3;r++){P o=roots[r];dump(o,r,-1);for(int i=0;i<0x800;i+=8){P a=rq(o+i);dump(a,r,i);for(int j=0;j<0x400;j+=8){P b=rq(a+j);dump(b,r,(i<<16)|j);}}}
 fclose(f);plog("gen dump "+std::to_string(n)+" objects="+std::to_string(seen.size()));
}
static void openLayoutEditor(){
 P pm=q(rendererBase+0x1832f98);if(!pm)return;P popup=reinterpret_cast<P(*)(size_t)>(rendererBase+0x16b2f40)(0x240);call(0xca505c,popup,-1);
 reinterpret_cast<void(*)(P,P,int,int,P,int)>(rendererBase+0x86fd78)(pm,popup+0xc8,1,1,0,0);plog("layout editor opened from attack menu");
}
extern "C" JNIEXPORT jboolean JNICALL Java_lab_coc_offline_OfflineTools_isAttackMenuOpen(JNIEnv*,jclass){return attackMenuOpen;}
extern "C" JNIEXPORT void JNICALL Java_lab_coc_offline_OfflineTools_requestLayoutEditor(JNIEnv*,jclass){layoutEditorRequested=true;}
// Diagnostic: files/edit-layout.txt "<slot>" enters the editor for that slot exactly as the layout popup's "edit" button does
// (ca44d8 home branch: layout manager 1832fc8, cab9a4(manager, slot, 0)), and logs the slot the editor stored.
static void editLayoutHook(){
 if(filesDir.empty())return;std::string path=filesDir+"/edit-layout.txt";FILE* f=fopen(path.c_str(),"r");if(!f)return;int slot=-1;if(fscanf(f,"%d",&slot)!=1)slot=-1;fclose(f);unlink(path.c_str());
 P mgr=q(rendererBase+0x1832fc8);if(!mgr||slot<0)return;reinterpret_cast<void(*)(P,int,int)>(rendererBase+0xcab9a4)(mgr,slot,0);
 plog("edit-layout hook slot="+std::to_string(slot)+" mgr.slot="+std::to_string(*reinterpret_cast<int*>(mgr+0x30))+" global.slot="+std::to_string(*reinterpret_cast<int*>(q(rendererBase+0x1836308)+0x6c)));
}
// Diagnostic (files/startup-dump-on): every 15 frames before the submit, the frontend object and every object it points to (0x400 bytes
// each) are appended to files/startup-dump.bin as {u64 frame, u64 parent offset, bytes}, to find the start-up loading progress.
static void startupDump(P fm,int frame){
 static int on=-1;if(on<0)on=!filesDir.empty()&&access((filesDir+"/startup-dump-on").c_str(),F_OK)==0;if(!on||!fm)return;
 FILE* f=fopen((filesDir+"/startup-dump.bin").c_str(),"ab");if(!f)return;uint8_t buf[0x400];
 auto put=[&](P o,uint64_t off){if(!readable(o&0x00ffffffffffffffull,buf,sizeof(buf)))return;uint64_t h[2]={static_cast<uint64_t>(frame),off};fwrite(h,8,2,f);fwrite(buf,1,sizeof(buf),f);};
 put(fm,0xffff);for(int o=0;o<0x400;o+=8){P c=q(fm+o);if((c>>56)==0xb4)put(c,o);}fclose(f);
}
static bool localHomeSubmitted;
static void submitLocalHome(){
 base=rendererBase;P controller=q(base+0x1838e88);if(!controller){P frontend=q(base+0x1832ed8);if(!frontend){stage("Local home refused: frontend manager unavailable");return;}stage("Local home: enter original frontend mode 1 from mode "+std::to_string(*reinterpret_cast<int*>(frontend+8)));call(0xb8b94c,frontend,1);controller=q(base+0x1838e88);stage("Local home: frontend mode transition returned");}if(!controller||q(controller)!=base+0x1710478){stage("Local home refused: unexpected frontend controller assigned="+std::to_string(controller!=0));return;}
 P mode=q(controller+0x160);if(!mode||*reinterpret_cast<int*>(mode)!=0||q(controller+0xf8)){stage("Local home refused: frontend already owns a home or active mode");return;}
 base=rendererBase;P source=q(base+0x1844678);if(!source||!q(source+8)){stage("Local home refused: table source unavailable");return;}
 stage("Local home: construct fresh ClientAvatar and ClientHome");P avatar=block(0x800);call(0xcd2cd4,avatar);if(!avatarReplay::decodeInto(avatar,true)){stage("Local home: saved avatar rejected, replaying captured avatar");avatar=block(0x800);call(0xcd2cd4,avatar);if(!avatarReplay::decodeInto(avatar,false)){stage("Local home: captured avatar rejected, using fresh avatar");avatar=block(0x800);call(0xcd2cd4,avatar);}}liveAvatar=avatar;{std::vector<uint8_t> e;avatarRecord::encode(avatar,e);size_t sz;P raw=readLocal("save-avatar.bin","avatar-ops.bin",sz);std::vector<uint8_t> f(reinterpret_cast<uint8_t*>(raw),reinterpret_cast<uint8_t*>(raw)+sz);logAvatarDiff("file vs decoded avatar at load",f,e);}P home=block(0x78);call(0xefbb58,home);
 size_t homeSize;P homeRaw=readLocal("save-home.json","home.json",homeSize);ScString* json=reinterpret_cast<ScString*>(block(16));json->capacity=-1;json->length=homeSize;json->pointer=reinterpret_cast<const char*>(homeRaw);call(0xefbcb8,home,json);
 P calendar=block(16),events=block(16);call(0x11fce50,calendar);call(0x11fce50,events);call(0xfcb698,home+0x38,calendar);call(0xfcb698,home+0x50,events);
 // Server login normally sets GameMain+0x230 = account LogicLong{high,low} via setter 65db18; GUI update 65e298 derefs it. Use a local account id 0-1, allocated with libg's operator new (setter frees with 16b2f20).
 {P gameMain=q(base+0x1832cb0);if(gameMain&&!q(gameMain+0x230)){int* accountId=reinterpret_cast<int*>(reinterpret_cast<P(*)(size_t)>(base+0x16b2f40)(8));accountId[0]=0;accountId[1]=1;if(!avatarReplay::ops.empty()&&avatarReplay::ops[0].method==0xa8&&avatarReplay::ops[0].present&&!avatarReplay::mismatch){accountId[0]=avatarReplay::ops[0].words[0];accountId[1]=avatarReplay::ops[0].words[1];}call(0x65db18,gameMain,reinterpret_cast<P>(accountId));stage("Local home: account id set to "+std::to_string(accountId[0])+"-"+std::to_string(accountId[1]));}}
 // 5th argument = OwnHomeData +0x90, the server timestamp of the home snapshot (0 made absolute deadlines read as 1970: "available in 20727 days").
 // OwnHomeData handler ba820c also feeds the server clock to the GUI manager: b8d264(gui, msg+0xa4) -> gui+0x8c int / +0x90 double. Without it the attack menu counts deadlines from 0.
 call(0xb8d264,q(base+0x1832ed8),static_cast<int>(time(nullptr)));
 stage("Local home: submit through original frontend entry 0xb7fe94");localHomeSubmitted=true;
 reinterpret_cast<void(*)(P,P,P,P,int,int,int,int,P,P,P,P)>(base+0xb7fe94)(controller,home,avatar,0,static_cast<int>(time(nullptr)),1,0,0,block(8),0,block(8),0);
 stage("Local home submitted; frontend kind="+std::to_string(*reinterpret_cast<int*>(controller+0x128))+", mode="+std::to_string(*reinterpret_cast<int*>(mode)));
}
extern "C" JNIEXPORT jint JNICALL Java_lab_coc_offline_RenderActivity_observeNativeRuntime(JNIEnv*,jclass){
 static int frames=0;int current=++frames;
 // Local home is live once the frontend accepted it: mode 1, no pending home, load progress 1.
 // GameMain+0x190/0x194 hold the error set by GameMain::showError 65b324 (connection failure of the abandoned network-loading mode); while non-zero the controller logic update b81658 never ticks, so it is reset to its constructor value when the local home goes live.
 static bool homeLive=false;auto localHomeState=[&]{return restartRequested?3:homeLive?2:localHomeSubmitted?1:0;};if(rendererBase&&localHomeRequested&&!homeLive&&submitFrame>=0&&current>submitFrame){P controller=q(rendererBase+0x1838e88);P mode=controller?q(controller+0x160):0;if(mode&&*reinterpret_cast<int*>(mode)==1&&!q(controller+0xf8)&&*reinterpret_cast<float*>(controller+0x2d4)>=1.f){homeLive=true;stage("Local home live at frame "+std::to_string(current));{static bool slotsPatched;if(!slotsPatched){slotsPatched=true;if(sandboxPatches<10)patchLayoutSlots();}}{std::vector<uint8_t> e;avatarRecord::encode(liveAvatar,e);size_t sz;P raw=readLocal("save-avatar.bin","avatar-ops.bin",sz);std::vector<uint8_t> f(reinterpret_cast<uint8_t*>(raw),reinterpret_cast<uint8_t*>(raw)+sz);logAvatarDiff("file vs live avatar after home load",f,e);}{P gui=q(rendererBase+0x1832ed8);if(gui&&!guiHomeFieldsKnown){guiHomeFields[0]=*reinterpret_cast<int*>(gui+0x84);guiHomeFields[1]=*reinterpret_cast<int*>(gui+0x88);guiHomeFields[2]=*reinterpret_cast<int*>(gui+0x8c);guiHomeFieldsKnown=true;stage("GUI home fields 84="+std::to_string(guiHomeFields[0])+" 88="+std::to_string(guiHomeFields[1])+" 8c="+std::to_string(guiHomeFields[2]));}}{P gm=q(rendererBase+0x1832cb0);if(gm&&*reinterpret_cast<int*>(gm+0x190)){stage("Local home: clearing stale network error type="+std::to_string(*reinterpret_cast<int*>(gm+0x190))+" sub="+std::to_string(*reinterpret_cast<int*>(gm+0x194)));*reinterpret_cast<int*>(gm+0x190)=0;*reinterpret_cast<int*>(gm+0x194)=0;*reinterpret_cast<uint8_t*>(gm+0x198)=0;}}}}
 // The network manager keeps retrying and its connection-failed listener (vtable 16cb4d8 -> 7a57f4 -> GameMain::showError(1,0)) re-sets the error; offline there is no server, so keep it cleared while the local home is live.
 if(homeLive){P gm=q(rendererBase+0x1832cb0);if(gm&&*reinterpret_cast<int*>(gm+0x190)==1){*reinterpret_cast<int*>(gm+0x190)=0;*reinterpret_cast<int*>(gm+0x194)=0;*reinterpret_cast<uint8_t*>(gm+0x198)=0;}}
 if(homeLive&&localAttackRequested){localAttackRequested=false;startLocalAttack();}
 if(homeLive&&restartAttackRequested){P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;if(m&&*reinterpret_cast<int*>(m)==2&&battleActive&&!replayActive){restartAttackRequested=false;restartLocalAttack();}}
 // Original matchmaking ("Подбор" -> searching, step global 17885a0 == 2): Java shows the target-layout chooser, writes attack-home.json and calls requestLocalAttack.
 {int step=*reinterpret_cast<int*>(rendererBase+0x17885a0);matchmakingWaiting=homeLive&&step==2&&!battleActive;}
 if(homeLive){static bool goHomeSent;P controller=q(rendererBase+0x1838e88);P mode=controller?q(controller+0x160):0;int state=mode?*reinterpret_cast<int*>(mode):-1;int step=*reinterpret_cast<int*>(rendererBase+0x17885a0);if((state==2||(state==5&&replayActive))&&step==4&&!goHomeSent){goHomeSent=true;if(replayActive){replayActive=false;replayFile.clear();externalEdit=false;plog("replay: ended");if(!reloadHomeFromSave())restartRequested=true;}else{if(!rerunActive){mkdir((filesDir+"/replays").c_str(),0700);writeReplayJson("replays/"+std::to_string(time(nullptr))+".json");}rerunActive=false;returnHomeLocal();}}else if(state!=2&&state!=5)goHomeSent=false;}
 if(cloudsCleanupPending){P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;if(m&&*reinterpret_cast<int*>(m)==1&&!q(c+0xf8)){cloudsCleanupPending=false;if(!removeStaleClouds())stage("Local go-home: no stale clouds found");}}
 if(!replayRequested.empty()&&homeLive&&!battleActive&&!replayActive)startReplay();
 if(homeLive&&!battleActive&&!replayActive){applyLiveEdits();hideHomeHud();socialPopupTick();if(current%30==0)refillMagicItems();}else socialTabState=-1;
 // The replay's own "home" button asks the server; the sandbox exit reloads the village from the saves.
 if(replayExitRequested){replayExitRequested=false;if(replayActive){replayActive=false;replayJson=0;externalEdit=false;plog("replay: exit");if(!reloadHomeFromSave())restartRequested=true;}}
 if(reloadHomeRequested&&homeLive&&!battleActive){reloadHomeRequested=false;externalEdit=false;if(!reloadHomeFromSave())restartRequested=true;}
 if(externalEditRequested&&homeLive&&!battleActive){externalEditRequested=false;saveLocalState();externalEdit=true;externalEditReady=true;plog("offline tools: saved and paused autosave");}
 // After an in-place go-home the home GameMode loads (mode 1, nothing pending) but the battle-exit clouds stay, because the GUI update b8a1e4 only releases them
 // (b8b518(gui,0)) when globals(df6884)+0x2dd is clear; hide them with the transition's own cleanup 8e0154 (on gui+0x20) once the home is ready.
 if(goHomePending){P c=q(rendererBase+0x1838e88);P m=c?q(c+0x160):0;if(m&&*reinterpret_cast<int*>(m)==1&&!q(c+0xf8)&&*reinterpret_cast<float*>(c+0x2d4)>=1.f){if(cloudsReleaseAt<0)cloudsReleaseAt=current+CLOUDS_RELEASE_DELAY;else if(current>=cloudsReleaseAt){goHomePending=false;{
   // The GUI manager stays in its waiting mode (gui+8 == 2, object at gui+0x10 whose +0x20 holds the loaded home controller) until the server answers.
   // Its own switch b8b94c(gui, 1) takes that prepared controller when called in mode 2, so call it once the home is ready.
   P gui=q(rendererBase+0x1832ed8);if(gui&&*reinterpret_cast<int*>(gui+8)==2){call(0xb8b94c,gui,1);plog("go-home: GUI switched to home mode, guiMode="+std::to_string(*reinterpret_cast<int*>(gui+8)));}else plog("go-home: GUI not in waiting mode");}}}}
 genMemoryDump();
 if(homeLive&&!battleActive){if(current%6==0)sampleSelectedBuilding();sandboxAttackMenu();if(layoutEditorRequested){layoutEditorRequested=false;openLayoutEditor();}if(current%30==0)editLayoutHook();}else selectedBuildingSnapshot.store(0);
 // Autosave every 5 s; every second while a popup is open (army editor, attack menu), because "Подбор" unloads the home and the battle uses the last save.
 {P pmAuto=q(rendererBase+0x1832f98);bool popupOpen=pmAuto&&q(pmAuto+0x180)!=q(pmAuto+0x178);if(homeLive&&current%(popupOpen?60:300)==0)saveLocalState();}
 // Tick gate diagnostic (controller logic update b81658): home state ticks only if global 17885a0==1 and GameMain(1832cb0)+0x190==0 (Ghidra func_0x00758560 = ELF 658560); subtick at LogicGame+0x60.
 if(homeLive&&current%300==0){P controller=q(rendererBase+0x1838e88);P mode=controller?q(controller+0x160):0;P game=mode?q(mode+0x10):0;stage("Tick gate: conn="+std::to_string(*reinterpret_cast<int*>(rendererBase+0x17885a0))+" state="+std::to_string(mode?*reinterpret_cast<int*>(mode):-1)+" sync="+std::to_string(mode?*reinterpret_cast<uint8_t*>(mode+0xd):-1)+" subtick="+std::to_string(game?*reinterpret_cast<int*>(game+0x60):-1)+" gameMain190="+std::to_string(q(rendererBase+0x1832cb0)?*reinterpret_cast<int*>(q(rendererBase+0x1832cb0)+0x190):-1));}
 // RuntimeAsset manager global (ctor 770c98 from GameMain vslot 6595d0); loadHomeState path derefs it via 7721b8 without null check.
 static P lastRuntimeAsset=1,lastRuntimeAssetData=1;if(rendererBase){P rt=q(rendererBase+0x1832cc0),data=rt?q(rt+0x28):0;if(rt!=lastRuntimeAsset||data!=lastRuntimeAssetData){char line[96];snprintf(line,sizeof(line),"RuntimeAsset global=%llx +28=%llx frame=%d",(unsigned long long)rt,(unsigned long long)data,current);stage(line);lastRuntimeAsset=rt;lastRuntimeAssetData=data;}}
 frameNow=current;if(rendererBase&&battleActive&&!replayActive)applyBattleModifier();if(rendererBase&&current%10==0)injectHook();if(replayActive&&current%30==0){P rc=q(rendererBase+0x1838e88);P rm=rc?q(rc+0x160):0;static std::string last;std::string now="replay state mode="+std::to_string(rm?*reinterpret_cast<int*>(rm):-1)+" step="+std::to_string(*reinterpret_cast<int*>(rendererBase+0x17885a0))+" kind="+std::to_string(rc?*reinterpret_cast<int*>(rc+0x128):-1)+" f8="+std::to_string(rc&&q(rc+0xf8)?1:0)+" load="+std::to_string(rc?*reinterpret_cast<float*>(rc+0x2d4):-1.f)+" gui="+std::to_string(q(rendererBase+0x1832ed8)?*reinterpret_cast<int*>(q(rendererBase+0x1832ed8)+8):-1);if(now!=last){last=now;plog(now);}}
 // The replay mode is prepared behind the clouds while the GUI manager waits for a server answer (gui mode 2), as after go-home; switch it
 // with the GUI's own b8b94c once the replay mode exists.
 {P nc=q(rendererBase+0x1838e88);if(replayActive&&replayJson&&nc&&q(nc+0x160)&&q(nc+0x160)!=replayOldController){P jsonHolder=replayJson,extraHolder=0;replayJson=0;
   reinterpret_cast<void(*)(P,P*,P*)>(rendererBase+0xb7fb6c)(nc,&jsonHolder,&extraHolder);
   // b7e0c0 passes loadReplay mode 4 (live) for replay type 2 and mode 1 (recorded) otherwise; keep the type b7fb6c derived (not 2).
   if(*reinterpret_cast<int*>(nc+0x114)==2)*reinterpret_cast<int*>(nc+0x114)=0;plog("replay: handed to the new controller");}}
 // Live replays only (mode+0xd): mode+0x68 is the last tick available to play (-1 = whole recording; live data advances it) and the
 // controller passes 0 for a local recording, which froze it at tick 0. In a recorded replay (loadReplay f95310, mode+0xd == 0) mode+0x68
 // is m_currentTimestamp, the base of every new object's random seed (e8033c); it must keep the recorded value, or every object
 // created after the start (builders, troops) gets the seed of its predecessor and the replay drifts from the battle.
 {P c5=q(rendererBase+0x1838e88);P m5=c5?q(c5+0x160):0;bool live5=m5&&*reinterpret_cast<uint8_t*>(m5+0xd);
  if(replayActive&&m5&&*reinterpret_cast<int*>(m5)==5){if(live5&&*reinterpret_cast<int*>(m5+0x68)==0){*reinterpret_cast<int*>(m5+0x68)=-1;plog("replay: whole recording enabled");}
  // The replay plays as a live replay (mode+0xd): the client simulates only up to the last received tick, mode+0x58, which live data
  // messages advance. A local recording is complete, so the received tick is set beyond its end once.
  if(live5&&*reinterpret_cast<int*>(m5+0x58)==0){*reinterpret_cast<int*>(m5+0x58)=0x3fffffff;plog("replay: all ticks available");
   // The controller's replay load leaves the command list empty offline; queue the recorded commands in the replay mode itself.
   // loadReplay normally queues the recorded commands itself (cmd manager mode+0x18, count +0xc); only an empty queue gets them from the file,
   // so they are never doubled.
   {P mgr=q(m5+0x18);int queued=mgr?*reinterpret_cast<int*>(mgr+0xc):-1;plog("replay: game queued "+std::to_string(queued)+" commands");
    if(queued==0&&!replayFile.empty()){injectRequested=replayFile;injectReplayCommands();rerunActive=false;}}}
  // diagnostic: is the replay game ticking by itself?
  static int lastTick=-1;P g5=q(m5+0x10);int t5=g5?*reinterpret_cast<int*>(g5+0x60):-1;if(current%60==0&&t5!=lastTick){lastTick=t5;plog("replay tick "+std::to_string(t5));}
  if(replayTickAssist)for(int t=0;t<battleSpeed;t++)call(0xf93030,m5);}}
 {static int replayWait=-1;P rg=q(rendererBase+0x1832ed8);P rc2=q(rendererBase+0x1838e88);P rm2=rc2?q(rc2+0x160):0;
  if(replayActive&&rg&&*reinterpret_cast<int*>(rg+8)==2&&rm2&&*reinterpret_cast<int*>(rc2+0x128)==0){if(replayWait<0)replayWait=current+30;else if(current>=replayWait){replayWait=-1;call(0xb8b94c,rg,1);plog("replay: GUI switched, guiMode="+std::to_string(*reinterpret_cast<int*>(rg+8)));}}else if(!replayActive)replayWait=-1;}if(rendererBase&&current%30==0)replayDumpHook();if(rendererBase&&(battleActive||replayActive)){if(stateDumps)dumpBattleState();
  // The game's own battle speed (its fast-forward): the controller update (b806dc) runs a battle at the integer speed in the game object,
  // game+0x3f4 (game = mode+0x10); no extra simulation steps, so battles and their replays stay identical. A recorded replay (mode+0xd == 0)
  // runs at the speed of its own HUD speed button instead (b808a8), so the sandbox leaves replays to it.
  P bc=q(rendererBase+0x1838e88);P bm=bc?q(bc+0x160):0;P bg=bm?q(bm+0x10):0;int st=bm?*reinterpret_cast<int*>(bm):-1;
  if(bg&&st==2){int* speed=reinterpret_cast<int*>(bg+0x3f4);if(*speed!=battleSpeed&&*speed!=0)*speed=battleSpeed;}}if(rendererBase&&submitFrame<0&&current%15==0)startupDump(q(rendererBase+0x1832ed8),current);{static int lastMode=-99;static int loggedError=-1;P fm0=rendererBase?q(rendererBase+0x1832ed8):0;int m=fm0?*reinterpret_cast<int*>(fm0+8):-1;if(m!=lastMode){lastMode=m;plog("frontend mode "+std::to_string(m)+" at frame "+std::to_string(current));}int ce=connectionErrorFrame.load();if(ce>=0&&loggedError<0){loggedError=ce;plog("connection error dialog at frame "+std::to_string(ce));}}if(rendererBase&&localHomeRequested&&submitFrame<0&&current>=LOCAL_HOME_MIN_FRAME&&current%15==0){P fm=q(rendererBase+0x1832ed8);static long lastCalls=-1;static int idleSince=0;long calls=fileCalls.load();if(lastCalls<0||calls-lastCalls>30)idleSince=current;lastCalls=calls;  // >2 calls per frame = still loading (the idle client polls about 1 per frame)
   if(current%60==0)plog("start-up file calls "+std::to_string(calls)+" at frame "+std::to_string(current));
   if(current>=LOCAL_HOME_FRAME||(fm&&*reinterpret_cast<int*>(fm+8)==3&&current-idleSince>=LOCAL_HOME_IDLE_FRAMES)){submitFrame=current;plog("local home submit at frame "+std::to_string(current));if(sandboxPatches<10)patchLayoutSlots();/* before the home loads: its scenery is a deferred file checked by ba0164 */submitLocalHome();if(!localHomeSubmitted)submitFrame=-1;/* refused: retry */}}if(rendererBase&&localHomeRequested&&submitFrame>=0&&current>=submitFrame&&current%60==0){P controller=q(rendererBase+0x1838e88);if(controller){P mode=q(controller+0x160);stage("Local frontend callback="+std::to_string(current)+" pending="+std::to_string(q(controller+0xf8)!=0)+" mode="+std::to_string(mode?*reinterpret_cast<int*>(mode):-1)+" progress="+std::to_string(*reinterpret_cast<float*>(controller+0x2d4)));}}if(!rendererBase||(current!=1&&current!=60&&current!=120))return localHomeState();
 P source=q(rendererBase+0x1844678);if(!source){stage("Renderer tables not assigned at callback "+std::to_string(current));return localHomeState();}P manager=q(source+8);if(!manager){stage("Renderer table manager not assigned");return localHomeState();}
 int present=0,read=0,linked=0;for(int i=0;i<119;i++){P table=reinterpret_cast<P(*)(P,int)>(rendererBase+0xdf80a0)(manager,i);if(!table&&current==120)stage("Renderer missing table="+std::to_string(i));if(table&&current==120&&!*reinterpret_cast<uint8_t*>(table+0x61))stage("Renderer unlinked table="+std::to_string(i));if(table){present++;read+=*reinterpret_cast<uint8_t*>(table+0x60)!=0;linked+=*reinterpret_cast<uint8_t*>(table+0x61)!=0;}}
 stage("Renderer native tables callback="+std::to_string(current)+" present="+std::to_string(present)+" read="+std::to_string(read)+" linked="+std::to_string(linked));
 return localHomeState();
}
