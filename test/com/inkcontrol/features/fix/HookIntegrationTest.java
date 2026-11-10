package com.inkcontrol.features.fix;
import android.content.Context;
import android.os.Binder;
import com.android.server.EpdManagerService;
import com.android.server.wm.ActivityRecord;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
/** Host callback simulation. Does not validate actual Binder, ART inlining or device method names. */
public final class HookIntegrationTest {
    private static int checks;
    static void check(boolean yes,String name){checks++;if(!yes)throw new AssertionError(name);}
    static void denied(Object stub,String method,Object... args)throws Throwable {
        try{XposedBridge.call(stub,method,args);throw new AssertionError("not denied: "+method);}
        catch(SecurityException expected){checks++;}
    }
    public static void main(String[] args)throws Throwable {
        com.inkcontrol.platform.RuntimeConfig.enableFixes();
        XC_LoadPackage.LoadPackageParam pkg=new XC_LoadPackage.LoadPackageParam();
        pkg.packageName="android";pkg.classLoader=HookIntegrationTest.class.getClassLoader();new Hook().handleLoadPackage(pkg);
        Context context=new Context();EpdManagerService s=new EpdManagerService(context);XposedBridge.constructed(s);
        Object stub=s.mService;
        Binder.uid=1000;XposedBridge.call(stub,"begigResumeActivity","video.a","A",false);
        check("0".equals(android.os.SystemProperties.get("debug.inkcontrol.fusion.reader","unset")),
                "翻页动画闸门：名单为空时前台应用不放行");
        ActivityRecord a=new ActivityRecord(10001,"video.a","A");
        XposedBridge.call(a,"setState",ActivityRecord.State.RESUMED,"test");s.mHandler.drain();
        Binder.uid=10001;XposedBridge.call(stub,"notifyVideoOn",true,10001);s.mHandler.drain();
        check(s.mGray2NoFlickerReason.size()==1 && (s.mEpdMode&1024)!=0,"video bit on");
        for(int i=0;i<40;i++)XposedBridge.call(s,"notifyVideoOrCameraOn",1,"CAMERA_VIDEO_AGAIN",10001);
        check(s.mGray2NoFlickerReason.size()==1 && s.stockNotifyCalls==0,"retry never learns Activity or duplicates");
        XposedBridge.call(stub,"notifyVideoOn",true,10001);XposedBridge.call(stub,"notifyCameraOn",true,10001);s.mHandler.drain();
        XposedBridge.call(stub,"notifyVideoOn",false,10001);s.mHandler.drain();
        check(s.mGray2NoFlickerReason.contains("VIDEO_ON") && s.mGray2NoFlickerReason.contains("CAMERA_ON"),"parallel counts");
        XposedBridge.call(stub,"notifyVideoOn",false,10001);s.mHandler.drain();
        check(!s.mGray2NoFlickerReason.contains("VIDEO_ON") && s.mGray2NoFlickerReason.contains("CAMERA_ON"),"camera survives video end");
        XposedBridge.call(stub,"notifyCameraOn",false,10001);s.mHandler.drain();
        check(s.mGray2NoFlickerReason.isEmpty() && (s.mEpdMode&1024)==0,"all stop clears bit");
        check(s.mGray2NoFlickerActivity.isEmpty(),"no permanent learned Activity");
        XposedBridge.call(stub,"setEpdDisplayMode",3);check(s.calls==1 && Binder.uid==10001,"own temporary mode keeps identity");
        denied(stub,"setEpdDisplayMode",0x403);
        denied(stub,"addAppMode","video.a",3);denied(stub,"updateShutdownWallPaper",new Object());
        check(s.calls==1 && Binder.uid==10001,"denied entry never executes clear identity side effects");
        check(((Integer)XposedBridge.call(stub,"getEpdMode"))==3,"ordinary read retained");
        denied(stub,"begigResumeActivity","video.b","B",false);check("video.a".equals(s.mTopPackage),"caller cannot spoof top package");
        denied(stub,"notifyVideoOn",true,10002);check(s.mGray2NoFlickerReason.isEmpty(),"UID spoof denied before enqueue");
        Binder.uid=1013;XposedBridge.call(stub,"notifyVideoOn",true,10001);s.mHandler.drain();
        check(s.mGray2NoFlickerReason.contains("VIDEO_ON"),"native media notification");
        denied(stub,"addAppMode","video.a",6);
        Binder.uid=1000;XposedBridge.call(stub,"begigResumeActivity","video.b","B",false);
        XposedBridge.call(a,"setVisible",false);s.mHandler.drain();
        check(s.mGray2NoFlickerReason.isEmpty(),"switch hides old video");
        XposedBridge.call(a,"setVisible",true);s.mHandler.drain();
        check(s.mGray2NoFlickerReason.contains("VIDEO_ON"),"visible PiP survives other foreground");
        XposedBridge.call(a,"setState",ActivityRecord.State.DESTROYED,"test");s.mHandler.drain();
        check(s.mGray2NoFlickerReason.isEmpty(),"destroy cleans owned video");
        Binder.uid=10003;XposedBridge.call(stub,"addBitmapToPresentationDisplayWithString",null,0,3,0,false,null);
        context.pm.lyricCertificate=false;
        denied(stub,"addBitmapToPresentationDisplayWithString",null,0,3,0,false,null);
        check(s.calls==2,"wrong lyric certificate denied without image mutation");
        Binder.uid=10050;XposedBridge.call(stub,"updateShutdownWallPaper",new Object());check(s.calls==3,"platform-signed client retained");
        com.inkcontrol.platform.RuntimeConfig.disableAll();
        Binder.uid=10001;XposedBridge.call(stub,"addAppMode","video.a",3);
        check(s.calls==4,"permission switch off restores original method");
        System.out.println("PASS HookIntegrationTest checks="+checks+" (simulated callbacks, no device)");
    }
}
