package dev.vorga.natromobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** User-taught mobile UI landmarks, independent of desktop resolution or localization. */
final class ScreenAnchors {
    private final Context context;
    private final MacroConfig c;
    ScreenAnchors(Context context) {this.context=context;c=new MacroConfig(context);}
    private File file(String name) {return new File(context.getFilesDir(),"anchor_"+name+".png");}
    boolean exists(String name) {return file(name).isFile() && c.integer(name+"_w",0)>0;}
    void save(String name,Bitmap shot,int left,int top,int right,int bottom) throws IOException {
        int w=right-left,h=bottom-top;
        if(w<12||h<12||left<0||top<0||right>shot.getWidth()||bottom>shot.getHeight()) throw new IOException("Выдели прямоугольник минимум 12 × 12 px");
        Bitmap crop=Bitmap.createBitmap(shot,left,top,w,h);
        try(FileOutputStream out=new FileOutputStream(file(name))) {
            if(!crop.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("Не удалось сохранить ориентир");
        } finally {crop.recycle();}
        c.p.edit().putInt(name+"_x",left).putInt(name+"_y",top).putInt(name+"_w",w).putInt(name+"_h",h)
                .putInt(name+"_screen_w",shot.getWidth()).putInt(name+"_screen_h",shot.getHeight()).apply();
    }
    boolean matches(String name,Bitmap shot) {
        if(!exists(name)||shot==null||shot.getWidth()!=c.integer(name+"_screen_w",0)||shot.getHeight()!=c.integer(name+"_screen_h",0))return false;
        Bitmap expected=BitmapFactory.decodeFile(file(name).getPath());
        if(expected==null)return false;
        int ox=c.integer(name+"_x",0),oy=c.integer(name+"_y",0),w=expected.getWidth(),h=expected.getHeight();
        if(ox+w>shot.getWidth()||oy+h>shot.getHeight()){expected.recycle();return false;}
        // Small positional tolerance; fixed region prevents unrelated red/gold scene objects matching.
        double best=Double.MAX_VALUE;
        for(int dy=-4;dy<=4;dy+=2)for(int dx=-4;dx<=4;dx+=2){
            if(ox+dx<0||oy+dy<0||ox+dx+w>shot.getWidth()||oy+dy+h>shot.getHeight())continue;
            long error=0;int samples=0;
            for(int y=0;y<h;y+=Math.max(1,h/24))for(int x=0;x<w;x+=Math.max(1,w/40)){
                int a=expected.getPixel(x,y),b=shot.getPixel(ox+x+dx,oy+y+dy);
                error+=Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b));samples+=3;
            }
            best=Math.min(best,(double)error/Math.max(1,samples));
        }
        expected.recycle();
        return best<=c.integer("anchor_tolerance",18);
    }
}
