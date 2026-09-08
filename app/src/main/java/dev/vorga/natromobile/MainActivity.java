package dev.vorga.natromobile;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {
    private TextView status;
    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,64,32,32);scroll.addView(root);
        TextView title=new TextView(this);title.setText("Natro Mobile\nPine Tree");title.setTextSize(32);root.addView(title);
        TextView help=new TextView(this);help.setText("Настройка и управление прямо поверх Roblox.\n\n1. Включи службу и разреши окно поверх приложений.\n2. Открой Bee Swarm Simulator.\n3. Нажми N • меню и откалибруй управление.\n\nДля первого теста встань на Pine Tree и выбери «Я уже на Pine Tree». Для автомаршрута обучи ориентиры во вкладке «Калибровка». Открытие меню останавливает текущий запуск.");help.setTextSize(16);root.addView(help);
        status=new TextView(this);status.setPadding(0,28,0,20);root.addView(status);
        add(root,"1. Специальные возможности",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        add(root,"2. Поверх других приложений",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        add(root,"3. Открыть Roblox с меню Natro",()->{
            if(!Settings.canDrawOverlays(this)){toast("Разреши окно поверх других приложений");return;}
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);
            startForegroundService(new Intent(this,ControlOverlayService.class));
            Intent launch=getPackageManager().getLaunchIntentForPackage("com.roblox.client");
            if(launch!=null)startActivity(launch);else toast("Roblox не установлен");
        });
        add(root,"Закрыть плавающее меню",()->{stopService(new Intent(this,CalibrationOverlayService.class));stopService(new Intent(this,ControlOverlayService.class));});
        setContentView(scroll);
    }
    @Override protected void onResume(){super.onResume();if(status!=null)status.setText("Служба: "+(MacroAccessibilityService.get()!=null?"включена":"выключена")+"\nОкно поверх Roblox: "+(Settings.canDrawOverlays(this)?"разрешено":"не разрешено"));}
    private void add(LinearLayout root,String label,Runnable action){MaterialButton b=new MaterialButton(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());root.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
