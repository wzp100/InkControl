package com.inkcontrol.features.desktop;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import com.inkcontrol.platform.RuntimeConfig;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Hook implements IXposedHookLoadPackage {
    static Context context;
    static Bundle state=new Bundle();
    static String theme="glass",kind="clock";
    static boolean enabled;
    static Handler handler;
    private static WeakReference<Activity> activity=new WeakReference<>(null);
    private static final WeakHashMap<TextView,Original> originals=new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup,ThemeWidgetView> widgets=new WeakHashMap<>();
    private static ClassLoader loader;
    private static boolean initialized;
    private static final java.util.Set<Class<?>> observedClockClasses=new java.util.HashSet<>();
    private static final class Original {
        Drawable icon,styled;
        Object script;
        int day;
        String title,theme;
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p)throws Throwable{
        if("com.android.systemui".equals(p.packageName)&&"com.android.systemui".equals(p.processName)){
            XposedHelpers.findAndHookMethod("android.app.Application",p.classLoader,"attach",Context.class,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){MediaBridge.start((Context)m.args[0]);}});return;
        }
        if(!"com.hmct.vision".equals(p.packageName)||!p.packageName.equals(p.processName))return;
        loader=p.classLoader;
        XposedHelpers.findAndHookMethod("android.app.Application",loader,"attach",Context.class,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){attach((Context)m.args[0]);}});
        XposedHelpers.findAndHookMethod("com.hmct.vision.Launcher",loader,"onCreate",Bundle.class,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){activity=new WeakReference<>((Activity)m.thisObject);handler.postDelayed(Hook::refresh,500);}});
        XposedHelpers.findAndHookMethod("com.hmct.vision.Launcher",loader,"onResume",new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){activity=new WeakReference<>((Activity)m.thisObject);handler.post(Hook::refresh);}});
        XposedHelpers.findAndHookMethod("com.hmct.vision.Launcher",loader,"onDestroy",new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){if(activity.get()==m.thisObject){widgets.clear();originals.clear();activity.clear();}}});
        XposedHelpers.findAndHookMethod("com.hmct.vision.BubbleTextView",loader,"onDraw",Canvas.class,new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam m){if(enabled)style((TextView)m.thisObject);}});
        popupBudget();
        XposedHelpers.findAndHookMethod("com.hmct.vision.plugin.PluginView",loader,"addRemoteView",View.class,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){View remote=findClock((View)m.args[0]);if(remote!=null)addWidget((ViewGroup)m.thisObject,remote);}});
        XposedHelpers.findAndHookMethod("com.hmct.vision.DragLayer",loader,"dispatchDraw",Canvas.class,new XC_MethodHook(){@Override protected void beforeHookedMethod(MethodHookParam m){if(!enabled||!RuntimeConfig.enabled("desktop.wallpaper"))return;View v=(View)m.thisObject;Bitmap b=Assets.bitmap("wallpaper-"+theme+".png");if(b!=null)((Canvas)m.args[0]).drawBitmap(b,null,new Rect(0,0,v.getWidth(),v.getHeight()),new Paint(3));}});
        XposedHelpers.findAndHookMethod("com.hmct.vision.FolderIcon",loader,"dispatchDraw",Canvas.class,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){if(!enabled||!RuntimeConfig.enabled("desktop.icons"))return;try{
            ViewGroup f=(ViewGroup)m.thisObject;int id=f.getResources().getIdentifier("preview_background","id","com.hmct.vision");View bg=f.findViewById(id);
            if(bg==null)return;Bitmap b=Assets.icon("Tools",theme);if(b==null)return;Canvas c=(Canvas)m.args[0];int save=c.save();int[] a=new int[2],z=new int[2];bg.getLocationInWindow(a);f.getLocationInWindow(z);c.translate(a[0]-z[0],a[1]-z[1]);Paint paint=new Paint(3);
            // 先遮住原文件夹组合缩略图，再绘主题图形；标签和点击仍由原厂处理。
            paint.setColor("paper".equals(theme)?Color.rgb(252,252,252):Color.WHITE);c.drawRect(0,0,bg.getWidth(),bg.getHeight(),paint);c.drawBitmap(b,null,new Rect(0,0,bg.getWidth(),bg.getHeight()),new Paint(3));c.restoreToCount(save);
        }catch(Throwable ignored){}}});
        XposedHelpers.findAndHookMethod("com.hmct.vision.LauncherPreferenceActivity",loader,"onCreate",Bundle.class,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam m){
            PreferenceActivity a=(PreferenceActivity)m.thisObject;if(a.getPreferenceScreen()==null)return;
            // 未启用桌面主题时不注入入口，原厂桌面设置保持原样，避免被误认为已修改。
            if(!RuntimeConfig.enabled("desktop.theme"))return;
            Preference pref=new Preference(a);pref.setKey("einkthemes_theme");pref.setTitle("桌面主题与小组件");pref.setSummary(themeSummary());pref.setOrder(-100);pref.setPersistent(false);
            pref.setOnPreferenceClickListener(x->{a.startActivity(new Intent().setClassName("com.inkcontrol","com.inkcontrol.ui.MainActivity"));return true;});a.getPreferenceScreen().addPreference(pref);
        }});
        XposedBridge.log("EinkThemes: launcher hooks installed pid="+android.os.Process.myPid());
    }
    /**
     * 长按菜单的条目预算。
     *
     * <p>原厂 {@code popup.PopupPopulator.a(List ids, List system)} 里写的是
     * {@code size = Math.min(4, 快捷方式数 + 系统项数)}：先把整行铺成「快捷方式」类型，
     * 再把末尾几个槽位改成系统项（分享 / 冻结 / 卸载）。这台机的系统项正好占 3 个，
     * 于是无论应用注册几条动态快捷方式，都只剩 1 个槽位 —— 这就是「海信桌面只显示 1 条」的真正原因，
     * 与快捷方式的内容、标签长度都无关。
     *
     * <p>两行本来就是各自独立的容器（{@code ShortcutsItemView} 和 {@code SystemShortcutsItemView}），
     * 所以按「快捷方式最多 4 个 + 系统项照原样」重算数组长度即可：快捷方式占前面几格，
     * 系统项仍落在末尾，各行数量互不影响。只对墨控自己的快捷方式（id 以 {@code feature:} 开头）生效。
     */
    private static void popupBudget(){
        try{
            XposedHelpers.findAndHookMethod("com.hmct.vision.popup.b",loader,"a",List.class,List.class,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam m){
                    try{
                        Object result=m.getResult();
                        if(!(result instanceof Object[]))return;
                        List<?> ids=(List<?>)m.args[0];
                        if(ids==null)return;
                        boolean ours=false;
                        for(Object id:ids)if(String.valueOf(id).startsWith("feature:")){ours=true;break;}
                        if(!ours)return;
                        List<?> system=(List<?>)m.args[1];
                        int keep=system==null?0:system.size();
                        int deep=Math.min(4,ids.size());
                        int total=deep+keep;
                        Object[] original=(Object[])result;
                        if(total<=original.length)return;
                        Class<?> type=original.getClass().getComponentType();
                        Object[] expanded=(Object[])java.lang.reflect.Array.newInstance(type,total);
                        Object shortcut=type.getField("a").get(null);
                        Object single=type.getField("b").get(null);
                        Object multi=type.getField("c").get(null);
                        for(int i=0;i<deep;i++)expanded[i]=shortcut;
                        for(int i=0;i<keep;i++)expanded[total-1-i]=keep==1?single:multi;
                        m.setResult(expanded);
                    }catch(Throwable failed){XposedBridge.log("EinkThemes: popup budget skipped "+failed.getClass().getSimpleName());}
                }
            });
        }catch(Throwable missing){XposedBridge.log("EinkThemes: popup budget hook unavailable "+missing.getClass().getSimpleName());}
    }
    private static void attach(Context c){if(initialized)return;initialized=true;context=c;handler=new Handler(c.getMainLooper());RuntimeConfig.listen(Hook::refresh);
        handler.post(()->{try{Assets.init(c);c.getContentResolver().registerContentObserver(ThemeProvider.URI,true,new ContentObserver(handler){@Override public void onChange(boolean self,android.net.Uri uri){
            if(uri!=null&&uri.getPath()!=null&&uri.getPath().endsWith("weather"))return;refresh();}});refresh();RuntimeConfig.markReady();}catch(Throwable t){RuntimeConfig.failInitialization("桌面初始化失败："+t.getClass().getSimpleName());XposedBridge.log("EinkThemes: initialization failed "+t);}});
    }
    /** 启用桌面主题时，设置页摘要显示当前主题名；入口本身只在启用时才注入。 */
    static String themeSummary(){
        try{
            com.inkcontrol.core.FeatureDefinition feature=com.inkcontrol.core.FeatureCatalog.get("desktop.theme");
            String value=RuntimeConfig.value("desktop.theme");int index=feature.values.indexOf(value);
            String label=index>=0&&index<feature.labels.size()?feature.labels.get(index):value;
            return "使用中：" + label;
        }catch(Throwable t){return "";}
    }
    static Object field(Object object,String name){if(object==null)return null;for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception ignored){}return null;}
    private static void setField(Object object,String name,Object value)throws Exception{for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(object,value);return;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    static boolean isClock(View v){return v!=null&&"com.hmct.weather.widget.ClockVerticalItem".equals(v.getClass().getName());}
    static View findClock(View v){if(isClock(v))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View result=findClock(g.getChildAt(i));if(result!=null)return result;}}return null;}
    private static void addWidget(ViewGroup host,View remote){if(widgets.containsKey(host))return;
        // 原厂天气异步回填时立即重绘；不依赖打开配置页或等下一个整分钟。
        if(observedClockClasses.add(remote.getClass()))for(Method method:remote.getClass().getDeclaredMethods()){
            boolean weather="a".equals(method.getName())&&method.getParameterTypes().length==2;
            boolean date="b".equals(method.getName())&&method.getParameterTypes().length==0;
            if(weather||date)XposedBridge.hookMethod(method,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam p){handler.post(Hook::refresh);}});
        }
        ThemeWidgetView widget=new ThemeWidgetView(host.getContext(),remote);widgets.put(host,widget);host.addView(widget,new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));widget.setVisibility(RuntimeConfig.enabled("desktop.widget")?View.VISIBLE:View.GONE);
        XposedBridge.log("EinkThemes: clock plugin themed pid="+android.os.Process.myPid());handler.post(Hook::diagnostic);
    }
    static void refresh(){if(context==null)return;try{
        Bundle b;
        try { b=context.getContentResolver().call(ThemeProvider.URI,"get",null,null); }
        catch(IllegalArgumentException notReady) { b=new Bundle(); }
        if(b==null)b=new Bundle();
        for(TextView view:new ArrayList<>(originals.keySet()))restore(view);
        String oldTheme=theme,oldKind=kind;boolean oldEnabled=enabled;state=b;theme=RuntimeConfig.value("desktop.theme");kind=RuntimeConfig.value("desktop.widget");enabled=RuntimeConfig.enabled("desktop.theme");
        Activity a=activity.get();if(a!=null){walk(a.getWindow().getDecorView());a.getWindow().getDecorView().invalidate();}
        for(ThemeWidgetView v:new ArrayList<>(widgets.values())){if(v!=null){v.setVisibility(RuntimeConfig.enabled("desktop.widget")?View.VISIBLE:View.GONE);v.refresh();}}
        if(oldEnabled!=enabled||!oldTheme.equals(theme)||!oldKind.equals(kind))XposedBridge.log("EinkThemes: applied theme="+theme+" kind="+kind+" enabled="+enabled+" pid="+android.os.Process.myPid());
        diagnostic();
    }catch(Throwable t){RuntimeConfig.fail("桌面应用失败："+t.getClass().getSimpleName());XposedBridge.log("EinkThemes: refresh failed "+t);XposedBridge.log(t);}}
    private static void walk(View v){
        if(v instanceof TextView&&v.getClass().getName().equals("com.hmct.vision.BubbleTextView")){if(enabled)style((TextView)v);else restore((TextView)v);}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;
            if(g.getClass().getName().equals("com.hmct.vision.plugin.PluginView")){Object raw=field(g,"mRemoteView");View clock=raw instanceof View?findClock((View)raw):null;if(clock!=null)addWidget(g,clock);}
            List<View> children=new ArrayList<>();for(int i=0;i<g.getChildCount();i++)children.add(g.getChildAt(i));for(View child:children)walk(child);}
        v.invalidate();
    }
    private static void style(TextView v){if(context==null)return;try{
        String title=v.getText()==null?"":v.getText().toString();if(title.isEmpty()){Object label=field(v.getTag(),"t");if(label!=null)title=label.toString();else if(v.getContentDescription()!=null)title=v.getContentDescription().toString();}Drawable current=(Drawable)field(v,"H");Original o=originals.get(v);
        int day=java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        if(o!=null&&theme.equals(o.theme)&&title.equals(o.title)&&current==o.styled&&(!"日历".equals(title)||o.day==day))return;
        if(o==null){o=new Original();o.icon=current;o.script=field(v,"K");originals.put(v,o);}
        else if(current!=o.styled){o.icon=current;Object script=field(v,"K");if(script!=null)o.script=script;}
        o.title=title;o.theme=theme;o.day=day;
        Bitmap b=RuntimeConfig.enabled("desktop.icons")?Assets.icon(title,theme):null;
        if(b!=null&&current!=null){int size=Math.max(80,Math.min(160,current.getIntrinsicWidth()));if(size<=0)size=112;Bitmap scaled=Bitmap.createScaledBitmap(b,size,size,true);
            Drawable drawable=(Drawable)loader.loadClass("com.hmct.vision.w").getConstructor(Bitmap.class).newInstance(scaled);setField(v,"K",null);v.getClass().getMethod("setIcon",Drawable.class).invoke(v,drawable);o.styled=drawable;
        }else o.styled=current;
    }catch(Throwable t){XposedBridge.log("EinkThemes: icon styling unavailable "+t.getClass().getSimpleName());}}
    private static void restore(TextView v){Original o=originals.remove(v);if(o==null)return;try{
        setField(v,"K",o.script);if(o.icon!=null)v.getClass().getMethod("setIcon",Drawable.class).invoke(v,o.icon);
    }catch(Throwable t){XposedBridge.log("EinkThemes: native icon restore unavailable "+t.getClass().getSimpleName());}}
    static void diagnostic(){try{if(context==null)return;JSONObject j=new JSONObject();j.put("pid",android.os.Process.myPid());j.put("theme",theme);j.put("kind",kind);j.put("enabled",enabled);j.put("styled_views",originals.size());j.put("clock_plugins",widgets.size());j.put("weather_available",!state.getString("temperature","").isEmpty());j.put("media_available",state.getBoolean("mediaAvailable"));
        File f=new File(context.getFilesDir(),"einkthemes-state.json");try(FileOutputStream out=new FileOutputStream(f)){out.write(j.toString(2).getBytes("UTF-8"));}
    }catch(Throwable t){XposedBridge.log("EinkThemes: diagnostic unavailable "+t);}}
}
