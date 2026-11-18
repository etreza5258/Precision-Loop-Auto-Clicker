

package com.precision.loopclicker;

import android.accessibilityservice.GestureDescription;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class FloatingService extends Service {

    public static final String ACTION_START = "com.precision.loopclicker.ACTION_START";
    public static final String ACTION_STOP = "com.precision.loopclicker.ACTION_STOP";
    public static final String ACTION_SHUTDOWN = "com.precision.loopclicker.ACTION_SHUTDOWN";
    public static final String SHOW_PANEL = "com.precision.loopclicker.SHOW_PANEL";
    public static final String ACTION_START_FROM_FLOATING =
            "com.precision.loopclicker.ACTION_START_FROM_FLOATING";

    private static final String CHANNEL_ID = "precision_loop_channel";
    private static final String PREF_NAME = "precision_loop_settings";
    private static final String PREF_CLOCK_SOURCE = "clock_source";
    private static final String PREF_INTERNET_EPOCH = "internet_epoch_at_sync_ms";
    private static final String PREF_INTERNET_ELAPSED = "internet_elapsed_at_sync_ms";
    private static final String PREF_MARKER_X = "marker_x";
    private static final String PREF_MARKER_Y = "marker_y";
    private static final String CLOCK_PHONE = "phone";
    private static final String CLOCK_INTERNET = "internet";
    private static final String ACTION_CLOCK_SOURCE_CHANGED =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";

    private static final int NOTIFICATION_ID = 4101;
    private static final int PANEL_BACKGROUND = Color.rgb(20, 30, 42);
    private static final int TITLE_COLOR = Color.rgb(255, 152, 0);
    private static final int DISPATCH_BATCH_SIZE = 1024;

    private static final long COUNTDOWN_UPDATE_MS = 200L;
    private static final long NANOS_PER_MS = 1_000_000L;

    private static volatile FloatingService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] markerLocation = new int[2];
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private WindowManager windowManager;
    private LinearLayout panel;
    private TextView selectedTimeView, statusView, countdownView, markerView;
    private WindowManager.LayoutParams panelParams, markerParams;

    private int markerSize, targetX, targetY;
    private int floatingStartHour, floatingStartMinute;
    private int floatingStartSecond, floatingStartMillis;

    private String clockSource = CLOCK_PHONE;

    private long internetEpochAtSyncMs, internetElapsedAtSyncMs;
    private long runStartElapsedMs, runEndElapsedMs, totalClicks;
    private long cycleDurationMs, cycleStrideMs, clickIntervalMs, touchDurationMs;

    // وضعیت چرخه‌ای که در حال تحویل درخواست‌های آن هستیم
    private long scheduledCycleStartMs, scheduledCycleEndMs, nextClickTargetMs;

    private long completedClicks, expiredClicks, failedClicks;

    private int runGeneration, cycleIndex, clickIndex;
    private int clicksPerCycle, cycleCount;

    private boolean internetTimeAnchorAvailable;
    private boolean clockSourceReceiverRegistered;
    private boolean floatingTimeConfigured;
    private boolean clicking, runDisplayActive, runFinished;

    private float panelTouchRawX, panelTouchRawY;
    private float markerTouchRawX, markerTouchRawY;
    private int panelStartX, panelStartY, markerStartX, markerStartY;

    private Runnable displayRunnable, countdownRunnable, nextClickRunnable;
    private AutoClickService targetConfiguredService;

    public static FloatingService getInstance() {
        return instance;
    }

    // دریافت تغییر ساعت مرجع

    private final BroadcastReceiver clockSourceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null ||
                    !ACTION_CLOCK_SOURCE_CHANGED.equals(intent.getAction())) return;

            String source = intent.getStringExtra("clock_source");
            if (!CLOCK_PHONE.equals(source) && !CLOCK_INTERNET.equals(source)) return;

            if (intent.hasExtra(PREF_INTERNET_EPOCH) &&
                    intent.hasExtra(PREF_INTERNET_ELAPSED)) {
                long epoch = intent.getLongExtra(PREF_INTERNET_EPOCH, 0L);
                long elapsed = intent.getLongExtra(PREF_INTERNET_ELAPSED, 0L);

                if (epoch > 0 && elapsed > 0 &&
                        elapsed <= SystemClock.elapsedRealtime()) {
                    internetEpochAtSyncMs = epoch;
                    internetElapsedAtSyncMs = elapsed;
                    internetTimeAnchorAvailable = true;
                    saveInternetAnchor(epoch, elapsed);
                }
            }

            clockSource = source;
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_CLOCK_SOURCE, source).apply();
            updateClockDisplay();
        }
    };

    // چرخه عمر

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        markerSize = dp(48);
        clockFormat.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));

        loadSavedClockSource();
        registerClockSourceReceiver();
        createNotificationChannel();
        startForegroundCompat();

        displayRunnable = new Runnable() {
            @Override
            public void run() {
                updateClockDisplay();
                handler.postDelayed(this, 1000L);
            }
        };

        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                updateCountdownDisplay();
                handler.postDelayed(this, clicking ? COUNTDOWN_UPDATE_MS : 250L);
            }
        };

        handler.post(displayRunnable);
        handler.post(countdownRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            createFloatingViews();
            return START_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_SHUTDOWN.equals(action)) {
            shutdownService();
        } else if (ACTION_STOP.equals(action)) {
            stopClicking();
        } else if (ACTION_START.equals(action)) {
            createFloatingViews();
            startClicking(intent);
        } else if (SHOW_PANEL.equals(action)) {
            createFloatingViews();
            showFloatingViews();
        } else {
            createFloatingViews();
        }

        return START_STICKY;
    }

    // شروع اجرا و اعتبارسنجی ورودی‌ها

    private void startClicking(Intent intent) {
        stopClicking();

        if (intent == null) {
            updateStatus("تنظیمات شروع دریافت نشد");
            return;
        }

        final int generation = ++runGeneration;
        long now = SystemClock.elapsedRealtime();
        long requestedStart = intent.getLongExtra("start_elapsed_realtime_ms", 0L);

        if (requestedStart <= 0) {
            long delay = intent.getLongExtra("start_delay_ms", 0L);
            if (delay < 0 || delay > Long.MAX_VALUE - now) {
                updateStatus("زمان شروع نامعتبر است");
                return;
            }
            requestedStart = now + delay;
        }

        long clicks = intent.getLongExtra(
                "clicks_per_cycle", intent.getLongExtra("click_count", 1L));
        long cycles = intent.getLongExtra("cycle_count", 1L);

        if (clicks < 1 || clicks > Integer.MAX_VALUE ||
                cycles < 1 || cycles > Integer.MAX_VALUE) {
            updateStatus("تعداد کلیک یا چرخه نامعتبر است");
            return;
        }

        int requestedClicks = (int) clicks;
        int requestedCycles = (int) cycles;

        long duration = intent.getLongExtra("cycle_duration_ms", 0L);
        long pause = intent.getLongExtra("cycle_pause_ms", 0L);
        long interval = intent.getLongExtra("click_interval_ms", 1L);
        long touch = intent.getLongExtra("touch_duration_ms", 1L);

        if (pause < 0 || interval < 0 || touch < 1 ||
                touch > GestureDescription.getMaxGestureDuration()) {
            updateStatus("تنظیمات اجرای کلیک نامعتبر است");
            return;
        }

        long lastOffset, stride, calculatedEnd, calculatedTotal;

        try {
            lastOffset = Math.multiplyExact(requestedClicks - 1L, interval);

            if (duration <= 0) {
                duration = Math.addExact(lastOffset, touch);
            }

            if (lastOffset >= duration) {
                updateStatus("تعداد کلیک‌ها با مدت چرخه سازگار نیست");
                return;
            }

            stride = Math.addExact(duration, pause);

            long totalDuration = Math.addExact(
                    Math.multiplyExact((long) requestedCycles, duration),
                    Math.multiplyExact((long) requestedCycles - 1L, pause));

            calculatedEnd = Math.addExact(requestedStart, totalDuration);
            calculatedTotal = Math.multiplyExact(
                    (long) requestedClicks, requestedCycles);

        } catch (ArithmeticException e) {
            updateStatus("مدت یا تعداد اجرا بیش از حد بزرگ است");
            return;
        }

        clicksPerCycle = requestedClicks;
        cycleCount = requestedCycles;
        cycleDurationMs = duration;
        cycleStrideMs = stride;
        clickIntervalMs = interval;
        touchDurationMs = touch;

        runStartElapsedMs = requestedStart;
        runEndElapsedMs = calculatedEnd;
        totalClicks = calculatedTotal;

        cycleIndex = clickIndex = 0;
        scheduledCycleStartMs = requestedStart;
        scheduledCycleEndMs = requestedStart + duration;
        nextClickTargetMs = requestedStart;

        completedClicks = expiredClicks = failedClicks = 0L;
        clicking = runDisplayActive = true;
        runFinished = false;

        updateTarget();
        setMarkerTouchable(false);
        showFloatingViews();
        updateStatus("زمان‌بندی کلیک‌ها آغاز شد");
        updateCountdownDisplay();

        scheduleNextClick(generation);
    }

    // تحویل دسته‌ای درخواست‌ها

    private void scheduleNextClick(int generation) {
        if (!clicking || generation != runGeneration) return;

        int dispatched = 0;

        while (dispatched < DISPATCH_BATCH_SIZE) {
            if (cycleIndex >= cycleCount) {
                scheduleRunFinish(generation);
                return;
            }

            if (clickIndex >= clicksPerCycle) {
                cycleIndex++;
                clickIndex = 0;

                if (cycleIndex >= cycleCount) {
                    scheduleRunFinish(generation);
                    return;
                }

                try {
                    scheduledCycleStartMs = Math.addExact(
                            scheduledCycleStartMs, cycleStrideMs);
                    scheduledCycleEndMs = Math.addExact(
                            scheduledCycleStartMs, cycleDurationMs);
                    nextClickTargetMs = scheduledCycleStartMs;
                } catch (ArithmeticException e) {
                    updateStatus("خطای محاسبات زمان‌بندی");
                    stopClicking();
                    return;
                }
            }

            long target = nextClickTargetMs;

            if (clickIndex + 1 < clicksPerCycle) {
                try {
                    nextClickTargetMs = Math.addExact(target, clickIntervalMs);
                } catch (ArithmeticException e) {
                    updateStatus("خطای محاسبات زمان‌بندی");
                    stopClicking();
                    return;
                }
            }

            clickIndex++;
            dispatched++;

            dispatchClick(
                    generation,
                    scheduledCycleStartMs,
                    target,
                    scheduledCycleEndMs
            );
        }

        if (clicking && generation == runGeneration) {
            nextClickRunnable = () -> scheduleNextClick(generation);
            handler.post(nextClickRunnable);
        }
    }

    private void dispatchClick(
            int generation, long cycleStart, long target, long cycleEnd) {
        if (!clicking || generation != runGeneration) return;

        try {
            long targetNanos = Math.multiplyExact(target, NANOS_PER_MS);
            long startNanos = Math.multiplyExact(cycleStart, NANOS_PER_MS);
            long endNanos = Math.multiplyExact(cycleEnd, NANOS_PER_MS);

            if (SystemClock.elapsedRealtimeNanos() >= endNanos) {
                recordClickResult(generation, false, true);
                return;
            }

            AutoClickService service = AutoClickService.getInstance();

            if (service == null || !service.isServiceReady()) {
                recordClickResult(generation, false, false);
                return;
            }

            ensureServiceTarget(service);

            boolean accepted = service.performClickAtTarget(
                    targetNanos,
                    startNanos,
                    endNanos,
                    touchDurationMs,
                    (success, expired) -> {
                        if (generation == runGeneration) {
                            recordClickResult(generation, success, expired);
                        }
                    });

            if (!accepted) recordClickResult(generation, false, false);

        } catch (Exception e) {
            recordClickResult(generation, false, false);
        }
    }

    private void ensureServiceTarget(AutoClickService service) {
        if (targetConfiguredService != service) {
            service.setTarget(targetX, targetY);
            targetConfiguredService = service;
        }
    }

    // ثبت نتیجه

    private void recordClickResult(int generation, boolean success, boolean expired) {
        if (generation != runGeneration) return;

        if (success) completedClicks++;
        else if (expired) expiredClicks++;
        else failedClicks++;

        updateRunResultState();
    }

    private void scheduleRunFinish(int generation) {
        if (generation != runGeneration) return;

        long remaining = runEndElapsedMs - SystemClock.elapsedRealtime();

        if (remaining > 0) {
            handler.postDelayed(() -> scheduleRunFinish(generation), remaining);
        } else {
            finishClicking();
        }
    }

    private void finishClicking() {
        if (!clicking) return;

        clicking = false;
        updateStatus("زمان‌بندی چرخه‌ها به پایان رسید؛ در انتظار نتایج");
        updateRunResultState();
        updateCountdownDisplay();
    }

    private void updateRunResultState() {
        long results = completedClicks + expiredClicks + failedClicks;

        if (!clicking && totalClicks > 0 && results >= totalClicks) {
            runDisplayActive = false;
            runFinished = true;
            setMarkerTouchable(true);
            updateStatus("اجرای تمام درخواست‌ها به پایان رسید");
            updateCountdownDisplay();
        }
    }

    private void stopClicking() {
        clicking = runDisplayActive = runFinished = false;
        runGeneration++;

        if (nextClickRunnable != null) {
            handler.removeCallbacks(nextClickRunnable);
            nextClickRunnable = null;
        }

        AutoClickService service = AutoClickService.getInstance();
        if (service != null) service.cancelPendingScheduledClick();

        targetConfiguredService = null;
        setMarkerTouchable(true);
        updateStatus("اجرای کلیک‌ها متوقف شد");
        updateCountdownDisplay();
    }

    // ساعت مرجع

    private void loadSavedClockSource() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

            clockSource = CLOCK_INTERNET.equals(
                    prefs.getString(PREF_CLOCK_SOURCE, CLOCK_PHONE))
                    ? CLOCK_INTERNET : CLOCK_PHONE;

            internetEpochAtSyncMs = prefs.getLong(PREF_INTERNET_EPOCH, 0L);
            internetElapsedAtSyncMs = prefs.getLong(PREF_INTERNET_ELAPSED, 0L);

            long now = SystemClock.elapsedRealtime();
            internetTimeAnchorAvailable = internetEpochAtSyncMs > 0 &&
                    internetElapsedAtSyncMs > 0 && internetElapsedAtSyncMs <= now;

        } catch (Exception ignored) {
            clockSource = CLOCK_PHONE;
            internetEpochAtSyncMs = internetElapsedAtSyncMs = 0L;
            internetTimeAnchorAvailable = false;
        }
    }

    private void saveInternetAnchor(long epoch, long elapsed) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putLong(PREF_INTERNET_EPOCH, epoch)
                .putLong(PREF_INTERNET_ELAPSED, elapsed).apply();
    }

    private void registerClockSourceReceiver() {
        if (clockSourceReceiverRegistered) return;

        try {
            IntentFilter filter = new IntentFilter(ACTION_CLOCK_SOURCE_CHANGED);

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(clockSourceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(clockSourceReceiver, filter);
            }

            clockSourceReceiverRegistered = true;
        } catch (Exception ignored) {
            clockSourceReceiverRegistered = false;
        }
    }

    private void unregisterClockSourceReceiver() {
        if (!clockSourceReceiverRegistered) return;

        try {
            unregisterReceiver(clockSourceReceiver);
        } catch (Exception ignored) {
        }

        clockSourceReceiverRegistered = false;
    }

    private long getCurrentClockEpochMs() {
        if (!CLOCK_INTERNET.equals(clockSource)) return System.currentTimeMillis();
        if (!internetTimeAnchorAvailable) return 0L;

        long difference = SystemClock.elapsedRealtime() - internetElapsedAtSyncMs;

        if (difference < 0 || internetEpochAtSyncMs <= 0 ||
                internetEpochAtSyncMs > Long.MAX_VALUE - difference) return 0L;

        return internetEpochAtSyncMs + difference;
    }

    // اعلان و Foreground

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Precision Loop Auto Clicker",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("سرویس تنظیم زمان");

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pending = PendingIntent.getActivity(this, 100, open, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Precision Loop Auto Clicker")
                .setContentText("سرویس تنظیم زمان فعال است")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // پنل شناور

    private void createFloatingViews() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        if (panel == null) createPanel();
        if (markerView == null) createMarker();

        showFloatingViews();
        updateTarget();
        updateClockDisplay();
        updateCountdownDisplay();
        setMarkerTouchable(!clicking);
    }

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(7), dp(8), dp(7));
        panel.setBackground(makeBackground(PANEL_BACKGROUND, dp(11)));
        panel.setClickable(true);

        createTimeSection();

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(5), 0, 0);

        TextView settings = createPanelButton("تنظیمات", Color.rgb(20, 125, 75));
        TextView time = createTimePickerButton();
        TextView shutdown = createPanelButton("خاموش", Color.rgb(75, 75, 85));

        settings.setOnClickListener(v -> openMainForStart());
        time.setOnClickListener(v -> showStartTimePicker());
        shutdown.setOnClickListener(v -> shutdownService());

        row.addView(settings);
        row.addView(time);
        row.addView(shutdown);
        panel.addView(row);

        panel.setOnTouchListener((v, event) -> {
            if (panelParams == null) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    panelTouchRawX = event.getRawX();
                    panelTouchRawY = event.getRawY();
                    panelStartX = panelParams.x;
                    panelStartY = panelParams.y;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    panelParams.x = panelStartX + (int) (event.getRawX() - panelTouchRawX);
                    panelParams.y = panelStartY + (int) (event.getRawY() - panelTouchRawY);
                    updateViewLayout(panel, panelParams);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        });

        panelParams = new WindowManager.LayoutParams(
                -2, -2, getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        panelParams.gravity = Gravity.TOP | Gravity.START;
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
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        markerView = new TextView(this);
        markerView.setText("⊕");
        markerView.setTextSize(39);
        markerView.setGravity(Gravity.CENTER);
        markerView.setTextColor(Color.WHITE);
        markerView.setBackground(makeBackground(Color.argb(220, 220, 35, 35), dp(24)));

        markerView.setOnTouchListener((view, event) -> {
            if (markerParams == null || clicking) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    markerTouchRawX = event.getRawX();
                    markerTouchRawY = event.getRawY();
                    markerStartX = markerParams.x;
                    markerStartY = markerParams.y;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    markerParams.x = markerStartX + (int) (event.getRawX() - markerTouchRawX);
                    markerParams.y = markerStartY + (int) (event.getRawY() - markerTouchRawY);
                    updateViewLayout(markerView, markerParams);
                    updateTarget();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    updateTarget();
                    prefs.edit().putInt(PREF_MARKER_X, markerParams.x)
                            .putInt(PREF_MARKER_Y, markerParams.y).apply();
                    return true;
            }
            return false;
        });

        markerParams = new WindowManager.LayoutParams(
                markerSize, markerSize, getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        markerParams.gravity = Gravity.TOP | Gravity.START;
        markerParams.x = prefs.getInt(PREF_MARKER_X, dp(120));
        markerParams.y = prefs.getInt(PREF_MARKER_Y, dp(350));

        try {
            windowManager.addView(markerView, markerParams);
            updateTarget();
        } catch (Exception e) {
            markerView = null;
            markerParams = null;
        }
    }

    private void updateTarget() {
        if (markerView == null || markerParams == null) return;

        markerView.getLocationOnScreen(markerLocation);
        targetX = markerLocation[0] + markerView.getWidth() / 2;
        targetY = markerLocation[1] + markerView.getHeight() / 2;

        AutoClickService service = AutoClickService.getInstance();

        if (service != null && service.isServiceReady()) {
            service.setTarget(targetX, targetY);
            targetConfiguredService = service;
        } else {
            targetConfiguredService = null;
        }
    }

    private void setMarkerTouchable(boolean touchable) {
        if (markerView == null || markerParams == null) return;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (markerParams.flags == flags) return;

        markerParams.flags = flags;
        updateViewLayout(markerView, markerParams);
    }

    private void createTimeSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(7), dp(6), dp(7), dp(6));
        box.setBackground(makeBackground(Color.rgb(13, 25, 36), dp(8)));

        TextView heading = new TextView(this);
        heading.setText("تنظیم زمان");
        heading.setTextColor(TITLE_COLOR);
        heading.setTextSize(13);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);

        selectedTimeView = new TextView(this);
        selectedTimeView.setTextColor(Color.WHITE);
        selectedTimeView.setTextSize(10);
        selectedTimeView.setGravity(Gravity.CENTER);
        selectedTimeView.setPadding(0, dp(5), 0, dp(4));

        countdownView = new TextView(this);
        countdownView.setText("آماده");
        countdownView.setTextColor(Color.rgb(255, 210, 90));
        countdownView.setTextSize(11);
        countdownView.setTypeface(null, Typeface.BOLD);
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setPadding(0, dp(3), 0, dp(3));

        statusView = new TextView(this);
        statusView.setText("آماده");
        statusView.setTextColor(Color.rgb(180, 190, 205));
        statusView.setTextSize(10);
        statusView.setGravity(Gravity.CENTER);

        box.addView(heading);
        box.addView(selectedTimeView);
        box.addView(countdownView);
        box.addView(statusView);
        panel.addView(box);
    }

    private TextView createPanelButton(String text, int color) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(10);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(3), dp(5), dp(3), dp(5));
        button.setBackground(makeBackground(color, dp(7)));
        button.setLayoutParams(buttonParams());
        button.setClickable(true);
        return button;
    }

    private TextView createTimePickerButton() {
        TextView button = new TextView(this);
        button.setText("⚙");
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(makeBackground(Color.rgb(55, 85, 110), dp(7)));
        button.setLayoutParams(buttonParams());
        button.setClickable(true);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    // انتخاب زمان شروع

    private void showStartTimePicker() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (CLOCK_INTERNET.equals(clockSource) && !internetTimeAnchorAvailable) {
            updateStatus("مرجع ساعت اینترنتی هنوز دریافت نشده است");
            return;
        }

        if (!floatingTimeConfigured) {
            long epoch = getCurrentClockEpochMs();

            if (epoch <= 0) {
                updateStatus("زمان مرجع ساعت در دسترس نیست");
                return;
            }

            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"));
            c.setTimeInMillis(epoch);
            floatingStartHour = c.get(Calendar.HOUR_OF_DAY);
            floatingStartMinute = c.get(Calendar.MINUTE);
            floatingStartSecond = c.get(Calendar.SECOND);
            floatingStartMillis = c.get(Calendar.MILLISECOND);
        }

        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_Dialog_Alert);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(10));
        root.setBackground(makeBackground(Color.WHITE, dp(12)));

        TextView title = new TextView(this);
        title.setText("تنظیم زمان شروع");
        title.setTextColor(TITLE_COLOR);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);

        NumberPicker hour = createNumberPicker(0, 23, floatingStartHour);
        NumberPicker minute = createNumberPicker(0, 59, floatingStartMinute);
        NumberPicker second = createNumberPicker(0, 59, floatingStartSecond);
        NumberPicker millis = createNumberPicker(0, 999, floatingStartMillis);
        millis.setFormatter(v -> String.format(Locale.US, "%03d", v));

        addPickerColumn(row, "ساعت", hour);
        addPickerColumn(row, "دقیقه", minute);
        addPickerColumn(row, "ثانیه", second);
        addPickerColumn(row, "میلی‌ثانیه", millis);
        root.addView(row);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(8), 0, 0);

        TextView cancel = createDialogButton("انصراف", Color.rgb(90, 90, 100));
        TextView confirm = createDialogButton("تأیید", Color.rgb(20, 125, 75));
        buttons.addView(cancel);
        buttons.addView(confirm);
        root.addView(buttons);

        dialog.setContentView(root);
        dialog.setCancelable(true);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setType(getOverlayType());
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p = window.getAttributes();
            p.width = dp(340);
            p.height = WindowManager.LayoutParams.WRAP_CONTENT;
            p.gravity = Gravity.CENTER;
            p.dimAmount = 0.35f;
            window.setAttributes(p);
        }

        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            floatingStartHour = hour.getValue();
            floatingStartMinute = minute.getValue();
            floatingStartSecond = second.getValue();
            floatingStartMillis = millis.getValue();
            floatingTimeConfigured = true;
            updateClockDisplay();
            updateStatus("زمان شروع انتخاب شد");
            dialog.dismiss();
        });

        try {
            dialog.show();
            if (window != null) {
                window.setLayout(dp(340), WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            updateStatus("باز کردن انتخاب‌گر زمان ناموفق بود");
        }
    }

    private NumberPicker createNumberPicker(int min, int max, int value) {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        picker.setWrapSelectorWheel(true);
        picker.setDescendantFocusability(NumberPicker.FOCUS_AFTER_DESCENDANTS);
        picker.setBackgroundColor(Color.WHITE);
        picker.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(105)));

        try {
            Field f = NumberPicker.class.getDeclaredField("mInputText");
            f.setAccessible(true);
            EditText input = (EditText) f.get(picker);
            if (input != null) {
                input.setTextColor(Color.BLACK);
                input.setBackgroundColor(Color.TRANSPARENT);
            }
        } catch (Exception ignored) {
        }

        try {
            Field f = NumberPicker.class.getDeclaredField("mSelectorWheelPaint");
            f.setAccessible(true);
            Paint paint = (Paint) f.get(picker);
            if (paint != null) paint.setColor(Color.BLACK);
        } catch (Exception ignored) {
        }

        return picker;
    }

    private void addPickerColumn(LinearLayout parent, String label, NumberPicker picker) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(Color.BLACK);
        title.setTextSize(10);
        title.setGravity(Gravity.CENTER);

        column.addView(title);
        column.addView(picker);
        column.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        parent.addView(column);
    }

    private TextView createDialogButton(String text, int color) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
        button.setBackground(makeBackground(color, dp(7)));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(p);
        return button;
    }

    private String formatFloatingStartTime() {
        return String.format(Locale.US, "%02d:%02d:%02d.%03d",
                floatingStartHour, floatingStartMinute,
                floatingStartSecond, floatingStartMillis);
    }

    // نمایش ساعت و شمارش معکوس

    private void updateClockDisplay() {
        if (selectedTimeView == null) return;

        long epoch = getCurrentClockEpochMs();
        String time = epoch > 0
                ? clockFormat.format(epoch)
                : "در انتظار همگام‌سازی";

        String source = CLOCK_INTERNET.equals(clockSource)
                ? (internetTimeAnchorAvailable
                    ? "ساعت اینترنتی ایران" : "ساعت اینترنتی (بدون مرجع)")
                : "ساعت گوشی";

        String display = source + ": " + time;
        if (floatingTimeConfigured) {
            display += "\nزمان شروع: " + formatFloatingStartTime();
        }
        selectedTimeView.setText(display);
    }

    private void updateCountdownDisplay() {
        if (countdownView == null) return;

        if (runFinished) {
            countdownView.setText(String.format(Locale.US,
                    "پایان اجرا | موفق: %d | منقضی: %d | خطا: %d",
                    completedClicks, expiredClicks, failedClicks));
            return;
        }

        if (!runDisplayActive) {
            countdownView.setText("آماده");
            return;
        }

        long remaining = runStartElapsedMs - SystemClock.elapsedRealtime();

        if (remaining > 0) {
            long hours = remaining / 3_600_000L;
            long minutes = remaining % 3_600_000L / 60_000L;
            long seconds = remaining % 60_000L / 1_000L;
            long millis = remaining % 1_000L;

            countdownView.setText(String.format(Locale.US,
                    "شروع تا: %02d:%02d:%02d.%03d",
                    hours, minutes, seconds, millis));
            return;
        }

        long results = completedClicks + expiredClicks + failedClicks;
        countdownView.setText(String.format(Locale.US,
                "در حال اجرا | نتیجه: %d/%d", results, totalClicks));
    }

    private void updateStatus(String message) {
        if (statusView != null) statusView.setText(message);
    }

    // باز کردن صفحه اصلی

    private void openMainForStart() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(ACTION_START_FROM_FLOATING);
        intent.putExtra("clock_source", clockSource);

        if (floatingTimeConfigured) {
            intent.putExtra("floating_start_time_configured", true);
            intent.putExtra("floating_start_hour", floatingStartHour);
            intent.putExtra("floating_start_minute", floatingStartMinute);
            intent.putExtra("floating_start_second", floatingStartSecond);
            intent.putExtra("floating_start_millis", floatingStartMillis);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        try {
            startActivity(intent);
        } catch (Exception e) {
            updateStatus("باز کردن صفحه اصلی ناموفق بود");
        }
    }

    // نمایش و مخفی‌سازی

    public static void setOverlaysHidden(boolean hidden) {
        FloatingService s = instance;
        if (s == null) return;

        s.handler.post(() -> {
            int visibility = hidden ? View.INVISIBLE : View.VISIBLE;
            if (s.panel != null) s.panel.setVisibility(visibility);
            if (s.markerView != null) s.markerView.setVisibility(visibility);
        });
    }

    private void showFloatingViews() {
        try {
            if (panel != null) panel.setVisibility(View.VISIBLE);
            if (markerView != null) markerView.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
        }
    }

    private void updateViewLayout(View view, WindowManager.LayoutParams params) {
        try {
            if (view != null && view.getParent() != null) {
                windowManager.updateViewLayout(view, params);
            }
        } catch (Exception ignored) {
        }
    }

    private GradientDrawable makeBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), Color.argb(100, 255, 255, 255));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // خاموش‌کردن و پاک‌سازی

    private void shutdownService() {
        stopClicking();
        removeFloatingViews();

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        stopSelf();
    }

    private void removeFloatingViews() {
        try {
            if (panel != null && panel.getParent() != null) {
                windowManager.removeView(panel);
            }
        } catch (Exception ignored) {
        }

        try {
            if (markerView != null && markerView.getParent() != null) {
                windowManager.removeView(markerView);
            }
        } catch (Exception ignored) {
        }

        panel = markerView = null;
        panelParams = markerParams = null;
        selectedTimeView = statusView = countdownView = null;
    }

    @Override
    public void onDestroy() {
        stopClicking();

        if (displayRunnable != null) {
            handler.removeCallbacks(displayRunnable);
            displayRunnable = null;
        }

        if (countdownRunnable != null) {
            handler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }

        unregisterClockSourceReceiver();
        removeFloatingViews();

        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
