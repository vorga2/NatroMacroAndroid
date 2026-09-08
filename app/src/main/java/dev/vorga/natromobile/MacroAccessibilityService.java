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

/**
 * Top-level state machine only. Input, distance movement, vision and scripted
 * paths are delegated to dedicated engines so routes stay Natro-like and testable.
 */
public class MacroAccessibilityService extends AccessibilityService {
    private static final String ROBLOX_PACKAGE="com.roblox.client";
    private static volatile MacroAccessibilityService instance;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean stop=new AtomicBoolean();

    private volatile String state="Готов к запуску";
    private volatile int cycle;
    private int width,height;

    private MacroConfig config;
    private TouchEngine touch;
    private MovementEngine movement;
    private VisionEngine vision;
    private PathEngine paths;
    private RecoveryStateMachine recovery;

    static MacroAccessibilityService get(){return instance;}

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){ }

    @Override public void onInterrupt(){stopMacro();}

    @Override public void onDestroy(){
        stopMacro();
        if(instance==this)instance=null;
        worker.shutdownNow();
        super.onDestroy();
    }

    boolean isRunningMacro(){return busy.get();}
    String status(){return state;}
    void stopMacro(){stop.set(true);}

    void startMacroAfterDelay(long delay){
        if(!busy.compareAndSet(false,true))return;
        stop.set(false);

        worker.execute(()->{
            int completed=0;
            try {
                setState("WAIT ROBLOX");
                if(!waitForRobloxFocus(3000))
                    throw new IllegalStateException("Открой Bee Swarm Simulator в Roblox");

                dimensions();
                config=new MacroConfig(this);
                String error=config.validate(width,height);
                if(error!=null)throw new IllegalStateException(error);

                touch=new TouchEngine(
                        this,
                        ()->!stop.get()&&isRobloxForeground()&&sameDimensions(),
                        config,width,height
                );
                movement=new MovementEngine(touch,config,width,height,config::speed);
                vision=new VisionEngine(new ScreenAnchors(this),this::screenshotBlocking,movement);
                paths=new PathEngine(movement,vision,this::setState);
                recovery=new RecoveryStateMachine(config,movement,vision,paths,this::setState);

                setState("Старт через "+Math.max(1,delay/1000)+" с");
                movement.waitFor(delay);

                boolean fromField=config.text("start_mode","field").equals("field");
                boolean repeat=config.flag("repeat_cycle",false);
                if(!fromField||repeat)recovery.validateAutoRoute();

                int limit=config.integer("cycles",1);
                for(cycle=1;!stop.get()&&(limit==0||cycle<=limit);cycle++){
                    if(!fromField||cycle>1){
                        recovery.resetToHive();
                        recovery.convertAtHive();
                        recovery.gotoRamp();
                        recovery.gotoCannonWithRecovery();
                        recovery.flyCannonToPine();
                    }

                    gather();
                    completed++;
                    if(!repeat)break;

                    // A finite session converts the final backpack before stopping.
                    if(limit>0&&cycle==limit){
                        recovery.resetToHive();
                        recovery.convertAtHive();
                        break;
                    }
                }

                setState("Завершено • циклов: "+completed);
            } catch(InterruptedException e){
                setState(stop.get()?"Остановлено":"Остановлено: Roblox потерял фокус или экран изменился");
            } catch(Exception e){
                String msg=e.getMessage();
                setState("Ошибка: "+(msg==null?e.getClass().getSimpleName():msg));
            } finally {
                if(touch!=null)touch.release(isRobloxForeground());
                touch=null;
                movement=null;
                vision=null;
                paths=null;
                recovery=null;
                busy.set(false);
                ControlOverlayService.setState(state);
            }
        });
    }

    private void gather() throws Exception {
        setState("PINE TREE • GATHER • "+cycle);
        List<PatternStep> steps=PatternFactory.create(
                config.text("pattern","Snake"),
                config.number("pattern_size",1),
                config.integer("pattern_reps",3),
                config.flag("invert_lr",false),
                config.flag("invert_fb",false),
                config.flag("facing_corner",false)
        );

        long end=SystemClock.uptimeMillis()+config.integer("run_seconds",900)*1000L;
        boolean full=config.flag("detect_full",false);
        if(full&&!vision.exists("full"))
            throw new IllegalStateException("Обучи ориентир полного рюкзака full");

        int index=0;
        int fullCount=0;
        long nextFullScan=0;

        while(SystemClock.uptimeMillis()<end){
            long remaining=end-SystemClock.uptimeMillis();
            if(steps.isEmpty()){
                movement.moveRawGather(0,0,Math.min(500,remaining));
            }else{
                PatternStep step=steps.get(index++%steps.size());
                long estimated=MovementMath.duration(
                        step.tiles,config.speed(),config.number("timing_scale",1)
                );
                if(estimated>remaining){
                    movement.moveRawGather(step.x,step.y,remaining);
                }else{
                    movement.moveTiles(step.x,step.y,step.tiles,true);
                }
            }

            if(full&&SystemClock.uptimeMillis()>=nextFullScan){
                fullCount=vision.seen("full",1)?fullCount+1:0;
                nextFullScan=SystemClock.uptimeMillis()+700;
                if(fullCount>=3){
                    setState("Рюкзак полный");
                    return;
                }
            }
        }
    }

    void testCamera(){
        if(!busy.compareAndSet(false,true))return;
        stop.set(false);
        worker.execute(()->{
            TouchEngine localTouch=null;
            try{
                if(!waitForRobloxFocus(2500))throw new IllegalStateException("Открой Roblox");
                dimensions();
                MacroConfig localConfig=new MacroConfig(this);
                localTouch=new TouchEngine(this,()->!stop.get()&&isRobloxForeground(),localConfig,width,height);
                MovementEngine localMove=new MovementEngine(localTouch,localConfig,width,height,localConfig::speed);
                localMove.waitFor(500);
                localMove.rotate(1);
                setState("Проверь поворот на 45°. При необходимости измени длину свайпа");
            }catch(Exception e){
                setState("Тест камеры: "+e.getMessage());
            }finally{
                if(localTouch!=null)localTouch.release(isRobloxForeground());
                busy.set(false);
            }
        });
    }

    private boolean waitForRobloxFocus(long timeoutMs) throws InterruptedException {
        long end=SystemClock.uptimeMillis()+Math.max(0,timeoutMs);
        while(!stop.get()&&SystemClock.uptimeMillis()<=end){
            if(isRobloxForeground())return true;
            Thread.sleep(40);
        }
        if(stop.get())throw new InterruptedException();
        return false;
    }

    private void dimensions(){
        DisplayMetrics d=new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(d);
        width=d.widthPixels;
        height=d.heightPixels;
    }

    private boolean sameDimensions(){
        DisplayMetrics d=new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(d);
        return width==d.widthPixels&&height==d.heightPixels;
    }

    boolean isRobloxForeground(){
        PowerManager power=getSystemService(PowerManager.class);
        KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        if(power!=null&&!power.isInteractive())return false;
        if(keyguard!=null&&keyguard.isKeyguardLocked())return false;

        try{
            List<AccessibilityWindowInfo> windows=getWindows();
            if(windows!=null){
                // Search every active/focused window before rejecting the foreground.
                // This avoids false negatives from our non-focusable overlay/SystemUI.
                for(AccessibilityWindowInfo win:windows){
                    if(win==null||(!win.isActive()&&!win.isFocused()))continue;
                    AccessibilityNodeInfo root=null;
                    try{
                        root=win.getRoot();
                        if(root==null||root.getPackageName()==null)continue;
                        if(ROBLOX_PACKAGE.equals(root.getPackageName().toString()))return true;
                    }finally{
                        if(root!=null)try{root.recycle();}catch(RuntimeException ignored){}
                    }
                }
            }

            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root==null)return false;
            try{
                return ROBLOX_PACKAGE.contentEquals(root.getPackageName()==null?"":root.getPackageName());
            }finally{
                try{root.recycle();}catch(RuntimeException ignored){}
            }
        }catch(RuntimeException e){
            return false;
        }
    }

    void capture(Consumer<Bitmap> callback){
        main.post(()->{
            if(!isRobloxForeground()){
                callback.accept(null);
                return;
            }
            try{
                takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
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
                });
            }catch(RuntimeException e){
                callback.accept(null);
            }
        });
    }

    private Bitmap screenshotBlocking() throws InterruptedException {
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<Bitmap> out=new AtomicReference<>();
        AtomicBoolean expired=new AtomicBoolean();

        capture(bitmap->{
            synchronized(out){
                if(expired.get()){
                    if(bitmap!=null)bitmap.recycle();
                }else{
                    out.set(bitmap);
                }
            }
            latch.countDown();
        });

        try{
            latch.await(2500,TimeUnit.MILLISECONDS);
        }finally{
            synchronized(out){expired.set(true);}
        }
        return out.get();
    }

    private void setState(String value){
        state=value;
        ControlOverlayService.setState(value);
    }
}
