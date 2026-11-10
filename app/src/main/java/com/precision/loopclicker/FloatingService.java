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
import android.view.MotionEvent;
import android.view.View;
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

    private static final String CHANNEL_ID =
            "precision_clicker_channel";

    private static final int NOTIFICATION_ID = 1001;

    private static FloatingService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private WindowManager windowManager;

    // پنل شناور
    private LinearLayout panel;
    private TextView titleView;
    private TextView statusView;
    private TextView counterView;

    private WindowManager.LayoutParams panelParams;

    // نشانگر هدف
    private TextView marker;
    private WindowManager.LayoutParams markerParams;

    private int markerSize;
    private int targetX = 500;
    private int targetY = 500;

    private float panelTouchStartX;
    private float panelTouchStartY;
    private int panelStartX;
    private int panelStartY;

    private float markerTouchStartX;
    private float markerTouchStartY;
    private int markerStartX;
    private int markerStartY;

    private volatile boolean running = false;
    private boolean cycleActive = false;
    private boolean overlaysHidden = false;

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

    private Runnable cycleStartRunnable;
    private Runnable nextClickRunnable;
    private Runnable cycleFinishRunnable;
    private Runnable displayRunnable;

    private AutoClickService accessibility;

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        markerSize = dp(56);

        createNotificationChannel();
        startForegroundNotification();

        displayRunnable = new Runnable() {
            @Override
            public void run() {
                updateDisplay();

                if (displayRunnable != null) {
                    handler.postDelayed(this, 50);
                }
            }
        };

        handler.post(displayRunnable);
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    // مخفی و نمایان کردن هم‌زمان پنل و نشانگر
    public static void setOverlaysHidden(boolean hidden) {

        FloatingService service = instance;

        if (service == null) {
            return;
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            service.changeOverlayVisibility(hidden);
        } else {
            service.handler.post(
                    () -> service.changeOverlayVisibility(hidden)
            );
        }
    }

    private void changeOverlayVisibility(boolean hidden) {

        overlaysHidden = hidden;

        int visibility = hidden
                ? View.INVISIBLE
                : View.VISIBLE;

        if (panel != null) {
            panel.setVisibility(visibility);
        }

        if (marker != null) {
            marker.setVisibility(visibility);
        }
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
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

        Intent openIntent =
                new Intent(this, MainActivity.class);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
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
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        String action =
                intent == null ? null : intent.getAction();

        if (ACTION_START.equals(action)) {

            createFloatingViews();
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
            changeOverlayVisibility(false);

        } else {

            createFloatingViews();
        }

        return START_NOT_STICKY;
    }

    // ساخت پنل و نشانگر
    private void createFloatingViews() {

        if (!Settings.canDrawOverlays(this)) {
            return;
        }

        if (panel == null) {
            createPanel();
        }

        if (marker == null) {
            createMarker();
        }

        changeOverlayVisibility(overlaysHidden);
    }

    private int getOverlayType() {

        if (Build.VERSION.SDK_INT >= 26) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }

        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams createOverlayParams(
            int width,
            int height
    ) {

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        width,
                        height,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;

        return params;
    }

    private void createPanel() {

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(12));
        root.setBackground(makeBackground("#202B38", 16));

        // عنوان پنل؛ فقط این قسمت برای جابه‌جایی پنل استفاده می‌شود.
        titleView = new TextView(this);

        titleView.setText("☰  Precision Loop Auto Clicker");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(13);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(dp(3), dp(5), dp(3), dp(10));

        titleView.setOnTouchListener(
                (view, event) -> handlePanelDrag(event)
        );

        root.addView(titleView);

        statusView = new TextView(this);
        statusView.setText("آماده");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(8), 0, dp(5));

        root.addView(statusView);

        counterView = new TextView(this);
        counterView.setText("در انتظار شروع");
        counterView.setTextColor(Color.rgb(255, 193, 7));
        counterView.setTextSize(12);
        counterView.setPadding(0, dp(5), 0, dp(8));

        root.addView(counterView);

        TextView stopButton =
                makeButton("توقف کلیک‌ها", "#C62828");

        stopButton.setOnClickListener(v -> stopLoop());

        root.addView(stopButton);

        TextView closeButton =
                makeButton("بستن پنل", "#455A64");

        closeButton.setOnClickListener(v -> removeFloatingViews());

        root.addView(closeButton);

        panel = root;

        panelParams = createOverlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
        );

        panelParams.x = dp(20);
        panelParams.y = dp(150);

        try {

            windowManager.addView(panel, panelParams);

        } catch (Exception e) {

            panel = null;
            titleView = null;
            statusView = null;
            counterView = null;
            panelParams = null;
        }
    }

    private boolean handlePanelDrag(MotionEvent event) {

        if (panelParams == null || panel == null) {
            return false;
        }

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN:

                panelTouchStartX = event.getRawX();
                panelTouchStartY = event.getRawY();

                panelStartX = panelParams.x;
                panelStartY = panelParams.y;

                return true;

            case MotionEvent.ACTION_MOVE:

                int newX = panelStartX
                        + (int) (event.getRawX() - panelTouchStartX);

                int newY = panelStartY
                        + (int) (event.getRawY() - panelTouchStartY);

                panelParams.x = newX;
                panelParams.y = newY;

                try {
                    windowManager.updateViewLayout(panel, panelParams);
                } catch (Exception ignored) {
                }

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:

                return true;
        }

        return true;
    }

    // ساخت نشانگر قرمز مستقل
    private void createMarker() {

        TextView target = new TextView(this);

        target.setText("⊕");
        target.setTextColor(Color.WHITE);
        target.setTextSize(38);
        target.setGravity(Gravity.CENTER);
        target.setTypeface(null, Typeface.BOLD);

        GradientDrawable background =
                new GradientDrawable();

        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(220, 220, 30, 30));
        background.setStroke(dp(2), Color.WHITE);

        target.setBackground(background);

        target.setElevation(dp(8));

        target.setOnTouchListener(
                (view, event) -> handleMarkerDrag(event)
        );

        marker = target;

        markerParams = createOverlayParams(
                markerSize,
                markerSize
        );

        markerParams.x = dp(500);
        markerParams.y = dp(500);

        try {

            windowManager.addView(marker, markerParams);

            updateTargetFromMarker();

        } catch (Exception e) {

            marker = null;
            markerParams = null;
        }
    }

    private boolean handleMarkerDrag(MotionEvent event) {

        if (markerParams == null || marker == null) {
            return false;
        }

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN:

                markerTouchStartX = event.getRawX();
                markerTouchStartY = event.getRawY();

                markerStartX = markerParams.x;
                markerStartY = markerParams.y;

                return true;

            case MotionEvent.ACTION_MOVE:

                int screenWidth =
                        getResources().getDisplayMetrics().widthPixels;

                int screenHeight =
                        getResources().getDisplayMetrics().heightPixels;

                int newX = markerStartX
                        + (int) (event.getRawX() - markerTouchStartX);

                int newY = markerStartY
                        + (int) (event.getRawY() - markerTouchStartY);

                newX = Math.max(
                        0,
                        Math.min(newX, screenWidth - markerSize)
                );

                newY = Math.max(
                        0,
                        Math.min(newY, screenHeight - markerSize)
                );

                markerParams.x = newX;
                markerParams.y = newY;

                try {
                    windowManager.updateViewLayout(marker, markerParams);
                } catch (Exception ignored) {
                }

                updateTargetFromMarker();

                return true;

            case MotionEvent.ACTION_UP:

                updateTargetFromMarker();
                return true;

            case MotionEvent.ACTION_CANCEL:

                return true;
        }

        return true;
    }

    // مختصات مرکز نشانگر، محل واقعی لمس خواهد بود.
    private void updateTargetFromMarker() {

        if (markerParams == null) {
            return;
        }

        targetX = markerParams.x + markerSize / 2;
        targetY = markerParams.y + markerSize / 2;

        if (accessibility == null) {
            accessibility = AutoClickService.getInstance();
        }

        if (accessibility != null) {
            accessibility.setTarget(targetX, targetY);
        }
    }

    private GradientDrawable makeBackground(
            String color,
            int radius
    ) {

        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), Color.GRAY);

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
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(makeBackground(color, 10));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        params.topMargin = dp(6);
        button.setLayoutParams(params);

        return button;
    }

    // شروع زمان‌بندی اصلی
    private void startLoop(Intent config) {

        stopLoop();

        if (config == null) {
            setStatus("اطلاعات شروع دریافت نشد");
            return;
        }

        startTimeEpochMs =
                config.getLongExtra("start_time_epoch_ms", 0);

        cycleDurationMs =
                config.getLongExtra("cycle_duration", 0);

        pauseDurationMs =
                config.getLongExtra("pause_duration", 0);

        clickIntervalMs =
                config.getLongExtra("click_interval_ms", 0);

        touchDurationMs =
                config.getLongExtra("touch_duration", 5);

        clickCount =
                config.getIntExtra("click_count", 1);

        long wallNow = System.currentTimeMillis();

        if (startTimeEpochMs <= wallNow) {
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

        long remaining = startTimeEpochMs - wallNow;
        long elapsedNow = SystemClock.elapsedRealtime();

        if (remaining > Long.MAX_VALUE - elapsedNow) {
            setStatus("زمان شروع بیش از حد بزرگ است");
            return;
        }

        accessibility = AutoClickService.getInstance();

        if (accessibility == null) {
            setStatus("سرویس دسترسی‌پذیری فعال نیست");
            return;
        }

        updateTargetFromMarker();

        running = true;
        cycleActive = false;
        cycleIndex = 0;

        anchorElapsedMs = elapsedNow + remaining;

        long generation = runGeneration;

        setStatus("زمان‌بندی فعال شد");

        scheduleCycleStart(generation, anchorElapsedMs);
    }

    private void postAtElapsedTime(
            Runnable runnable,
            long targetElapsed
    ) {

        long now = SystemClock.elapsedRealtime();

        long delay = targetElapsed <= now
                ? 0
                : targetElapsed - now;

        handler.postDelayed(runnable, delay);
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

        postAtElapsedTime(cycleStartRunnable, targetElapsed);
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

        cycleStartElapsedMs =
                SystemClock.elapsedRealtime();

        cycleStartWallMs =
                System.currentTimeMillis();

        if (cycleDurationMs >
                Long.MAX_VALUE - cycleStartElapsedMs) {

            setStatus("مدت چرخه بیش از حد بزرگ است");
            stopLoop();
            return;
        }

        cycleDeadlineElapsedMs =
                cycleStartElapsedMs + cycleDurationMs;

        accessibility.resetClickCounters();

        successAtStart = accessibility.getSuccessfulClicks();
        failedAtStart = accessibility.getFailedClicks();

        updateTargetFromMarker();

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

        long target;

        if (clickNumber == 0) {
            target = now;
        } else {

            if (clickIntervalMs >
                    Long.MAX_VALUE - lastClickDispatchElapsedMs) {

                setStatus("فاصله کلیک بیش از حد بزرگ است");
                stopLoop();
                return;
            }

            target = lastClickDispatchElapsedMs + clickIntervalMs;
        }

        removeRunnable(nextClickRunnable);

        nextClickRunnable =
                () -> dispatchNextClick(generation);

        postAtElapsedTime(nextClickRunnable, target);
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

        cycleFinishRunnable =
                () -> finishCycle(generation);

        postAtElapsedTime(
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

        if (accessibility != null
                && accessibility.isGestureInProgress()) {

            handler.postDelayed(
                    () -> finishCycle(generation),
                    5
            );

            return;
        }

        cycleActive = false;

        long actualEnd =
                SystemClock.elapsedRealtime();

        long actualDuration =
                actualEnd - cycleStartElapsedMs;

        long success = accessibility == null
                ? 0 : accessibility.getSuccessfulClicks();

        long failed = accessibility == null
                ? 0 : accessibility.getFailedClicks();

        long avgLatency = accessibility == null
                ? 0 : accessibility.getAverageGestureLatencyNanos();

        long maxLatency = accessibility == null
                ? 0 : accessibility.getMaxGestureLatencyNanos();

        long measured = accessibility == null
                ? 0 : accessibility.getMeasuredGestures();

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

        setStatus("چرخه " + cycleIndex + " تکمیل شد");

        long period = cycleDurationMs + pauseDurationMs;

        long nextTarget;

        if (cycleIndex >
                (Long.MAX_VALUE - anchorElapsedMs) / period) {

            nextTarget = SystemClock.elapsedRealtime();

        } else {

            nextTarget =
                    anchorElapsedMs + cycleIndex * period;
        }

        long now = SystemClock.elapsedRealtime();

        if (nextTarget < now) {
            nextTarget = now;
        }

        scheduleCycleStart(generation, nextTarget);
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

            if (cycleIndex >
                    (Long.MAX_VALUE - anchorElapsedMs) / period) {

                nextTarget = now;

            } else {

                nextTarget =
                        anchorElapsedMs + cycleIndex * period;
            }

            long remaining = Math.max(0, nextTarget - now);

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
                    "کلیک "
                            + clickNumber
                            + " از "
                            + clickCount
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
            titleView = null;
            statusView = null;
            counterView = null;
            panelParams = null;
        }

        if (marker != null) {

            try {
                windowManager.removeView(marker);
            } catch (Exception ignored) {
            }

            marker = null;
            markerParams = null;
        }
    }

    @Override
    public void onDestroy() {

        stopLoop();

        removeRunnable(displayRunnable);
        displayRunnable = null;

        removeFloatingViews();

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}