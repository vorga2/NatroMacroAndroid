package dev.vorga.natromobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;

final class SettingsPanel extends LinearLayout {
    interface Actions { void start();void calibrate(String kind);void testCamera();void close(); }
    private final MacroConfig config;
    private final Actions actions;
    private final LinearLayout body;
    private final List<Runnable> writers=new ArrayList<>();
    private SharedPreferences.Editor editor;
    private String tab="Сбор";
    SettingsPanel(Context context,Actions actions){
        super(context);this.actions=actions;config=new MacroConfig(context);setOrientation(VERTICAL);setPadding(dp(16),dp(12),dp(16),dp(12));
        TextView title=text("Natro • Pine Tree",24);addView(title);
        LinearLayout tabs=new LinearLayout(context);
        for(String name:new String[]{"Сбор","Движение","Маршрут","Калибровка"}){
            MaterialButton b=button(name,()->{if(save()){tab=name;render();}});b.setTextSize(10);b.setMinWidth(0);b.setPadding(2,0,2,0);
            tabs.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));
        }addView(tabs);
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);
        body=new LinearLayout(context);body.setOrientation(VERTICAL);scroll.addView(body);
        addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(context);
        footer.addView(button("Сохранить",()->{if(save())Toast.makeText(context,"Настройки сохранены",Toast.LENGTH_SHORT).show();}),new LinearLayout.LayoutParams(0,dp(52),1));
        footer.addView(button("Запустить",()->{if(save())actions.start();}),new LinearLayout.LayoutParams(0,dp(52),1));addView(footer);
        addView(button("Свернуть меню",()->{if(save())actions.close();}));render();
    }
    private void render(){
        body.removeAllViews();writers.clear();
        switch(tab){
            case "Сбор":
                heading("Фарм Pine Tree");
                choice("Старт","start_mode",new String[]{"field","hive"},new String[]{"Я уже на Pine Tree","Автомаршрут от улья"},"field");
                choice("Паттерн","pattern",PatternFactory.NAMES,PatternFactory.NAMES,"Snake");
                number("Размер: XS .25 · S .5 · M 1 · L 1.5 · XL 2","pattern_size",1,.25,2,false);
                number("Повторы паттерна","pattern_reps",3,1,10,true);
                number("Время сбора, секунды","run_seconds",900,10,7200,true);
                toggle("Инверсия влево / вправо","invert_lr",false);
                toggle("Инверсия вперёд / назад","invert_fb",false);
                toggle("e_lol: камера направлена в угол","facing_corner",false);
                toggle("Удерживать инструмент сбора","tool_hold",true);
                number("Интервал кликов, мс (если удержание выключено)","tool_interval",250,100,2000,true);
                toggle("Завершать сбор по полному рюкзаку","detect_full",false);
                note("Проверка полного рюкзака требует ориентира full. При старте с поля поставь персонажа в выбранную начальную точку и зафиксируй камеру.");break;
            case "Движение":
                heading("Расстояния и длительности");
                number("Базовая скорость БЕЗ множителей ниже","movespeed",28,1,80,false);
                number("Коэффициент времени","timing_scale",1,.5,2,false);
                number("Текущие стаки Haste (вручную)","haste",0,0,10,true);
                toggle("Hasty Guard ×1.10","hasty_guard",false);toggle("Gifted Hasty ×1.15","gifted_hasty",false);
                toggle("Coconut Haste +10","coconut",false);toggle("Bear Morph +4","bear",false);
                toggle("Haste+ ×2","haste_plus",false);toggle("Oil ×1.20","oil",false);toggle("Super Smoothie ×1.25","smoothie",false);
                note("Баффы задаются вручную. Автоматическое распознавание меняющихся стаков пока не перенесено. Чтобы избежать двойного учёта экипировки, введи скорость без отмеченных множителей.");
                number("Длина свайпа для поворота на 45° (доля ширины)","camera_swipe",.14,.02,.25,false);
                body.addView(button("Тест поворота вправо",()->{if(save())actions.testCamera();}));
                toggle("Проверил: один свайп поворачивает на 45°","camera_calibrated",false);
                note("Roblox: альбомный экран, фиксированный джойстик, камера Classic, чувствительность и масштаб не менять после калибровки.");break;
            case "Маршрут":
                heading("Улей → Pine Tree → улей");
                number("Номер занятого улья","hive_slot",1,1,6,true);
                toggle("Повторять цикл с возвращением к улью","repeat_cycle",false);
                number("Циклов (0 = до остановки)","cycles",1,0,1000,true);
                number("Ожидание возрождения, мс","respawn_ms",5500,3000,15000,true);
                number("Лимит переработки, секунды","convert_seconds",120,10,900,true);
                toggle("Ставить спринклер после прилёта","sprinkler",false);
                number("Допуск распознавания ориентиров (RGB)","anchor_tolerance",18,3,35,true);
                note("Автомаршрут использует красную пушку и планер. Нужны доступ к пушке, Pine Tree и занятый улей. Возврат — через Reset Character. Перед первым циклом обучи hive, cannon, pine и empty.");
                note("hive: фиксированный фрагмент вида от улья в направлении рампы. cannon: текст подсказки взаимодействия. pine: стабильный фрагмент после приземления, до сбора. empty/full: неизменный участок индикатора рюкзака в нужном состоянии.");break;
            default:
                heading("Настройка прямо в Roblox");
                note("Открой нужный экран игры, затем выбери калибровку. Меню исчезнет. Каждый выбор сохраняется после отпускания пальца. Кнопка × отменяет.");
                body.addView(button("Управление: джойстик, прыжок, сбор",()->actions.calibrate("controls")));
                for(String[] item:new String[][]{{"interact","Кнопка взаимодействия / Make Honey"},{"menu","Кнопка меню Roblox"},{"reset","Reset Character в меню"},{"confirm","Подтверждение Reset"},{"sprinkler","Слот спринклера"}}){
                    String key=item[0];body.addView(button(item[1]+(config.point(key)?" ✓":""),()->actions.calibrate(key)));
                }
                for(String name:new String[]{"hive","cannon","pine","empty","full"}){
                    body.addView(button("Ориентир "+name+(new ScreenAnchors(getContext()).exists(name)?" ✓":""),()->actions.calibrate("anchor:"+name)));
                }
                note("Для ориентира укажи два противоположных угла небольшого узнаваемого фрагмента. Не выделяй пчёл, игроков, мигающий текст или одноцветный фон. После смены разрешения обучи ориентиры заново.");
        }
    }
    boolean save(){
        editor=config.p.edit();
        try{for(Runnable w:writers)w.run();editor.apply();return true;}
        catch(IllegalArgumentException e){Toast.makeText(getContext(),e.getMessage(),Toast.LENGTH_LONG).show();return false;}
    }
    private void number(String label,String key,double def,double min,double max,boolean integer){
        TextInputLayout box=new TextInputLayout(getContext());box.setHint(label);
        TextInputEditText input=new TextInputEditText(box.getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER|(integer?0:InputType.TYPE_NUMBER_FLAG_DECIMAL));
        input.setText(integer?String.valueOf(config.integer(key,(int)def)):String.valueOf(config.number(key,(float)def)));
        box.addView(input);body.addView(box,margin());
        writers.add(()->{
            double value;
            try{value=Double.parseDouble(input.getText().toString().trim().replace(',','.'));}catch(Exception e){value=Double.NaN;}
            if(!Double.isFinite(value)||value<min||value>max||(integer&&value!=Math.rint(value))){box.setError(min+"–"+max);throw new IllegalArgumentException(label+": "+min+"–"+max);}
            box.setError(null);if(integer)editor.putInt(key,(int)value);else editor.putFloat(key,(float)value);
        });
    }
    private void toggle(String label,String key,boolean def){
        MaterialSwitch v=new MaterialSwitch(getContext());v.setText(label);v.setChecked(config.flag(key,def));body.addView(v,margin());
        writers.add(()->editor.putBoolean(key,v.isChecked()));
    }
    private void choice(String label,String key,String[] values,String[] labels,String def){
        body.addView(text(label,14));Spinner s=new Spinner(getContext());s.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,labels));
        for(int i=0;i<values.length;i++)if(values[i].equals(config.text(key,def)))s.setSelection(i);
        body.addView(s,margin());writers.add(()->editor.putString(key,values[s.getSelectedItemPosition()]));
    }
    private void heading(String label){body.addView(text(label,20));}
    private void note(String label){TextView v=text(label,13);v.setTextColor(0xFFBFC9B5);body.addView(v,margin());}
    private TextView text(String label,int size){TextView v=new TextView(getContext());v.setText(label);v.setTextColor(0xFFE1E5D9);v.setTextSize(size);return v;}
    private MaterialButton button(String label,Runnable action){MaterialButton b=new MaterialButton(getContext());b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private LayoutParams margin(){LayoutParams p=new LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(8));return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
