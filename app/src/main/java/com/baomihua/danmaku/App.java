package com.baomihua.danmaku;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public final class App extends Application {

    private static App instance;
    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        installCrashHandler();
    }

    public static App get() {
        return instance;
    }

    public static Prefs prefs(Context context) {
        App app = instance;
        if (app == null) {
            app = (App) context.getApplicationContext();
            app.prefs = new Prefs(app);
        }
        return app.prefs;
    }

    public static Handler main() {
        return instance != null ? instance.main : new Handler(Looper.getMainLooper());
    }

    public static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main().post(r);
        }
    }

    /**
     * 把崩溃写到文件里。前台服务崩了的话界面会直接消失，用户什么都看不到，
     * 所以这里必须留证据，面板的「自检」里会显示。
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    java.io.StringWriter writer = new java.io.StringWriter();
                    error.printStackTrace(new java.io.PrintWriter(writer));
                    String text = "时间: " + new java.util.Date() + "\n线程: " + thread.getName()
                            + "\n\n" + writer;
                    java.io.File file = new java.io.File(getFilesDir(), "last_crash.txt");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(file);
                    out.write(text.getBytes("UTF-8"));
                    out.close();
                    Diag.error("进程崩溃（" + thread.getName() + "）", error);
                } catch (Throwable ignored) {
                    // 崩溃处理里不能再崩
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }

    /** 上次崩溃的堆栈，没有就是空串。 */
    public static String lastCrash(Context context) {
        try {
            java.io.File file = new java.io.File(context.getApplicationContext().getFilesDir(),
                    "last_crash.txt");
            if (!file.exists()) {
                return "";
            }
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void clearLastCrash(Context context) {
        try {
            java.io.File file = new java.io.File(context.getApplicationContext().getFilesDir(),
                    "last_crash.txt");
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 悬浮窗权限（有的系统叫「显示在其他应用上层」）。 */
    public static boolean canDrawOverlays(Context context) {
        try {
            return android.provider.Settings.canDrawOverlays(context);
        } catch (Exception e) {
            return false;
        }
    }
}
