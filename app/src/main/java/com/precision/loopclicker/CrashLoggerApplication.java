
package com.precision.loopclicker;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashLoggerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            saveCrash(thread, error);

            android.os.Process.killProcess(
                    android.os.Process.myPid()
            );
            System.exit(10);
        });
    }

    private void saveCrash(Thread thread, Throwable error) {
        try {
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));

            String report =
                    "PRECISION LOOP CRASH REPORT\n"
                    + "Date: "
                    + new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.US
                    ).format(new Date())
                    + "\nAndroid: " + Build.VERSION.RELEASE
                    + "\nSDK: " + Build.VERSION.SDK_INT
                    + "\nThread: " + thread.getName()
                    + "\n\n"
                    + writer;

            File directory = getExternalFilesDir("crash");

            if (directory == null) {
                directory = getFilesDir();
            }

            if (!directory.exists()) {
                directory.mkdirs();
            }

            File file = new File(directory, "last_crash.txt");

            try (FileOutputStream output =
                         new FileOutputStream(file, false)) {
                output.write(report.getBytes("UTF-8"));
                output.flush();
            }

        } catch (Exception ignored) {
        }
    }
}
