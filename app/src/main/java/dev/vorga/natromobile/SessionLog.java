package dev.vorga.natromobile;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Append-only session log written to the app's external files dir
 * (no extra permissions needed). Best-effort: never throws and never
 * blocks the macro for more than a few milliseconds.
 *
 * The file is rotated in place once it exceeds MAX_BYTES, keeping the
 * most recent half — this keeps long 24/7 sessions bounded on disk while
 * preserving the latest events for post-mortem analysis.
 */
final class SessionLog {
    private static final long MAX_BYTES = 512 * 1024L;
    private static final Object LOCK = new Object();
    private static volatile File file;

    private SessionLog() {}

    static void init(Context context) {
        if (file != null) return;
        synchronized (LOCK) {
            if (file != null) return;
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir == null) dir = context.getFilesDir();
                if (dir != null) file = new File(dir, "macro-session.log");
            } catch (Exception ignored) {}
        }
    }

    static void i(String msg) { write("INFO ", msg); }
    static void w(String msg) { write("WARN ", msg); }
    static void e(String msg) { write("ERROR", msg); }

    static String path() {
        File f = file;
        return f == null ? null : f.getAbsolutePath();
    }

    private static void write(String level, String msg) {
        File f = file;
        if (f == null) return;
        synchronized (LOCK) {
            try {
                if (f.length() > MAX_BYTES) rotate(f);
                String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()) + " [" + level + "] " + msg + "\n";
                Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
                try {
                    w.write(line);
                    w.flush();
                } finally {
                    w.close();
                }
            } catch (Exception ignored) {}
        }
    }

    private static void rotate(File f) {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            int keep = Math.min(all.length, (int) (MAX_BYTES / 2));
            byte[] tail = Arrays.copyOfRange(all, all.length - keep, all.length);
            java.nio.file.Files.write(f.toPath(), tail);
        } catch (Exception ignored) {}
    }
}
