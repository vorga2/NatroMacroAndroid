package dev.vorga.natromobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;

public class ControlOverlayService extends Service {
    private static volatile ControlOverlayService instance;
    private static volatile Rect bubbleBounds=new Rect();
    private final Handler main=new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View root;
    private TextView status;
    private WindowManager.LayoutParams params;
    private String state="Готов к запуску";
    private boolean expanded;
    private int bubbleX=16,bubbleY=16;
    private ContextThemeWrapper theme;
    static void setState(String value){ControlOverlayService s=instance;if(s!=null)s.main.post(()->{s.state=value;if(s.status!=null)s.status.setText(value);});}
    static boolean obscures(float x,float y){return bubbleBounds.contains(Math.round(x),Math.round(y));}
    static boolean obscuresPath(float x,float y,float tx,float ty){
        return Rect.intersects(bubbleBounds,new Rect((int)Math.min(x,tx),(int)Math.min(y,ty),(int)Math.max(x,tx)+1,(int)Math.max(y,ty)+1));
    }
    static void hideForCalibration(){ControlOverlayService s=instance;if(s!=null)s.remove();}
    static void restore(){ControlOverlayService s=instance;if(s!=null)s.showBubble();}
    @Override public void onCreate(){
        super.onCreate();instance=this;wm=getSystemService(WindowManager.class);theme=new ContextThemeWrapper(this,R.style.AppTheme);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("macro_control","Natro overlay",NotificationManager.IMPORTANCE_LOW));
        Intent stopIntent=new Intent(this,ControlOverlayService.class).setAction("STOP");
        PendingIntent stop=PendingIntent.getService(this,1,stopIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(42,new Notification.Builder(this,"macro_control").setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("Natro • Pine Tree").setContentText("Меню поверх Roblox • STOP в любой момент")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null,"STOP",stop).build()).setOngoing(true).build());
        if(!Settings.canDrawOverlays(this)){stopSelf();return;}
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);
        bubbleX=dm.widthPixels-dp(166);bubbleY=dp(8);showBubble();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"STOP".equals(intent.getAction())){MacroAccessibilityService s=MacroAccessibilityService.get();if(s!=null)s.stopMacro();}
        return START_NOT_STICKY;
    }
    private void showBubble(){
        if(instance!=this)return;remove();expanded=false;
        LinearLayout row=new LinearLayout(theme);row.setPadding(dp(6),0,dp(6),0);
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xFF273321);bg.setCornerRadius(dp(24));row.setBackground(bg);
        MaterialButton menu=button("N • меню",this::openMenu);menu.setTextSize(12);menu.setMinWidth(0);
        row.addView(menu,new LinearLayout.LayoutParams(dp(94),dp(48)));
        MaterialButton stopButton=button("■",()->{MacroAccessibilityService s=MacroAccessibilityService.get();if(s!=null)s.stopMacro();});
        stopButton.setContentDescription("Остановить макрос");stopButton.setMinWidth(0);row.addView(stopButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        root=row;params=layout(dp(154),dp(48),false);params.x=bubbleX;params.y=bubbleY;clamp();wm.addView(root,params);
        menu.setOnTouchListener(new View.OnTouchListener(){float downX,downY;int startX,startY;boolean moved;
            public boolean onTouch(View v,MotionEvent e){
                if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();startX=params.x;startY=params.y;moved=false;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){if(Math.hypot(e.getRawX()-downX,e.getRawY()-downY)>dp(8))moved=true;if(moved){params.x=startX+Math.round(e.getRawX()-downX);params.y=startY+Math.round(e.getRawY()-downY);clamp();wm.updateViewLayout(root,params);bounds();}return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){if(!moved)v.performClick();else{bubbleX=params.x;bubbleY=params.y;}return true;}return true;
            }
        });root.post(this::bounds);
    }
    private void openMenu(){
        MacroAccessibilityService s=MacroAccessibilityService.get();if(s!=null)s.stopMacro();
        afterStopped(()->{
            remove();expanded=true;
            LinearLayout container=new LinearLayout(theme);container.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xFF141811);bg.setCornerRadius(dp(24));container.setBackground(bg);
            status=new TextView(theme);status.setText(s==null?"Включи службу специальных возможностей":s.status());status.setTextSize(12);status.setPadding(dp(16),dp(8),dp(16),0);container.addView(status);
            SettingsPanel panel=new SettingsPanel(theme,new SettingsPanel.Actions(){
                public void start(){showBubble();MacroAccessibilityService svc=MacroAccessibilityService.get();if(svc!=null)main.postDelayed(()->svc.startMacroAfterDelay(800),300);else Toast.makeText(ControlOverlayService.this,"Включи специальные возможности",Toast.LENGTH_LONG).show();}
                public void calibrate(String kind){remove();startService(new Intent(ControlOverlayService.this,CalibrationOverlayService.class).putExtra("kind",kind));}
                public void testCamera(){showBubble();MacroAccessibilityService svc=MacroAccessibilityService.get();if(svc!=null)svc.testCamera();}
                public void close(){showBubble();}
            });container.addView(panel,new LinearLayout.LayoutParams(-1,0,1));
            root=container;
            android.util.DisplayMetrics dm=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);
            params=layout(Math.min(dp(420),dm.widthPixels-dp(24)),dm.heightPixels-dp(24),true);params.x=dp(12);params.y=dp(12);wm.addView(root,params);
        });
    }
    private void afterStopped(Runnable action){
        if(instance!=this)return;
        MacroAccessibilityService s=MacroAccessibilityService.get();
        if(s!=null&&s.isRunningMacro()){main.postDelayed(()->afterStopped(action),50);return;}action.run();
    }
    private WindowManager.LayoutParams layout(int w,int h,boolean focusable){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        |(focusable?0:WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;return p;
    }
    private void bounds(){if(root==null||expanded){bubbleBounds=new Rect();return;}int[] xy=new int[2];root.getLocationOnScreen(xy);bubbleBounds=new Rect(xy[0],xy[1],xy[0]+root.getWidth(),xy[1]+root.getHeight());}
    private void clamp(){android.util.DisplayMetrics d=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(d);params.x=Math.max(0,Math.min(params.x,d.widthPixels-params.width));params.y=Math.max(dp(8),Math.min(params.y,d.heightPixels-params.height));}
    private void remove(){status=null;bubbleBounds=new Rect();if(root!=null){wm.removeView(root);root=null;}}
    private MaterialButton button(String text,Runnable action){MaterialButton b=new MaterialButton(theme);b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);MacroAccessibilityService s=MacroAccessibilityService.get();if(s!=null)s.stopMacro();afterStopped(this::showBubble);}
    @Override public void onDestroy(){if(instance==this)instance=null;main.removeCallbacksAndMessages(null);MacroAccessibilityService s=MacroAccessibilityService.get();if(s!=null)s.stopMacro();remove();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
