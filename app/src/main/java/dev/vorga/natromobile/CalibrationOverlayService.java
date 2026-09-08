package dev.vorga.natromobile;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

public class CalibrationOverlayService extends Service {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View overlay;
    private String kind;
    private int step,width,height;
    private final float[] xs=new float[4],ys=new float[4];
    private boolean capturing;
    @Override public void onCreate(){super.onCreate();wm=getSystemService(WindowManager.class);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(overlay!=null||capturing)return START_NOT_STICKY;
        kind=intent==null?"controls":intent.getStringExtra("kind");if(kind==null)kind="controls";
        MacroAccessibilityService svc=MacroAccessibilityService.get();
        if(svc==null||svc.isRunningMacro()){toast("Сначала останови макрос и включи специальные возможности");stopSelf();return START_NOT_STICKY;}
        ControlOverlayService.hideForCalibration();
        DisplayMetrics dm=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);width=dm.widthPixels;height=dm.heightPixels;
        if(width<=height){toast("Калибровка нужна в альбомном Roblox");stopSelf();return START_NOT_STICKY;}
        overlay=new View(this){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas){
                // Keep selected pixels unobscured; only the instruction card is shaded.
                paint.setColor(0xE6141811);canvas.drawRoundRect(new RectF(width*.15f,12,width*.85f,118),22,22,paint);
                paint.setColor(Color.WHITE);paint.setTextSize(24);paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(instruction(),width/2f,52,paint);
                paint.setTextSize(18);canvas.drawText("Касание выбирает точку • × справа — отмена",width/2f,88,paint);
                paint.setTextSize(36);canvas.drawText("×",width-40,52,paint);
                if(step>0){paint.setColor(0xFFB4D5A1);canvas.drawCircle(xs[0],ys[0],7,paint);}
            }
            @Override public boolean onTouchEvent(MotionEvent e){
                if(e.getAction()!=MotionEvent.ACTION_UP)return true;
                if(e.getRawX()>width-80&&e.getRawY()<90){stopSelf();return true;}
                choose(e.getRawX(),e.getRawY());invalidate();return true;
            }
        };
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(overlay,p);return START_NOT_STICKY;
    }
    private String instruction(){
        if(kind.equals("controls"))return new String[]{"1/4 • Центр фиксированного джойстика","2/4 • Правый край круга джойстика","3/4 • Кнопка прыжка","4/4 • Кнопка сбора инструментом"}[Math.min(step,3)];
        if(kind.startsWith("anchor:"))return (step==0?"1/2 • Первый угол ориентира ":"2/2 • Противоположный угол ")+kind.substring(7);
        return "Нажми центр кнопки «"+kind+"»";
    }
    private void choose(float x,float y){
        if(capturing)return;xs[step]=x;ys[step]=y;
        SharedPreferences.Editor e=new MacroConfig(this).p.edit();
        if(kind.equals("controls")){
            if(step==1){float r=(float)Math.hypot(x-xs[0],y-ys[0]);
                if(r<12||xs[0]-r<0||ys[0]-r<0||xs[0]+r>=width||ys[0]+r>=height){toast("Неверный радиус: весь круг должен помещаться на экране");return;}}
            if(++step<4)return;
            putPoint(e,"joy",xs[0],ys[0]);putPoint(e,"jump",xs[2],ys[2]);putPoint(e,"tool",xs[3],ys[3]);
            e.putFloat("radius_n",(float)Math.hypot(xs[1]-xs[0],ys[1]-ys[0])/Math.min(width,height));
            e.putInt("cal_w",width).putInt("cal_h",height).apply();toast("Управление сохранено");stopSelf();
        }else if(kind.startsWith("anchor:")){
            if(++step<2)return;capturing=true;remove();
            handler.postDelayed(()->{
                MacroAccessibilityService s=MacroAccessibilityService.get();
                if(s==null){toast("Служба недоступна");stopSelf();return;}
                s.capture(bitmap->{
                    if(bitmap==null){toast("Снимок недоступен. Roblox должен быть открыт");stopSelf();return;}
                    try{
                        new ScreenAnchors(this).save(kind.substring(7),bitmap,(int)Math.min(xs[0],xs[1]),(int)Math.min(ys[0],ys[1]),(int)Math.max(xs[0],xs[1]),(int)Math.max(ys[0],ys[1]));toast("Ориентир сохранён");
                    }catch(Exception ex){toast(ex.getMessage());}finally{bitmap.recycle();stopSelf();}
                });
            },550);
        }else{putPoint(e,kind,x,y);e.apply();toast("Кнопка сохранена");stopSelf();}
    }
    private void putPoint(SharedPreferences.Editor e,String key,float x,float y){e.putFloat(key+"_nx",x/width).putFloat(key+"_ny",y/height);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void remove(){if(overlay!=null){wm.removeView(overlay);overlay=null;}}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);toast("Экран повернулся. Повтори калибровку");stopSelf();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);remove();ControlOverlayService.restore();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
