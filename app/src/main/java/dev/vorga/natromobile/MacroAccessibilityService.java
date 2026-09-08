package dev.vorga.natromobile;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class MacroAccessibilityService extends AccessibilityService {
    private static volatile MacroAccessibilityService instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean stop=new AtomicBoolean();
    private volatile String state="Готов к запуску";
    private volatile int cycle;
    private MacroConfig config;
    private TouchEngine touch;
    private ScreenAnchors anchors;
    private int width,height;
    private long lastScreenshot;
    static MacroAccessibilityService get(){return instance;}
    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){ }
    @Override public void onInterrupt(){stopMacro();}
    @Override public void onDestroy(){stopMacro();instance=null;worker.shutdown();super.onDestroy();}
    boolean isRunningMacro(){return busy.get();}
    String status(){return state;}
    void stopMacro(){stop.set(true);}
    void startMacroAfterDelay(long delay){
        if(!busy.compareAndSet(false,true))return;
        stop.set(false);
        worker.execute(()->{
            try {
                config=new MacroConfig(this);anchors=new ScreenAnchors(this);
                // The focusable settings panel has just closed. Wait for Android to
                // return focus, without scheduling a future start that could outlive STOP.
                long focusDeadline=SystemClock.uptimeMillis()+2000;
                while(!isRobloxForeground()&&SystemClock.uptimeMillis()<focusDeadline){
                    if(stop.get())throw new InterruptedException();Thread.sleep(40);
                }
                if(stop.get())throw new InterruptedException();
                dimensions();
                String error=config.validate(width,height);
                if(error!=null)throw new IllegalStateException(error);
                if(!isRobloxForeground())throw new IllegalStateException("Открой Bee Swarm Simulator в Roblox");
                touch=new TouchEngine(this,()->!stop.get()&&isRobloxForeground()&&sameDimensions(),config,width,height);
                setState("Старт через "+Math.max(1,delay/1000)+" с");touch.waitFor(delay);
                boolean fromField=config.text("start_mode","field").equals("field");
                boolean repeat=config.flag("repeat_cycle",false);
                if(!fromField||repeat)validateRoute();
                int limit=config.integer("cycles",1);
                for(cycle=1;!stop.get()&&(limit==0||cycle<=limit);cycle++){
                    if(!fromField||cycle>1){resetAndAlign();convert();routeToPine();}
                    gather();
                    if(!repeat)break;
                    // A finite session also converts its final backpack.
                    if(limit>0 && cycle==limit){resetAndAlign();convert();break;}
                }
                setState("Завершено • циклов: "+cycle);
            }catch(InterruptedException e){setState(stop.get()?"Остановлено":"Остановлено: Roblox потерял фокус или экран изменился");}
            catch(Exception e){setState("Ошибка: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
            finally{
                if(touch!=null)touch.release(isRobloxForeground());
                touch=null;busy.set(false);
                ControlOverlayService.setState(state);
            }
        });
    }
    private void validateRoute(){
        for(String point:new String[]{"menu","reset","confirm","interact"})
            if(!config.point(point))throw new IllegalStateException("Для маршрута нужна калибровка кнопки: "+point);
        for(String marker:new String[]{"hive","cannon","pine","empty"})
            if(!anchors.exists(marker))throw new IllegalStateException("Обучи ориентир «"+marker+"» в меню калибровки");
        if(!config.flag("camera_calibrated",false))throw new IllegalStateException("Откалибруй поворот камеры на 45°");
    }
    private void resetAndAlign()throws Exception{
        for(int attempt=1;attempt<=3;attempt++){
            setState("Возврат к улью • попытка "+attempt+"/3");
            touch.tap("menu",80);touch.waitFor(650);
            touch.tap("reset",80);touch.waitFor(650);
            touch.tap("confirm",80);touch.waitFor(config.integer("respawn_ms",5500));
            setState("Проверка улья и направления камеры");
            for(int yaw=0;yaw<8;yaw++){
                if(seen("hive",2))return;
                rotate(1);touch.waitFor(200);
            }
        }
        throw new IllegalStateException("Улей не подтверждён после 3 сбросов. Проверь ориентацию камеры и ориентир hive");
    }
    private void convert()throws Exception{
        setState("Улей • переработка пыльцы");
        if(seen("empty",2))return;
        touch.tap("interact",100);
        long end=SystemClock.uptimeMillis()+config.integer("convert_seconds",120)*1000L;
        int matches=0;
        while(SystemClock.uptimeMillis()<end){
            touch.waitFor(600);
            matches=seen("empty",1)?matches+1:0;
            if(matches>=3){setState("Рюкзак пуст");return;}
        }
        throw new IllegalStateException("Переработка не завершилась. Проверь кнопку взаимодействия и ориентир empty");
    }
    private void routeToPine()throws Exception{
        // Original nm_gotoRamp(): 5 forward, 9.2*HiveSlot-4 right.
        setState("Улей → рампа");walk(0,-1,5,false);walk(1,0,9.2*config.integer("hive_slot",1)-4,false);
        setState("Рампа → красная пушка");
        boolean found=false;
        // Bounded stepping replaces the original desktop pixel loop while moving right.
        for(int attempt=0;attempt<3&&!found;attempt++){
            touch.move(1,0,100,false,new long[]{0});
            walk(1,0,2,false);walk(1,-1,1.5,false);
            for(int probe=0;probe<18;probe++){
                if(seen("cannon",2)){found=true;break;}
                walk(1,0,.65,false);
            }
            if(!found)throw new IllegalStateException("Подсказка пушки не найдена. Остановлено до полёта");
        }
        setState("Пушка → Pine Tree");
        touch.tap("interact",100);
        // gtf-pinetree.ahk: right+back 925 ms, double jump, 4500 ms glide,
        // right 500 ms, jump, 4 left rotations, 2000 ms settle.
        // Mobile jump presses are explicit 65 ms with 80 ms between them.
        touch.move(1,1,5635,false,new long[]{925,1070});
        touch.move(1,0,500,false);touch.tap("jump",65);
        rotate(-4);touch.waitFor(2000);
        if(!seen("pine",2))throw new IllegalStateException("Pine Tree не подтверждён. Проверь угол камеры, полёт и ориентир pine");
        if(config.flag("sprinkler",false)){
            if(!config.point("sprinkler"))throw new IllegalStateException("Откалибруй кнопку спринклера");
            touch.tap("sprinkler",80);touch.waitFor(500);
        }
    }
    private void gather()throws Exception{
        setState("Pine Tree • сбор • цикл "+cycle);
        List<PatternStep> steps=PatternFactory.create(config.text("pattern","Snake"),config.number("pattern_size",1),
                config.integer("pattern_reps",3),config.flag("invert_lr",false),config.flag("invert_fb",false),config.flag("facing_corner",false));
        long end=SystemClock.uptimeMillis()+config.integer("run_seconds",900)*1000L;
        long nextScan=0;int fullCount=0;
        boolean full=config.flag("detect_full",false);
        if(full&&!anchors.exists("full"))throw new IllegalStateException("Обучи ориентир полного рюкзака full");
        int i=0;
        while(SystemClock.uptimeMillis()<end){
            long remaining=end-SystemClock.uptimeMillis();
            if(steps.isEmpty())touch.move(0,0,Math.min(500,remaining),true);
            else{
                PatternStep step=steps.get(i++%steps.size());
                touch.move(step.x,step.y,Math.min(duration(step.tiles),remaining),true);
            }
            if(full&&SystemClock.uptimeMillis()>=nextScan){
                fullCount=seen("full",1)?fullCount+1:0;
                nextScan=SystemClock.uptimeMillis()+700;
                if(fullCount>=3){setState("Рюкзак полный");return;}
            }
        }
    }
    private long duration(double tiles){return MovementMath.duration(tiles,config.speed(),config.number("timing_scale",1));}
    private void walk(float x,float y,double tiles,boolean gather)throws InterruptedException{touch.move(x,y,duration(tiles),gather);}
    private void rotate(int steps)throws InterruptedException{
        float distance=config.number("camera_swipe",.14f)*width;
        for(int i=0;i<Math.abs(steps);i++){
            float from=width*.7f,to=from+(steps>0?-distance:distance);
            touch.swipe(from,height*.48f,to,height*.48f,145);touch.waitFor(75);
        }
    }
    void testCamera(){
        if(!busy.compareAndSet(false,true))return;stop.set(false);
        worker.execute(()->{
            try{
                dimensions();config=new MacroConfig(this);
                touch=new TouchEngine(this,()->!stop.get()&&isRobloxForeground(),config,width,height);
                touch.waitFor(800);rotate(1);setState("Проверь поворот на 45°. При необходимости измени длину свайпа");
            }catch(Exception e){setState("Тест камеры: "+e.getMessage());}
            finally{touch=null;busy.set(false);}
        });
    }
    private boolean seen(String name,int frames)throws Exception{
        for(int i=0;i<frames;i++){
            long since=SystemClock.uptimeMillis()-lastScreenshot;
            if(since<350)touch.waitFor(350-since);
            Bitmap b=screenshotBlocking();lastScreenshot=SystemClock.uptimeMillis();
            if(b==null)throw new IllegalStateException("Не удалось получить снимок Roblox");
            boolean matches;
            try{matches=anchors.matches(name,b);}finally{b.recycle();}
            if(!matches)return false;
        }
        return true;
    }
    private void dimensions(){DisplayMetrics d=new DisplayMetrics();getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(d);width=d.widthPixels;height=d.heightPixels;}
    private boolean sameDimensions(){DisplayMetrics d=new DisplayMetrics();getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(d);return width==d.widthPixels&&height==d.heightPixels;}
    boolean isRobloxForeground(){
        if(!getSystemService(PowerManager.class).isInteractive()||getSystemService(KeyguardManager.class).isKeyguardLocked())return false;
        try{
            List<AccessibilityWindowInfo> windows=getWindows();
            for(AccessibilityWindowInfo win:windows){
                if(!win.isActive()&&!win.isFocused())continue;
                AccessibilityNodeInfo root=win.getRoot();
                if(root==null)continue;
                String pkg=root.getPackageName()==null?"":root.getPackageName().toString();root.recycle();
                if(pkg.equals("com.roblox.client"))return true;
                if(win.getType()==AccessibilityWindowInfo.TYPE_APPLICATION || win.isFocused())return false;
            }
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root==null)return false;
            boolean ok="com.roblox.client".contentEquals(root.getPackageName()==null?"":root.getPackageName());root.recycle();return ok;
        }catch(RuntimeException e){return false;}
    }
    void capture(Consumer<Bitmap> callback){
        main.post(()->{
            if(!isRobloxForeground()){callback.accept(null);return;}
            try{takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
                @Override public void onSuccess(ScreenshotResult result){
                    Bitmap copy=null,wrapped=null;
                    try(HardwareBuffer buffer=result.getHardwareBuffer()){
                        wrapped=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
                        if(wrapped!=null)copy=wrapped.copy(Bitmap.Config.ARGB_8888,false);
                    }catch(RuntimeException ignored){}
                    finally{if(wrapped!=null)wrapped.recycle();}
                    callback.accept(copy);
                }
                @Override public void onFailure(int errorCode){callback.accept(null);}
            });}catch(RuntimeException e){callback.accept(null);}
        });
    }
    private Bitmap screenshotBlocking()throws InterruptedException{
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<Bitmap> out=new AtomicReference<>();AtomicBoolean expired=new AtomicBoolean();
        capture(b->{synchronized(out){if(expired.get()){if(b!=null)b.recycle();}else out.set(b);}latch.countDown();});
        try{latch.await(2500,TimeUnit.MILLISECONDS);}finally{synchronized(out){expired.set(true);}}
        return out.get();
    }
    private void setState(String value){state=value;ControlOverlayService.setState(value);}
}
