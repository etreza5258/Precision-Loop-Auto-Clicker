
package com.precision.loopclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class FloatingService extends Service {

    private static final String ACTION_START = "ACTION_START";
    private static final String ACTION_STOP = "ACTION_STOP";
    private static final String ACTION_SHUTDOWN = "ACTION_SHUTDOWN";
    private static final String ACTION_SHOW_PANEL = "SHOW_PANEL";

    private static final String CHANNEL_ID = "precision_clicker_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private LinearLayout panel;
    private TextView statusView;
    private TextView counterView;

    private volatile boolean running = false;
    private boolean cycleActive = false;

    private long runGeneration = 0;
    private long cycleIndex = 0;

    private long startTimeEpochMs;
    private long cycleDurationMs;
    private long pauseDurationMs;
    private long clickIntervalMs;
    private long touchDurationMs;

    private int clickCount;
    private int clickNumber;

    private long anchorElapsedMs;
    private long cycleStartElapsedMs;
    private long cycleDeadlineElapsedMs;
    private long lastClickDispatchElapsedMs;
    private long cycleStartWallMs;

    private long successAtStart;
    private long failedAtStart;

    private final int targetX = 500;
    private final int targetY = 500;

    private Runnable cycleStartRunnable;
    private Runnable nextClickRunnable;
    private Runnable cycleFinishRunnable;
    private Runnable displayRunnable;

    private AutoClickService accessibility;

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        startForegroundNotification();

        displayRunnable = new Runnable() {
            @Override
            public void run() {
                updateDisplay();
                handler.postDelayed(this, 50);
            }
        };

        handler.post(displayRunnable);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Precision Loop Auto Clicker",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("وضعیت اجرای اتوکلیکر");

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Precision Loop Auto Clicker")
                .setContentText("سرویس اتوکلیکر فعال است")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = intent == null ? null : intent.getAction();

        if (ACTION_START.equals(action)) {

            createFloatingViews();

            // تنظیمات مستقیماً از Intent ارسالی MainActivity خوانده می‌شوند.
            startLoop(intent);

        } else if (ACTION_STOP.equals(action)) {

            stopLoop();

        } else if (ACTION_SHUTDOWN.equals(action)) {

            stopLoop();
            removeFloatingViews();
            stopForeground(true);
            stopSelf();

        } else if (ACTION_SHOW_PANEL.equals(action)) {

            createFloatingViews();

        } else {

            createFloatingViews();
        }

        return START_NOT_STICKY;
    }

    private void createFloatingViews() {

        if (panel != null) {
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 14, 18, 14);
        root.setBackground(makeBackground("#202B38", 18));

        TextView title = new TextView(this);
        title.setText("Precision Loop Auto Clicker");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("آماده");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(13);
        statusView.setPadding(0, 12, 0, 6);
        root.addView(statusView);

        counterView = new TextView(this);
        counterView.setText("در انتظار شروع");
        counterView.setTextColor(Color.rgb(255, 193, 7));
        counterView.setTextSize(13);
        counterView.setPadding(0, 5, 0, 10);
        root.addView(counterView);

        TextView stopButton = makeButton(
                "توقف کلیک‌ها",
                "#C62828"
        );

        stopButton.setOnClickListener(v -> stopLoop());
        root.addView(stopButton);

        TextView closeButton = makeButton(
                "بستن پنل",
                "#455A64"
        );

        closeButton.setOnClickListener(v -> removeFloatingViews());
        root.addView(closeButton);

        panel = root;

        int overlayType;

        if (Build.VERSION.SDK_INT >= 26) {
            overlayType =
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType =
                    WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 150;

        try {
            windowManager.addView(panel, params);

        } catch (Exception e) {
            panel = null;
            statusView = null;
            counterView = null;
        }
    }

    private GradientDrawable makeBackground(
            String color,
            int radius
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(radius);
        drawable.setStroke(1, Color.GRAY);
        return drawable;
    }

    private TextView makeButton(
            String text,
            String color
    ) {
        TextView button = new TextView(this);

        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(12, 12, 12, 12);
        button.setBackground(makeBackground(color, 10));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        params.topMargin = 6;
        button.setLayoutParams(params);

        return button;
    }

    private void startLoop(Intent config) {

        stopLoop();

        if (config == null) {
            setStatus("اطلاعات شروع دریافت نشد");
            return;
        }

        startTimeEpochMs = config.getLongExtra(
                "start_time_epoch_ms",
                0
        );

        cycleDurationMs = config.getLongExtra(
                "cycle_duration",
                0
        );

        pauseDurationMs = config.getLongExtra(
                "pause_duration",
                0
        );

        clickIntervalMs = config.getLongExtra(
                "click_interval_ms",
                0
        );

        touchDurationMs = config.getLongExtra(
                "touch_duration",
                5
        );

        clickCount = config.getIntExtra(
                "click_count",
                1
        );

        if (startTimeEpochMs <= System.currentTimeMillis()) {
            setStatus("زمان شروع باید در آینده باشد");
            return;
        }

        if (cycleDurationMs <= 0
                || clickCount <= 0
                || clickIntervalMs < 0
                || pauseDurationMs < 0
                || touchDurationMs < 1) {

            setStatus("تنظیمات زمان یا تعداد کلیک نامعتبر است");
            return;
        }

        if (cycleDurationMs > Long.MAX_VALUE - pauseDurationMs) {
            setStatus("مدت چرخه و مکث بیش از حد بزرگ است");
            return;
        }

        accessibility = AutoClickService.getInstance();

        if (accessibility == null) {
            setStatus("سرویس دسترسی‌پذیری فعال نیست");
            return;
        }

        running = true;
        cycleActive = false;
        cycleIndex = 0;

        long remaining =
                startTimeEpochMs - System.currentTimeMillis();

        anchorElapsedMs =
                SystemClock.elapsedRealtime() + remaining;

        long generation = runGeneration;

        setStatus("زمان‌بندی فعال شد");

        scheduleCycleStart(
                generation,
                anchorElapsedMs
        );
    }

    private void scheduleCycleStart(
            long generation,
            long targetElapsed
    ) {
        if (!running || generation != runGeneration) {
            return;
        }

        removeRunnable(cycleStartRunnable);

        cycleStartRunnable = () -> {
            if (!running || generation != runGeneration) {
                return;
            }

            startCycle(generation);
        };

        handler.postAtTime(
                cycleStartRunnable,
                Math.max(SystemClock.elapsedRealtime(), targetElapsed)
        );
    }

    private void startCycle(long generation) {

        if (!running || generation != runGeneration) {
            return;
        }

        accessibility = AutoClickService.getInstance();

        if (accessibility == null) {
            setStatus("سرویس کلیک در دسترس نیست");
            stopLoop();
            return;
        }

        if (accessibility.isGestureInProgress()) {
            handler.postDelayed(
                    () -> startCycle(generation),
                    5
            );
            return;
        }

        cycleActive = true;
        clickNumber = 0;

        cycleStartElapsedMs = SystemClock.elapsedRealtime();
        cycleStartWallMs = System.currentTimeMillis();

        cycleDeadlineElapsedMs =
                cycleStartElapsedMs + cycleDurationMs;

        accessibility.resetClickCounters();

        successAtStart = accessibility.getSuccessfulClicks();
        failedAtStart = accessibility.getFailedClicks();

        accessibility.setTarget(targetX, targetY);
        accessibility.setTouchDuration(touchDurationMs);

        setStatus("چرخه " + (cycleIndex + 1) + " شروع شد");

        scheduleNextClick(generation);
    }

    private void scheduleNextClick(long generation) {

        if (!running
                || generation != runGeneration
                || !cycleActive) {
            return;
        }

        if (clickNumber >= clickCount) {
            waitForCycleEnd(generation);
            return;
        }

        long now = SystemClock.elapsedRealtime();

        long target = clickNumber == 0
                ? now
                : lastClickDispatchElapsedMs + clickIntervalMs;

        removeRunnable(nextClickRunnable);

        nextClickRunnable = () ->
                dispatchNextClick(generation);

        handler.postAtTime(
                nextClickRunnable,
                Math.max(now, target)
        );
    }

    private void dispatchNextClick(long generation) {

        if (!running
                || generation != runGeneration
                || !cycleActive) {
            return;
        }

        if (accessibility == null) {
            setStatus("سرویس کلیک در دسترس نیست");
            stopLoop();
            return;
        }

        if (accessibility.isGestureInProgress()) {
            handler.postDelayed(
                    () -> dispatchNextClick(generation),
                    5
            );
            return;
        }

        clickNumber++;

        lastClickDispatchElapsedMs =
                SystemClock.elapsedRealtime();

        setStatus(
                "در حال اجرای کلیک "
                        + clickNumber
                        + " از "
                        + clickCount
        );

        accessibility.performClick(() -> {

            handler.post(() -> {

                if (!running
                        || generation != runGeneration
                        || !cycleActive) {
                    return;
                }

                if (clickNumber < clickCount) {
                    scheduleNextClick(generation);
                } else {
                    waitForCycleEnd(generation);
                }
            });
        });
    }

    private void waitForCycleEnd(long generation) {

        if (!running || generation != runGeneration) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now >= cycleDeadlineElapsedMs) {
            finishCycle(generation);
            return;
        }

        removeRunnable(cycleFinishRunnable);

        cycleFinishRunnable = () ->
                finishCycle(generation);

        handler.postAtTime(
                cycleFinishRunnable,
                cycleDeadlineElapsedMs
        );
    }

    private void finishCycle(long generation) {

        if (!running
                || generation != runGeneration
                || !cycleActive) {
            return;
        }

        cycleActive = false;

        long actualEnd = SystemClock.elapsedRealtime();

        long actualDuration =
                actualEnd - cycleStartElapsedMs;

        long success = accessibility == null
                ? 0
                : accessibility.getSuccessfulClicks();

        long failed = accessibility == null
                ? 0
                : accessibility.getFailedClicks();

        long avgLatency = accessibility == null
                ? 0
                : accessibility.getAverageGestureLatencyNanos();

        long maxLatency = accessibility == null
                ? 0
                : accessibility.getMaxGestureLatencyNanos();

        long measured = accessibility == null
                ? 0
                : accessibility.getMeasuredGestures();

        CycleReportStore.addReport(
                this,
                cycleIndex + 1,
                cycleStartWallMs,
                actualDuration,
                success - successAtStart,
                failed - failedAtStart,
                avgLatency,
                maxLatency,
                measured
        );

        cycleIndex++;

        setStatus(
                "چرخه " + cycleIndex + " تکمیل شد"
        );

        long period = cycleDurationMs + pauseDurationMs;

        long nextTarget;

        if (cycleIndex > (Long.MAX_VALUE - anchorElapsedMs) / period) {
            nextTarget = SystemClock.elapsedRealtime();
        } else {
            nextTarget =
                    anchorElapsedMs + cycleIndex * period;
        }

        long now = SystemClock.elapsedRealtime();

        if (nextTarget < now) {
            nextTarget = now;
        }

        scheduleCycleStart(
                generation,
                nextTarget
        );
    }

    private void updateDisplay() {

        if (statusView == null || counterView == null) {
            return;
        }

        if (!running) {
            counterView.setText("متوقف");
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (!cycleActive) {

            long period = cycleDurationMs + pauseDurationMs;

            long nextTarget;

            if (cycleIndex > (Long.MAX_VALUE - anchorElapsedMs) / period) {
                nextTarget = now;
            } else {
                nextTarget =
                        anchorElapsedMs + cycleIndex * period;
            }

            long remaining =
                    Math.max(0, nextTarget - now);

            counterView.setText(
                    "شروع چرخه بعدی: " + formatMillis(remaining)
            );

            return;
        }

        long remaining =
                Math.max(0, cycleDeadlineElapsedMs - now);

        if (clickNumber >= clickCount) {

            counterView.setText(
                    "پایان چرخه: " + formatMillis(remaining)
            );

        } else {

            counterView.setText(
                    "کلیک " + clickNumber
                            + " از " + clickCount
                            + " | پایان چرخه: "
                            + formatMillis(remaining)
            );
        }
    }

    private String formatMillis(long millis) {

        long hours = millis / 3600000;
        long minutes = (millis % 3600000) / 60000;
        long seconds = (millis % 60000) / 1000;
        long ms = millis % 1000;

        return String.format(
                Locale.US,
                "%02d:%02d:%02d.%03d",
                hours,
                minutes,
                seconds,
                ms
        );
    }

    private void setStatus(String text) {

        if (statusView != null) {
            statusView.setText(text);
        }
    }

    private void removeRunnable(Runnable runnable) {

        if (runnable != null) {
            handler.removeCallbacks(runnable);
        }
    }

    private void stopLoop() {

        running = false;
        cycleActive = false;
        runGeneration++;

        removeRunnable(cycleStartRunnable);
        removeRunnable(nextClickRunnable);
        removeRunnable(cycleFinishRunnable);

        cycleStartRunnable = null;
        nextClickRunnable = null;
        cycleFinishRunnable = null;

        setStatus("متوقف شد");

        if (counterView != null) {
            counterView.setText("متوقف");
        }
    }

    private void removeFloatingViews() {

        if (panel != null) {

            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) {
            }

            panel = null;
            statusView = null;
            counterView = null;
        }
    }

    @Override
    public void onDestroy() {

        stopLoop();

        removeRunnable(displayRunnable);

        removeFloatingViews();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
