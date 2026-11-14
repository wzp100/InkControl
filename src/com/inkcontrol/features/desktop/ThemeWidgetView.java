package com.inkcontrol.features.desktop;

import com.inkcontrol.platform.RuntimeConfig;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.view.ViewGroup;
import java.util.Map;
import java.util.HashMap;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONObject;

/** 只覆盖原有 ClockVerticalItem 的像素；原插件继续更新数据、占位和拖动。 */
final class ThemeWidgetView extends View {
    private final View remote;
    private final int originalAccessibility;
    private final float originalAlpha;
    private float downX,downY;
    private long downTime;
    private boolean registered;
    private final BroadcastReceiver tick=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh();Hook.refresh();}};
    private final Runnable progress=new Runnable(){@Override public void run(){if("music".equals(Hook.kind)&&Hook.state.getBoolean("playing")&&getGlobalVisibleRect(new Rect()))invalidate();postDelayed(this,15000);}};
    ThemeWidgetView(Context c,View original){super(c);remote=original;originalAlpha=original.getAlpha();originalAccessibility=original.getImportantForAccessibility();setClickable(true);setFocusable(true);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();IntentFilter f=new IntentFilter(Intent.ACTION_TIME_TICK);f.addAction(Intent.ACTION_TIME_CHANGED);f.addAction(Intent.ACTION_TIMEZONE_CHANGED);getContext().registerReceiver(tick,f);registered=true;post(progress);refresh();}
    @Override protected void onDetachedFromWindow(){removeCallbacks(progress);if(registered){getContext().unregisterReceiver(tick);registered=false;}remote.setImportantForAccessibility(originalAccessibility);remote.setAlpha(originalAlpha);super.onDetachedFromWindow();}
    void refresh(){
        remote.setImportantForAccessibility(RuntimeConfig.enabled("desktop.widget")?IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:originalAccessibility);
        remote.setAlpha(RuntimeConfig.enabled("desktop.widget")?0:originalAlpha);
        Bundle weather=new Bundle();Object city=Hook.field(remote,"r"),raw=Hook.field(city,"c");
        if(raw instanceof Map){try{
            weather.putAll(com.inkcontrol.shared.WeatherData.from(new HashMap<>((Map<?,?>)raw)));
        }catch(Exception ignored){}}
        String lunar=lunar(remote);if(lunar!=null)weather.putString("lunar",lunar);
        Hook.state.putAll(weather);
        try{getContext().getContentResolver().call(ThemeProvider.URI,"publish-weather",null,weather);}catch(Exception ignored){}
        setContentDescription("纸间主题"+("clock".equals(Hook.kind)?"时钟":"weather".equals(Hook.kind)?"天气":"音乐")+"小组件");invalidate();
    }
    private static String lunar(View view){if(view instanceof TextView){try{String id=view.getResources().getResourceEntryName(view.getId());if("tv_dayin".equals(id)||"clock_day_in".equals(id))return ((TextView)view).getText().toString();}catch(Exception ignored){}}
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){String s=lunar(g.getChildAt(i));if(s!=null&&!s.isEmpty())return s;}}return null;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);WidgetRenderer.draw(c,getWidth(),getHeight(),getContext(),Hook.theme,Hook.kind,Hook.state);}
    @Override public boolean onTouchEvent(MotionEvent event){switch(event.getActionMasked()){
        case MotionEvent.ACTION_DOWN:downX=event.getX();downY=event.getY();downTime=SystemClock.uptimeMillis();return true;
        case MotionEvent.ACTION_MOVE:return true;
        case MotionEvent.ACTION_UP:if(SystemClock.uptimeMillis()-downTime<450&&Math.hypot(event.getX()-downX,event.getY()-downY)<getResources().getDisplayMetrics().density*12){performClick();activate(event.getX()/Math.max(1,getWidth()),event.getY()/Math.max(1,getHeight()));}return true;
        case MotionEvent.ACTION_CANCEL:return true;
        default:return super.onTouchEvent(event);}}
    @Override public boolean performClick(){return super.performClick();}
    private void activate(float x,float y){try{
        boolean paper="paper".equals(Hook.theme);
        boolean controls=paper?(x>.62f&&y>.75f&&y<.94f):(x>.36f&&y>.5f&&y<.69f);
        if("music".equals(Hook.kind)&&Hook.state.getBoolean("mediaAvailable")&&controls){Bundle b=new Bundle();b.putString("command",x<(paper?.74f:.55f)?"previous":x<(paper?.86f:.75f)?"toggle":"next");getContext().getContentResolver().call(ThemeProvider.URI,"control",null,b);return;}
        Intent i;
        if("clock".equals(Hook.kind))i=new Intent("android.intent.action.SHOW_ALARMS");
        else if("weather".equals(Hook.kind)){
            i=new Intent().setClassName("com.hmct.clock.systemwidget","com.hmct.clock.systemwidget.LaunchActivity").putExtra("launch",2);
            Object city=Hook.field(remote,"r");if(city!=null)try{Object id=city.getClass().getMethod("g").invoke(city);if(id!=null)i.putExtra("extra_cityid",String.valueOf(id));}catch(Exception ignored){}
        }
        else{i=getContext().getPackageManager().getLaunchIntentForPackage(Hook.state.getString("mediaPackage","com.netease.cloudmusic"));if(i==null)i=getContext().getPackageManager().getLaunchIntentForPackage("com.netease.cloudmusic");}
        if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(i);}
    }catch(Exception e){android.widget.Toast.makeText(getContext(),com.inkcontrol.platform.HookText.t(getContext(),"暂时无法打开对应应用"),android.widget.Toast.LENGTH_SHORT).show();}}
}
