package dev.vorga.natromobile;

import android.content.Context;
import android.content.SharedPreferences;

final class MacroConfig {
    final SharedPreferences p;
    MacroConfig(Context c) { p=c.getSharedPreferences("config",Context.MODE_PRIVATE); }
    float number(String key,float def) { return p.getFloat(key,def); }
    int integer(String key,int def) { return p.getInt(key,def); }
    boolean flag(String key,boolean def) { return p.getBoolean(key,def); }
    String text(String key,String def) { return p.getString(key,def); }
    double speed() {
        return MovementMath.speed(number("movespeed",28),flag("hasty_guard",false),flag("gifted_hasty",false),
                integer("haste",0),flag("coconut",false),flag("bear",false),flag("haste_plus",false),flag("oil",false),flag("smoothie",false));
    }
    int x(String key,int width) { return Math.round(number(key+"_nx",-1)*width); }
    int y(String key,int height) { return Math.round(number(key+"_ny",-1)*height); }
    boolean point(String key) { return number(key+"_nx",-1)>=0 && number(key+"_ny",-1)>=0; }
    String validate(int width,int height) {
        if(width<=height) return "Открой Roblox в альбомной ориентации";
        if(integer("cal_w",0)!=width || integer("cal_h",0)!=height) return "Экран изменился. Повтори калибровку управления в Roblox";
        for(String key:new String[]{"joy","jump","tool"}) if(!point(key)) return "Откалибруй управление: "+key;
        float radius=number("radius_n",0)*Math.min(width,height);
        int cx=x("joy",width),cy=y("joy",height);
        if(radius<12 || cx-radius<0 || cx+radius>=width || cy-radius<0 || cy+radius>=height)
            return "Круг джойстика выходит за экран. Повтори калибровку";
        if(!Double.isFinite(speed()) || speed()<1 || speed()>250) return "Некорректная скорость";
        if(number("timing_scale",1)<.5 || number("timing_scale",1)>2) return "Коэффициент времени: 0.5–2";
        if(integer("run_seconds",900)<10 || integer("run_seconds",900)>7200) return "Время сбора: 10–7200 с";
        if(integer("cycles",1)<0 || integer("cycles",1)>1000) return "Циклы: 0–1000";
        try { PatternFactory.create(text("pattern","Snake"),number("pattern_size",1),integer("pattern_reps",3)); }
        catch(IllegalArgumentException e) { return e.getMessage(); }
        return null;
    }
}
