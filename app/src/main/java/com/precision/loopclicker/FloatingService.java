
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class FloatingService extends Service {

    public static final String ACTION_START =
            "com.precision.loopclicker.ACTION_START";

    public static final String ACTION_STOP =
            "com.precision.loopclicker.ACTION_STOP";

    public static final String ACTION_SHUTDOWN =
            "com.precision.loopclicker.ACTION_SHUTDOWN";

    public static final String SHOW_PANEL =
            "com.precision.loopclicker.SHOW_PANEL";

    private static final String CHANNEL_ID =
            "precision_loop_channel";

    private static final int NOTIFICATION_ID = 4101;

    private static final long SERVICE_RETRY_MS = 250;
    private static final long GESTURE_RETRY_MS = 10;

    private static volatile FloatingService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private WindowManager windowManager;

    private LinearLayout panel;
    private TextView titleView;
    private TextView statusView;
    private TextView counterView;
    private TextView markerView;

    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams markerParams;

    private int markerSize;

    private int targetX = 500;
    private int targetY = 500;

    private volatile boolean running;
    private boolean cycleActive;

    private long runGeneration;
    private long cycleIndex;

    private long anchorElapsedMs;
    private long cycleDurationMs;
    private long pauseDurationMs;
    private long clickIntervalMs;

    private long cycleStartElapsedMs;
    private long cycleDeadlineElapsedMs;
    private long lastClickDispatchElapsedMs;
    private long cycleStartWallMs;

    private long clickCount;
    private long clickNumber;

    private long successAtStart;
    private long failedAtStart;

    private Runnable cycleStartRunnable;
    private Runnable nextClickRunnable;
    private Runnable cycleFinishRunnable;
    private Runnable displayRunnable;

    private AutoClickService accessibility;

    private float panelTouchRawX;
    private float panelTouchRawY;

    private int panelStartX;
    private int panelStartY;

    private float markerTouchRawX;
    private float markerTouchRawY;

    private int markerStartX;
    private int markerStartY;

    public static FloatingService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

        markerSize = dp(56);

        createNotificationChannel();
        startForegroundCompat();

        displayRunnable = new Runnable() {
            @Override
            public void run() {
                updateDisplay();
                handler.postDelayed(this, 100);
            }
        };

        handler.post(displayRunnable);
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent == null) {
            createFloatingViews();
            return START_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_START.equals(action)) {

            createFloatingViews();
            startLoop(intent);

        } else if (ACTION_STOP.equals(action)) {

            stopLoop();

        } else if (ACTION_SHUTDOWN.equals(action)) {

            stopLoop();
            removeFloatingViews();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }

            stopSelf();

        } else if (SHOW_PANEL.equals(action)) {

            createFloatingViews();
            showFloatingViews();

        } else {

            createFloatingViews();
        }

        return START_STICKY;
    }

    // ---------------------------------------------------------
    // Notification
    // ---------------------------------------------------------

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

    private void startForegroundCompat() {

        Intent openIntent =
                new Intent(this, MainActivity.class);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        100,
                        openIntent,
                        pendingFlags
                );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("Precision Loop Auto Clicker")
                .setContentText("سرویس آماده است")
                .setSmallIcon(android.R.drawable.ic_media_play)
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

            startForeground(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    // ---------------------------------------------------------
    // Floating views
    // ---------------------------------------------------------

    private void createFloatingViews() {

        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (windowManager == null) {
            windowManager = (WindowManager)
                    getSystemService(WINDOW_SERVICE);
        }

        if (panel == null) {
            createPanel();
        }

        if (markerView == null) {
            createMarker();
        }

        showFloatingViews();
        updateTarget();
    }

    private int getOverlayType() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }

        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createPanel() {

        panel = new LinearLayout(this);

        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
        );

        panel.setBackground(
                makeBackground(
                        Color.rgb(20, 30, 42),
                        dp(14)
                )
        );

        titleView = new TextView(this);

        titleView.setText("Precision Loop | جابه‌جایی پنل");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);

        titleView.setPadding(
                dp(4),
                dp(5),
                dp(4),
                dp(10)
        );

        statusView = new TextView(this);

        statusView.setText("آماده");
        statusView.setTextColor(Color.rgb(120, 220, 160));
        statusView.setTextSize(12);

        statusView.setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4)
        );

        counterView = new TextView(this);

        counterView.setText("چرخه: 0 | کلیک: 0");
        counterView.setTextColor(Color.WHITE);
        counterView.setTextSize(12);

        counterView.setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(8)
        );

        Button stopButton = new Button(this);

        stopButton.setText("توقف کلیک‌ها");
        stopButton.setOnClickListener(v -> stopLoop());

        Button closeButton = new Button(this);

        closeButton.setText("بستن پنل و نشانگر");

        closeButton.setOnClickListener(
                v -> removeFloatingViews()
        );

        panel.addView(titleView);
        panel.addView(statusView);
        panel.addView(counterView);
        panel.addView(stopButton);
        panel.addView(closeButton);

        titleView.setOnTouchListener((v, event) -> {

            if (panelParams == null) {
                return false;
            }

            switch (event.getAction()) {

                case MotionEvent.ACTION_DOWN:

                    panelTouchRawX = event.getRawX();
                    panelTouchRawY = event.getRawY();

                    panelStartX = panelParams.x;
                    panelStartY = panelParams.y;

                    return true;

                case MotionEvent.ACTION_MOVE:

                    panelParams.x = panelStartX
                            + (int) (
                            event.getRawX() - panelTouchRawX
                    );

                    panelParams.y = panelStartY
                            + (int) (
                            event.getRawY() - panelTouchRawY
                    );

                    updateViewLayout(panel, panelParams);

                    return true;

                case MotionEvent.ACTION_UP:
                    return true;
            }

            return false;
        });

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        panelParams.gravity =
                Gravity.TOP | Gravity.START;

        panelParams.x = dp(12);
        panelParams.y = dp(120);

        try {

            windowManager.addView(panel, panelParams);

        } catch (Exception e) {

            panel = null;
            panelParams = null;
        }
    }

    private void createMarker() {

        markerView = new TextView(this);

        markerView.setText("⊕");
        markerView.setTextSize(42);
        markerView.setGravity(Gravity.CENTER);
        markerView.setTextColor(Color.WHITE);

        markerView.setBackground(
                makeBackground(
                        Color.argb(210, 220, 35, 35),
                        markerSize
                )
        );

        markerView.setOnTouchListener((v, event) -> {

            if (markerParams == null) {
                return false;
            }

            switch (event.getAction()) {

                case MotionEvent.ACTION_DOWN:

                    markerTouchRawX = event.getRawX();
                    markerTouchRawY = event.getRawY();

                    markerStartX = markerParams.x;
                    markerStartY = markerParams.y;

                    return true;

                case MotionEvent.ACTION_MOVE:

                    markerParams.x = markerStartX
                            + (int) (
                            event.getRawX() - markerTouchRawX
                    );

                    markerParams.y = markerStartY
                            + (int) (
                            event.getRawY() - markerTouchRawY
                    );

                    updateViewLayout(markerView, markerParams);
                    updateTarget();

                    return true;

                case MotionEvent.ACTION_UP:

                    updateTarget();

                    return true;
            }

            return false;
        });

        markerParams = new WindowManager.LayoutParams(
                markerSize,
                markerSize,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        markerParams.gravity =
                Gravity.TOP | Gravity.START;

        markerParams.x = dp(120);
        markerParams.y = dp(350);

        try {

            windowManager.addView(markerView, markerParams);

        } catch (Exception e) {

            markerView = null;
            markerParams = null;
        }
    }

    private GradientDrawable makeBackground(
            int color,
            int radius
    ) {

        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(radius);

        drawable.setStroke(
                dp(1),
                Color.argb(100, 255, 255, 255)
        );

        return drawable;
    }

    private void updateViewLayout(
            View view,
            WindowManager.LayoutParams params
    ) {

        try {

            if (view != null && view.getParent() != null) {
                windowManager.updateViewLayout(view, params);
            }

        } catch (Exception ignored) {
        }
    }

    private void updateTarget() {

        if (markerParams == null) {
            return;
        }

        targetX = markerParams.x + markerSize / 2;
        targetY = markerParams.y + markerSize / 2;

        AutoClickService service =
                AutoClickService.getInstance();

        if (service != null) {
            service.setTarget(targetX, targetY);
        }
    }

    private void showFloatingViews() {

        try {

            if (panel != null) {
                panel.setVisibility(View.VISIBLE);
            }

            if (markerView != null) {
                markerView.setVisibility(View.VISIBLE);
            }

        } catch (Exception ignored) {
        }
    }

    private void removeFloatingViews() {

        try {

            if (panel != null && panel.getParent() != null) {
                windowManager.removeView(panel);
            }

        } catch (Exception ignored) {
        }

        try {

            if (markerView != null
                    && markerView.getParent() != null) {

                windowManager.removeView(markerView);
            }

        } catch (Exception ignored) {
        }

        panel = null;
        markerView = null;

        panelParams = null;
        markerParams = null;
    }

    public static void setOverlaysHidden(boolean hidden) {

        FloatingService service = instance;

        if (service == null) {
            return;
        }

        service.handler.post(() -> {

            if (service.panel != null) {

                service.panel.setVisibility(
                        hidden ? View.INVISIBLE : View.VISIBLE
                );
            }

            if (service.markerView != null) {

                service.markerView.setVisibility(
                        hidden ? View.INVISIBLE : View.VISIBLE
                );
            }
        });
    }

    // ---------------------------------------------------------
    // Loop scheduling
    // ---------------------------------------------------------

    private void startLoop(Intent config) {

        stopLoop();

        if (config == null) {
            updateStatus("تنظیمات شروع دریافت نشد");
            return;
        }

        cycleDurationMs =
                config.getLongExtra("cycle_duration_ms", 1000);

        pauseDurationMs =
                config.getLongExtra("pause_duration_ms", 0);

        clickIntervalMs =
                config.getLongExtra("click_interval_ms", 50);

        clickCount =
                config.getLongExtra("click_count", 1);

        if (cycleDurationMs <= 0
                || pauseDurationMs < 0
                || clickIntervalMs < 0
                || clickCount <= 0) {

            updateStatus("مقادیر تنظیمات معتبر نیستند");
            return;
        }

        long delayMs;

        if (config.hasExtra("start_delay_ms")) {

            delayMs = config.getLongExtra(
                    "start_delay_ms",
                    -1
            );

        } else {

            long startTime = config.getLongExtra(
                    "start_time_epoch_ms",
                    System.currentTimeMillis()
            );

            delayMs = startTime - System.currentTimeMillis();
        }

        if (delayMs < 0) {
            updateStatus("زمان شروع گذشته است");
            return;
        }

        long nowElapsed = SystemClock.elapsedRealtime();

        if (delayMs > Long.MAX_VALUE - nowElapsed) {
            updateStatus("زمان شروع بیش از حد بزرگ است");
            return;
        }

        anchorElapsedMs = nowElapsed + delayMs;

        accessibility = AutoClickService.getInstance();

        running = true;
        cycleActive = false;

        cycleIndex = 0;
        clickNumber = 0;

        showFloatingViews();
        updateTarget();

        runGeneration++;

        long generation = runGeneration;

        cycleStartRunnable = () -> {

            if (isCurrentRun(generation)) {
                startCycle(generation);
            }
        };

        postAtElapsedTime(
                cycleStartRunnable,
                anchorElapsedMs
        );

        updateStatus(
                delayMs == 0
                        ? "در حال شروع"
                        : "در انتظار زمان شروع"
        );
    }

    private boolean isCurrentRun(long generation) {

        return running && generation == runGeneration;
    }

    private void postAtElapsedTime(
            Runnable runnable,
            long targetElapsedMs
    ) {

        long delay = Math.max(
                0,
                targetElapsedMs - SystemClock.elapsedRealtime()
        );

        handler.postDelayed(runnable, delay);
    }

    private long getCyclePeriodMs() {

        if (pauseDurationMs > Long.MAX_VALUE - cycleDurationMs) {
            return Long.MAX_VALUE;
        }

        return cycleDurationMs + pauseDurationMs;
    }

    private long getCycleTargetElapsed(long index) {

        long period = getCyclePeriodMs();

        if (period == Long.MAX_VALUE
                || (index > 0
                && period > (Long.MAX_VALUE - anchorElapsedMs) / index)) {

            return Long.MAX_VALUE;
        }

        return anchorElapsedMs + index * period;
    }

    // ---------------------------------------------------------
    // Accessibility connection
    // ---------------------------------------------------------

    private AutoClickService getReadyAccessibility() {

        AutoClickService service =
                AutoClickService.getInstance();

        if (service == null || !service.isServiceReady()) {
            return null;
        }

        return service;
    }

    private void waitForAccessibility(long generation) {

        if (!isCurrentRun(generation)) {
            return;
        }

        accessibility = getReadyAccessibility();

        if (accessibility != null) {

            accessibility.setTarget(targetX, targetY);

            updateStatus("سرویس کلیک متصل شد");

            if (!cycleActive) {
                startCycle(generation);
            } else {
                scheduleNextClick(generation);
            }

            return;
        }

        updateStatus("منتظر فعال شدن سرویس دسترسی‌پذیری...");

        cycleStartRunnable = () -> {

            if (isCurrentRun(generation)) {
                waitForAccessibility(generation);
            }
        };

        handler.postDelayed(
                cycleStartRunnable,
                SERVICE_RETRY_MS
        );
    }

    // ---------------------------------------------------------
    // Cycle
    // ---------------------------------------------------------

    private void startCycle(long generation) {

        if (!isCurrentRun(generation)) {
            return;
        }

        accessibility = getReadyAccessibility();

        if (accessibility == null) {
            waitForAccessibility(generation);
            return;
        }

        if (accessibility.isGestureInProgress()) {

            cycleStartRunnable = () -> {

                if (isCurrentRun(generation)) {
                    startCycle(generation);
                }
            };

            handler.postDelayed(
                    cycleStartRunnable,
                    GESTURE_RETRY_MS
            );

            return;
        }

        cycleActive = true;

        clickNumber = 0;

        cycleStartElapsedMs =
                SystemClock.elapsedRealtime();

        cycleDeadlineElapsedMs =
                cycleStartElapsedMs + cycleDurationMs;

        cycleStartWallMs =
                System.currentTimeMillis();

        accessibility.resetClickCounters();

        successAtStart =
                accessibility.getSuccessfulClicks();

        failedAtStart =
                accessibility.getFailedClicks();

        accessibility.setTarget(targetX, targetY);

        scheduleNextClick(generation);

        updateDisplay();
    }

    private void scheduleNextClick(long generation) {

        if (!isCurrentRun(generation) || !cycleActive) {
            return;
        }

        if (clickNumber >= clickCount) {
            waitForCycleEnd(generation);
            return;
        }

        long targetElapsed;

        if (clickNumber == 0) {

            targetElapsed = SystemClock.elapsedRealtime();

        } else {

            if (lastClickDispatchElapsedMs
                    > Long.MAX_VALUE - clickIntervalMs) {

                waitForCycleEnd(generation);
                return;
            }

            targetElapsed =
                    lastClickDispatchElapsedMs + clickIntervalMs;
        }

        if (targetElapsed >= cycleDeadlineElapsedMs) {
            waitForCycleEnd(generation);
            return;
        }

        nextClickRunnable = () -> {

            if (isCurrentRun(generation) && cycleActive) {
                dispatchNextClick(generation);
            }
        };

        postAtElapsedTime(
                nextClickRunnable,
                targetElapsed
        );
    }

    private void dispatchNextClick(long generation) {

        if (!isCurrentRun(generation) || !cycleActive) {
            return;
        }

        accessibility = getReadyAccessibility();

        if (accessibility == null) {

            updateStatus("اتصال سرویس کلیک قطع شده؛ تلاش مجدد");

            nextClickRunnable = () -> {

                if (isCurrentRun(generation) && cycleActive) {
                    dispatchNextClick(generation);
                }
            };

            handler.postDelayed(
                    nextClickRunnable,
                    SERVICE_RETRY_MS
            );

            return;
        }

        if (accessibility.isGestureInProgress()) {

            nextClickRunnable = () -> {

                if (isCurrentRun(generation) && cycleActive) {
                    dispatchNextClick(generation);
                }
            };

            handler.postDelayed(
                    nextClickRunnable,
                    GESTURE_RETRY_MS
            );

            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now >= cycleDeadlineElapsedMs) {
            waitForCycleEnd(generation);
            return;
        }

        accessibility.setTarget(targetX, targetY);

        final AutoClickService clickService = accessibility;

        clickNumber++;

        lastClickDispatchElapsedMs = now;

        final long callbackGeneration = generation;

        clickService.performClick(() -> {

            handler.post(() -> {

                if (!isCurrentRun(callbackGeneration)
                        || !cycleActive) {
                    return;
                }

                if (SystemClock.elapsedRealtime()
                        >= cycleDeadlineElapsedMs) {

                    waitForCycleEnd(callbackGeneration);

                } else {

                    scheduleNextClick(callbackGeneration);
                }

                updateDisplay();
            });
        });

        updateDisplay();
    }

    private void waitForCycleEnd(long generation) {

        if (!isCurrentRun(generation)) {
            return;
        }

        if (nextClickRunnable != null) {

            handler.removeCallbacks(nextClickRunnable);
            nextClickRunnable = null;
        }

        cycleFinishRunnable = () -> {

            if (isCurrentRun(generation)) {
                finishCycle(generation);
            }
        };

        postAtElapsedTime(
                cycleFinishRunnable,
                cycleDeadlineElapsedMs
        );
    }

    private void finishCycle(long generation) {

        if (!isCurrentRun(generation)) {
            return;
        }

        accessibility = getReadyAccessibility();

        if (accessibility != null
                && accessibility.isGestureInProgress()) {

            cycleFinishRunnable = () -> {

                if (isCurrentRun(generation)) {
                    finishCycle(generation);
                }
            };

            handler.postDelayed(
                    cycleFinishRunnable,
                    GESTURE_RETRY_MS
            );

            return;
        }

        cycleActive = false;

        if (accessibility != null) {

            long successes = Math.max(
                    0L,
                    accessibility.getSuccessfulClicks()
                            - successAtStart
            );

            long failures = Math.max(
                    0L,
                    accessibility.getFailedClicks()
                            - failedAtStart
            );

            long averageLatency =
                    accessibility.getAverageGestureLatencyNanos();

            long maxLatency =
                    accessibility.getMaxGestureLatencyNanos();

            long measured =
                    accessibility.getMeasuredGestures();

            long actualDuration = Math.max(
                    0,
                    SystemClock.elapsedRealtime()
                            - cycleStartElapsedMs
            );

            CycleReportStore.addReport(
                    this,
                    cycleIndex + 1,
                    cycleStartWallMs,
                    actualDuration,
                    successes,
                    failures,
                    averageLatency,
                    maxLatency,
                    measured
            );
        }

        cycleIndex++;

        long nextTarget =
                getCycleTargetElapsed(cycleIndex);

        if (nextTarget == Long.MAX_VALUE) {

            stopLoop();
            updateStatus("محدودیت زمان‌بندی چرخه");

            return;
        }

        cycleStartRunnable = () -> {

            if (isCurrentRun(generation)) {
                startCycle(generation);
            }
        };

        postAtElapsedTime(
                cycleStartRunnable,
                nextTarget
        );

        updateDisplay();
    }

    // ---------------------------------------------------------
    // Stop and status
    // ---------------------------------------------------------

    private void stopLoop() {

        running = false;
        cycleActive = false;

        runGeneration++;

        removeScheduledCallbacks();

        updateStatus("متوقف شد");
        updateDisplay();
    }

    private void removeScheduledCallbacks() {

        if (cycleStartRunnable != null) {

            handler.removeCallbacks(cycleStartRunnable);
            cycleStartRunnable = null;
        }

        if (nextClickRunnable != null) {

            handler.removeCallbacks(nextClickRunnable);
            nextClickRunnable = null;
        }

        if (cycleFinishRunnable != null) {

            handler.removeCallbacks(cycleFinishRunnable);
            cycleFinishRunnable = null;
        }
    }

    private void updateStatus(String message) {

        if (statusView != null) {
            statusView.setText(message);
        }
    }

    private void updateDisplay() {

        if (statusView == null || counterView == null) {
            return;
        }

        AutoClickService service = getReadyAccessibility();

        long successes = service != null
                ? service.getSuccessfulClicks()
                : 0;

        long failures = service != null
                ? service.getFailedClicks()
                : 0;

        if (!running) {

            counterView.setText(
                    "چرخه: " + cycleIndex
                            + " | تلاش: " + clickNumber
                            + " | موفق: " + successes
                            + " | ناموفق: " + failures
            );

            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (!cycleActive) {

            long remaining = Math.max(
                    0,
                    anchorElapsedMs - now
            );

            statusView.setText(String.format(
                    Locale.US,
                    "در انتظار شروع: %.3f ثانیه",
                    remaining / 1000.0
            ));

        } else {

            long remaining = Math.max(
                    0,
                    cycleDeadlineElapsedMs - now
            );

            statusView.setText(String.format(
                    Locale.US,
                    "چرخه فعال | باقی‌مانده: %.3f ثانیه",
                    remaining / 1000.0
            ));
        }

        counterView.setText(
                "چرخه: " + (cycleIndex + 1)
                        + " | تلاش: " + clickNumber
                        + "/" + clickCount
                        + " | موفق: " + successes
                        + " | ناموفق: " + failures
        );
    }

    private int dp(float value) {

        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    @Override
    public void onDestroy() {

        stopLoop();

        if (displayRunnable != null) {
            handler.removeCallbacks(displayRunnable);
        }

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
