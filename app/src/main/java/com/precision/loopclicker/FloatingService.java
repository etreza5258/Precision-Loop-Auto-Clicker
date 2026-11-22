
package com.precision.loopclicker;

import android.accessibilityservice.GestureDescription;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class FloatingService extends Service {

    public static final String ACTION_START =
            "com.precision.loopclicker.ACTION_START";
    public static final String ACTION_STOP =
            "com.precision.loopclicker.ACTION_STOP";
    public static final String ACTION_SHUTDOWN =
            "com.precision.loopclicker.ACTION_SHUTDOWN";
    public static final String SHOW_PANEL =
            "com.precision.loopclicker.SHOW_PANEL";
    public static final String ACTION_START_FROM_FLOATING =
            "com.precision.loopclicker.ACTION_START_FROM_FLOATING";

    private static final String PREF = "precision_loop_settings";
    private static final String CHANNEL = "precision_loop_channel";
    private static final String CLOCK_ACTION =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";
    private static final String PHONE = "phone";
    private static final String INTERNET = "internet";
    private static final long NS_MS = 1_000_000L;
    private static final long COUNTDOWN_MS = 200L;
    private static final int NOTIFICATION_ID = 4101;
    private static final int BATCH_SIZE = 1024;
    private static final int BG = Color.rgb(20, 30, 42);
    private static final int ORANGE = Color.rgb(255, 152, 0);

    private static volatile FloatingService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] markerLocation = new int[2];
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private WindowManager wm;
    private LinearLayout panel;
    private TextView selectedTimeView;
    private TextView statusView;
    private TextView countdownView;
    private TextView markerView;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams markerParams;

    private int targetX;
    private int targetY;
    private int markerSize;
    private int startHour;
    private int startMinute;
    private int startSecond;
    private int startMillis;

    private String clockSource = PHONE;
    private volatile long internetEpochNs;
    private volatile long internetElapsedNs;
    private boolean internetAnchorAvailable;
    private boolean receiverRegistered;
    private boolean floatingTimeConfigured;

    private long runStartNs;
    private long runEndNs;
    private long totalClicks;
    private long cycleDurationNs;
    private long cycleStrideNs;
    private long clickIntervalNs;
    private long touchDurationNs;
    private long cycleStartNs;
    private long cycleEndNs;
    private long nextTargetNs;

    private long completedClicks;
    private long expiredClicks;
    private long failedClicks;

    private int generation;
    private int cycleIndex;
    private int clickIndex;
    private int clicksPerCycle;
    private int cycleCount;

    private boolean clicking;
    private boolean runDisplayActive;
    private boolean runFinished;

    private float panelRawX;
    private float panelRawY;
    private float markerRawX;
    private float markerRawY;

    private int panelStartX;
    private int panelStartY;
    private int markerStartX;
    private int markerStartY;

    private Runnable clockRunnable;
    private Runnable countdownRunnable;
    private Runnable nextClickRunnable;

    private AutoClickService configuredService;

    public static FloatingService getInstance() {
        return instance;
    }

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null ||
                    !CLOCK_ACTION.equals(intent.getAction())) return;

            String source = intent.getStringExtra("clock_source");
            if (!PHONE.equals(source) && !INTERNET.equals(source)) return;

            long epoch = intent.getLongExtra(
                    "internet_epoch_at_sync_ns", 0);
            long elapsed = intent.getLongExtra(
                    "internet_elapsed_at_sync_ns", 0);

            if (epoch <= 0 || elapsed <= 0) {
                long epochMs = intent.getLongExtra(
                        "internet_epoch_at_sync_ms", 0);
                long elapsedMs = intent.getLongExtra(
                        "internet_elapsed_at_sync_ms", 0);
                try {
                    epoch = Math.multiplyExact(epochMs, NS_MS);
                    elapsed = Math.multiplyExact(elapsedMs, NS_MS);
                } catch (ArithmeticException ignored) {
                    epoch = elapsed = 0;
                }
            }

            if (epoch > 0 && elapsed > 0 &&
                    elapsed <= SystemClock.elapsedRealtimeNanos())
                saveInternetAnchor(epoch, elapsed);

            clockSource = source;
            getPrefs().edit().putString("clock_source", source).apply();
            updateClockDisplay();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        markerSize = dp(48);
        clockFormat.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));
        loadClockSource();
        registerClockReceiver();
        createNotificationChannel();
        startForegroundCompat();

        clockRunnable = new Runnable() {
            @Override
            public void run() {
                updateClockDisplay();
                handler.postDelayed(this, 1000);
            }
        };

        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                updateCountdown();
                handler.postDelayed(this, clicking ? COUNTDOWN_MS : 250);
            }
        };

        handler.post(clockRunnable);
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
            shutdown();
        } else if (ACTION_STOP.equals(action)) {
            stopClicking();
        } else if (ACTION_START.equals(action)) {
            createFloatingViews();
            startClicking(intent);
        } else {
            createFloatingViews();
        }

        return START_STICKY;
    }

    private void startClicking(Intent intent) {
        stopClicking();

        if (intent == null) {
            updateStatus("تنظیمات شروع دریافت نشد");
            return;
        }

        final int runId = ++generation;
        long now = SystemClock.elapsedRealtimeNanos();

        long start = intent.getLongExtra(
                "start_elapsed_realtime_ns", 0);

        if (start <= 0) {
            long oldStart = intent.getLongExtra(
                    "start_elapsed_realtime_ms", 0);
            if (oldStart > 0) {
                try {
                    start = Math.multiplyExact(oldStart, NS_MS);
                } catch (ArithmeticException e) {
                    updateStatus("زمان شروع نامعتبر است");
                    return;
                }
            }
        }

        if (start <= 0) {
            long delay;
            if (intent.hasExtra("start_delay_ns")) {
                delay = intent.getLongExtra("start_delay_ns", 0);
            } else {
                try {
                    delay = Math.multiplyExact(
                            intent.getLongExtra("start_delay_ms", 0),
                            NS_MS);
                } catch (ArithmeticException e) {
                    updateStatus("زمان شروع نامعتبر است");
                    return;
                }
            }

            if (delay < 0 || delay > Long.MAX_VALUE - now) {
                updateStatus("زمان شروع نامعتبر است");
                return;
            }
            start = now + delay;
        }

        long clicks = intent.getLongExtra(
                "clicks_per_cycle",
                intent.getLongExtra("click_count", 1));
        long cycles = intent.getLongExtra("cycle_count", 1);

        if (clicks < 1 || clicks > Integer.MAX_VALUE ||
                cycles < 1 || cycles > Integer.MAX_VALUE) {
            updateStatus("تعداد کلیک یا چرخه نامعتبر است");
            return;
        }

        int requestedClicks = (int) clicks;
        int requestedCycles = (int) cycles;

        long duration = getNanos(
                intent, "cycle_duration_ns", "cycle_duration_ms", 0);
        long pause = getNanos(
                intent, "cycle_pause_ns", "cycle_pause_ms", 0);
        long interval = getNanos(
                intent, "click_interval_ns", "click_interval_ms", NS_MS);
        long touch = getNanos(
                intent, "touch_duration_ns", "touch_duration_ms", NS_MS);

        if (duration < 0 || pause < 0 || interval < 0 || touch < 0) {
            updateStatus("تنظیمات زمانی نامعتبر است");
            return;
        }

        if (touch == 0) {
            updateStatus("مدت لمس باید بیشتر از صفر باشد");
            return;
        }

        if (touch % NS_MS != 0) {
            updateStatus(
                    "مدت لمس باید مضرب دقیق ۱٬۰۰۰٬۰۰۰ نانوثانیه باشد");
            return;
        }

        long touchMs = touch / NS_MS;
        if (touchMs > GestureDescription.getMaxGestureDuration()) {
            updateStatus("مدت لمس از حداکثر زمان مجاز اندروید بیشتر است");
            return;
        }

        long lastOffset;
        long stride;
        long end;
        long total;

        try {
            lastOffset = Math.multiplyExact(
                    requestedClicks - 1L, interval);

            if (duration == 0)
                duration = Math.max(1, Math.addExact(lastOffset, touch));

            if (lastOffset >= duration ||
                    Math.addExact(lastOffset, touch) > duration) {
                updateStatus("زمان آخرین کلیک از مهلت چرخه عبور می‌کند");
                return;
            }

            stride = Math.addExact(duration, pause);

            long fullDuration = Math.addExact(
                    Math.multiplyExact((long) requestedCycles, duration),
                    Math.multiplyExact((long) requestedCycles - 1, pause));

            end = Math.addExact(start, fullDuration);
            total = Math.multiplyExact(
                    (long) requestedClicks, requestedCycles);

        } catch (ArithmeticException e) {
            updateStatus("مدت یا تعداد اجرا بیش از حد بزرگ است");
            return;
        }

        clicksPerCycle = requestedClicks;
        cycleCount = requestedCycles;
        cycleDurationNs = duration;
        cycleStrideNs = stride;
        clickIntervalNs = interval;
        touchDurationNs = touch;
        runStartNs = start;
        runEndNs = end;
        totalClicks = total;
        cycleIndex = 0;
        clickIndex = 0;
        cycleStartNs = start;
        cycleEndNs = start + duration;
        nextTargetNs = start;
        completedClicks = 0;
        expiredClicks = 0;
        failedClicks = 0;
        clicking = true;
        runDisplayActive = true;
        runFinished = false;

        updateTarget();
        setMarkerTouchable(false);
        showFloatingViews();
        updateStatus("زمان‌بندی کلیک‌ها آغاز شد");
        updateCountdown();
        scheduleNextClick(runId);
    }

    private long getNanos(
            Intent intent, String nsKey, String msKey, long defaultValue) {
        if (intent.hasExtra(nsKey))
            return intent.getLongExtra(nsKey, defaultValue);
        try {
            return Math.multiplyExact(
                    intent.getLongExtra(msKey, defaultValue / NS_MS),
                    NS_MS);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    private void scheduleNextClick(int runId) {
        if (!clicking || runId != generation) return;

        int scheduledClicks = 0;

        while (cycleIndex < cycleCount &&
                scheduledClicks < BATCH_SIZE) {

            if (clickIndex >= clicksPerCycle) {
                cycleIndex++;
                clickIndex = 0;

                if (cycleIndex >= cycleCount) {
                    scheduleRunFinish(runId);
                    return;
                }

                try {
                    cycleStartNs = Math.addExact(
                            cycleStartNs, cycleStrideNs);
                    cycleEndNs = Math.addExact(
                            cycleStartNs, cycleDurationNs);
                    nextTargetNs = cycleStartNs;
                } catch (ArithmeticException e) {
                    updateStatus("خطای محاسبات زمان‌بندی");
                    stopClicking();
                    return;
                }
            }

            int count = clicksPerCycle;
            if (!queueCycle(
                    runId, cycleStartNs, cycleEndNs, count))
                updateStatus("ثبت دسته کلیک‌های چرخه ناموفق بود");

            clickIndex = clicksPerCycle;
            scheduledClicks += count;
        }

        if (cycleIndex >= cycleCount) {
            scheduleRunFinish(runId);
        } else if (clicking && runId == generation) {
            nextClickRunnable = () -> scheduleNextClick(runId);
            handler.post(nextClickRunnable);
        }
    }

    private boolean queueCycle(
            int runId, long cycleStart, long deadline, int count) {

        if (!clicking || runId != generation || count <= 0)
            return false;

        final long[] targets;
        final AutoClickService.GestureCompletion[] completions;

        try {
            targets = new long[count];
            completions = new AutoClickService.GestureCompletion[count];

            for (int i = 0; i < count; i++) {
                long offset = Math.multiplyExact(
                        (long) i, clickIntervalNs);
                targets[i] = Math.addExact(cycleStart, offset);
                completions[i] = (success, expired) ->
                        recordResult(runId, success, expired);
            }
        } catch (ArithmeticException | OutOfMemoryError e) {
            for (int i = 0; i < count; i++)
                recordResult(runId, false, false);
            return false;
        }

        AutoClickService service = AutoClickService.getInstance();

        if (service == null || !service.isServiceReady()) {
            for (int i = 0; i < count; i++)
                recordResult(runId, false, false);
            return false;
        }

        service.setTarget(targetX, targetY);
        configuredService = service;

        boolean accepted;
        try {
            accepted = service.performClickBatchAtTargets(
                    targets, cycleStart, deadline,
                    touchDurationNs, completions);
        } catch (Exception e) {
            accepted = false;
        }

        if (!accepted) {
            for (int i = 0; i < count; i++)
                recordResult(runId, false, false);
        }

        return accepted;
    }

    private void recordResult(
            int runId, boolean success, boolean expired) {
        if (runId != generation) return;

        if (success) completedClicks++;
        else if (expired) expiredClicks++;
        else failedClicks++;

        updateRunResult();
    }

    private void scheduleRunFinish(int runId) {
        if (runId != generation) return;

        long remaining =
                runEndNs - SystemClock.elapsedRealtimeNanos();

        if (remaining > 0) {
            long delay = remaining / NS_MS;
            if (remaining % NS_MS != 0) delay++;
            handler.postDelayed(
                    () -> scheduleRunFinish(runId),
                    Math.max(1, delay));
        } else {
            finishClicking();
        }
    }

    private void finishClicking() {
        if (!clicking) return;
        clicking = false;
        updateStatus("چرخه‌ها تمام شدند؛ در انتظار نتایج");
        updateRunResult();
        updateCountdown();
    }

    private void updateRunResult() {
        long results = completedClicks + expiredClicks + failedClicks;

        if (!clicking && totalClicks > 0 && results >= totalClicks) {
            runDisplayActive = false;
            runFinished = true;
            setMarkerTouchable(true);
            updateStatus("اجرای تمام درخواست‌ها به پایان رسید");
            updateCountdown();
        }
    }

    private void stopClicking() {
        clicking = false;
        runDisplayActive = false;
        runFinished = false;
        generation++;

        if (nextClickRunnable != null) {
            handler.removeCallbacks(nextClickRunnable);
            nextClickRunnable = null;
        }

        AutoClickService service = AutoClickService.getInstance();
        if (service != null) service.cancelPendingScheduledClick();

        configuredService = null;
        setMarkerTouchable(true);
        updateStatus("اجرای کلیک‌ها متوقف شد");
        updateCountdown();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void loadClockSource() {
        SharedPreferences p = getPrefs();
        clockSource = INTERNET.equals(
                p.getString("clock_source", PHONE)) ? INTERNET : PHONE;

        long epoch = p.getLong("internet_epoch_at_sync_ns", 0);
        long elapsed = p.getLong("internet_elapsed_at_sync_ns", 0);

        if (epoch <= 0 || elapsed <= 0) {
            try {
                epoch = Math.multiplyExact(
                        p.getLong("internet_epoch_at_sync_ms", 0), NS_MS);
                elapsed = Math.multiplyExact(
                        p.getLong("internet_elapsed_at_sync_ms", 0), NS_MS);
            } catch (ArithmeticException e) {
                epoch = elapsed = 0;
            }
        }

        if (epoch > 0 && elapsed > 0 &&
                elapsed <= SystemClock.elapsedRealtimeNanos()) {
            saveInternetAnchor(epoch, elapsed);
        } else {
            internetEpochNs = 0;
            internetElapsedNs = 0;
            internetAnchorAvailable = false;
        }
    }

    private void saveInternetAnchor(long epoch, long elapsed) {
        if (epoch <= 0 || elapsed <= 0) return;

        internetEpochNs = epoch;
        internetElapsedNs = elapsed;
        internetAnchorAvailable = true;

        getPrefs().edit()
                .putLong("internet_epoch_at_sync_ns", epoch)
                .putLong("internet_elapsed_at_sync_ns", elapsed)
                .putLong("internet_epoch_at_sync_ms", epoch / NS_MS)
                .putLong("internet_elapsed_at_sync_ms", elapsed / NS_MS)
                .apply();
    }

    private void registerClockReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(CLOCK_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                        clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(clockReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
            receiverRegistered = false;
        }
    }

    private void unregisterClockReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(clockReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private long currentClockMs() {
        if (!INTERNET.equals(clockSource))
            return System.currentTimeMillis();

        if (!internetAnchorAvailable ||
                internetEpochNs <= 0 || internetElapsedNs <= 0)
            return 0;

        long now = SystemClock.elapsedRealtimeNanos();
        if (now < internetElapsedNs) return 0;

        long diff = now - internetElapsedNs;
        if (internetEpochNs > Long.MAX_VALUE - diff) return 0;

        return (internetEpochNs + diff) / NS_MS;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Precision Loop Auto Clicker",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("سرویس تنظیم زمان");

        NotificationManager manager =
                getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23)
            flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pending = PendingIntent.getActivity(
                this, 100, open, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Precision Loop Auto Clicker")
                .setContentText("سرویس تنظیم زمان فعال است")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createFloatingViews() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (wm == null)
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (panel == null) createPanel();
        if (markerView == null) createMarker();

        showFloatingViews();
        updateTarget();
        updateClockDisplay();
        updateCountdown();
        setMarkerTouchable(!clicking);
    }

    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(7), dp(8), dp(7));
        panel.setBackground(background(BG, dp(11)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(7), dp(6), dp(7), dp(6));
        box.setBackground(background(Color.rgb(13, 25, 36), dp(8)));

        TextView heading = text("تنظیم زمان", ORANGE, 13, true);
        selectedTimeView = text("", Color.WHITE, 10, false);
        countdownView = text(
                "آماده", Color.rgb(255, 210, 90), 11, true);
        statusView = text(
                "آماده", Color.rgb(180, 190, 205), 10, false);

        box.addView(heading);
        box.addView(selectedTimeView);
        box.addView(countdownView);
        box.addView(statusView);
        panel.addView(box);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(5), 0, 0);

        TextView main = panelButton("تنظیمات", Color.rgb(20, 125, 75));
        TextView time = panelButton("⚙", Color.rgb(55, 85, 110));
        TextView off = panelButton("خاموش", Color.rgb(75, 75, 85));

        main.setOnClickListener(v -> openMain());
        time.setOnClickListener(v -> showStartTimePicker());
        off.setOnClickListener(v -> shutdown());

        row.addView(main);
        row.addView(time);
        row.addView(off);
        panel.addView(row);

        panel.setOnTouchListener((v, event) -> {
            if (panelParams == null) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    panelRawX = event.getRawX();
                    panelRawY = event.getRawY();
                    panelStartX = panelParams.x;
                    panelStartY = panelParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    panelParams.x = panelStartX +
                            (int) (event.getRawX() - panelRawX);
                    panelParams.y = panelStartY +
                            (int) (event.getRawY() - panelRawY);
                    updateView(panel, panelParams);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        });

        panelParams = new WindowManager.LayoutParams(
                -2, -2, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(12);
        panelParams.y = dp(120);

        try {
            wm.addView(panel, panelParams);
        } catch (Exception e) {
            panel = null;
            panelParams = null;
        }
    }

    private void createMarker() {
        SharedPreferences prefs = getPrefs();

        markerView = new TextView(this);
        markerView.setText("⊕");
        markerView.setTextSize(39);
        markerView.setGravity(Gravity.CENTER);
        markerView.setTextColor(Color.WHITE);
        markerView.setBackground(background(
                Color.argb(220, 220, 35, 35), dp(24)));

        markerView.setOnTouchListener((v, event) -> {
            if (markerParams == null || clicking) return false;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    markerRawX = event.getRawX();
                    markerRawY = event.getRawY();
                    markerStartX = markerParams.x;
                    markerStartY = markerParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    markerParams.x = markerStartX +
                            (int) (event.getRawX() - markerRawX);
                    markerParams.y = markerStartY +
                            (int) (event.getRawY() - markerRawY);
                    updateView(markerView, markerParams);
                    updateTarget();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    updateTarget();
                    prefs.edit()
                            .putInt("marker_x", markerParams.x)
                            .putInt("marker_y", markerParams.y)
                            .apply();
                    return true;
            }
            return false;
        });

        markerParams = new WindowManager.LayoutParams(
                markerSize, markerSize, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        markerParams.gravity = Gravity.TOP | Gravity.START;
        markerParams.x = prefs.getInt("marker_x", dp(120));
        markerParams.y = prefs.getInt("marker_y", dp(350));

        try {
            wm.addView(markerView, markerParams);
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
            configuredService = service;
        } else {
            configuredService = null;
        }
    }

    private void setMarkerTouchable(boolean touchable) {
        if (markerView == null || markerParams == null) return;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        if (!touchable)
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        if (markerParams.flags == flags) return;

        markerParams.flags = flags;
        updateView(markerView, markerParams);
    }

    private TextView text(
            String value, int color, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(3), 0, dp(3));
        return v;
    }

    private TextView panelButton(String value, int color) {
        TextView v = text(value, Color.WHITE, 11, true);
        v.setBackground(background(color, dp(7)));
        v.setClickable(true);

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(0, dp(34), 1);
        p.setMargins(dp(2), 0, dp(2), 0);
        v.setLayoutParams(p);
        return v;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), Color.argb(100, 255, 255, 255));
        return d;
    }

    private void showStartTimePicker() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (INTERNET.equals(clockSource) && !internetAnchorAvailable) {
            updateStatus("مرجع ساعت اینترنتی هنوز دریافت نشده است");
            return;
        }

        if (!floatingTimeConfigured) {
            long epoch = currentClockMs();
            if (epoch <= 0) {
                updateStatus("زمان مرجع ساعت در دسترس نیست");
                return;
            }

            Calendar c = Calendar.getInstance(
                    TimeZone.getTimeZone("Asia/Tehran"));
            c.setTimeInMillis(epoch);
            startHour = c.get(Calendar.HOUR_OF_DAY);
            startMinute = c.get(Calendar.MINUTE);
            startSecond = c.get(Calendar.SECOND);
            startMillis = c.get(Calendar.MILLISECOND);
        }

        Dialog dialog = new Dialog(
                this, android.R.style.Theme_Material_Light_Dialog_Alert);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(10));
        root.setBackground(background(Color.WHITE, dp(12)));
        root.addView(text("تنظیم زمان شروع", ORANGE, 16, true));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);

        NumberPicker hour = picker(0, 23, startHour);
        NumberPicker minute = picker(0, 59, startMinute);
        NumberPicker second = picker(0, 59, startSecond);
        NumberPicker millis = picker(0, 999, startMillis);
        millis.setFormatter(v -> String.format(Locale.US, "%03d", v));

        addPicker(row, "ساعت", hour);
        addPicker(row, "دقیقه", minute);
        addPicker(row, "ثانیه", second);
        addPicker(row, "میلی‌ثانیه", millis);
        root.addView(row);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);

        TextView cancel = panelButton(
                "انصراف", Color.rgb(90, 90, 100));
        TextView confirm = panelButton(
                "تأیید", Color.rgb(20, 125, 75));

        buttons.addView(cancel);
        buttons.addView(confirm);
        root.addView(buttons);

        dialog.setContentView(root);
        dialog.setCancelable(true);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setType(overlayType());
            window.setBackgroundDrawableResource(
                    android.R.color.transparent);
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            WindowManager.LayoutParams p = window.getAttributes();
            p.width = dp(340);
            p.height = WindowManager.LayoutParams.WRAP_CONTENT;
            p.gravity = Gravity.CENTER;
            p.dimAmount = 0.35f;
            window.setAttributes(p);
        }

        cancel.setOnClickListener(v -> dialog.dismiss());

        confirm.setOnClickListener(v -> {
            startHour = hour.getValue();
            startMinute = minute.getValue();
            startSecond = second.getValue();
            startMillis = millis.getValue();
            floatingTimeConfigured = true;
            updateClockDisplay();
            updateStatus("زمان شروع انتخاب شد");
            dialog.dismiss();
        });

        try {
            dialog.show();
            if (window != null)
                window.setLayout(
                        dp(340), WindowManager.LayoutParams.WRAP_CONTENT);
        } catch (Exception e) {
            updateStatus("باز کردن انتخاب‌گر زمان ناموفق بود");
        }
    }

    private NumberPicker picker(int min, int max, int value) {
        NumberPicker p = new NumberPicker(this);
        p.setMinValue(min);
        p.setMaxValue(max);
        p.setValue(Math.max(min, Math.min(max, value)));
        p.setWrapSelectorWheel(true);
        p.setDescendantFocusability(
                NumberPicker.FOCUS_AFTER_DESCENDANTS);
        p.setBackgroundColor(Color.WHITE);
        p.setLayoutParams(
                new LinearLayout.LayoutParams(-1, dp(105)));

        try {
            Field f = NumberPicker.class.getDeclaredField("mInputText");
            f.setAccessible(true);
            EditText input = (EditText) f.get(p);
            if (input != null) {
                input.setTextColor(Color.BLACK);
                input.setBackgroundColor(Color.TRANSPARENT);
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private void addPicker(
            LinearLayout parent, String label, NumberPicker picker) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.addView(text(label, Color.BLACK, 10, false));
        column.addView(picker);
        column.setLayoutParams(
                new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(column);
    }

    private String formatStartTime() {
        return String.format(
                Locale.US, "%02d:%02d:%02d.%03d",
                startHour, startMinute, startSecond, startMillis);
    }

    private void updateClockDisplay() {
        if (selectedTimeView == null) return;

        long epoch = currentClockMs();
        String time = epoch > 0
                ? clockFormat.format(epoch)
                : "در انتظار همگام‌سازی";

        String source = INTERNET.equals(clockSource)
                ? (internetAnchorAvailable
                    ? "ساعت اینترنتی ایران"
                    : "ساعت اینترنتی بدون مرجع")
                : "ساعت گوشی";

        String value = source + ": " + time;
        if (floatingTimeConfigured)
            value += "\nزمان شروع: " + formatStartTime();

        selectedTimeView.setText(value);
    }

    private void updateCountdown() {
        if (countdownView == null) return;

        if (runFinished) {
            countdownView.setText(String.format(
                    Locale.US,
                    "پایان | موفق: %d | منقضی: %d | خطا: %d",
                    completedClicks, expiredClicks, failedClicks));
            return;
        }

        if (!runDisplayActive) {
            countdownView.setText("آماده");
            return;
        }

        long remaining =
                runStartNs - SystemClock.elapsedRealtimeNanos();

        if (remaining > 0) {
            long ms = remaining / NS_MS;
            countdownView.setText(String.format(
                    Locale.US,
                    "شروع تا: %02d:%02d:%02d.%03d",
                    ms / 3_600_000,
                    ms % 3_600_000 / 60_000,
                    ms % 60_000 / 1000,
                    ms % 1000));
        } else {
            long results =
                    completedClicks + expiredClicks + failedClicks;
            countdownView.setText(String.format(
                    Locale.US, "در حال اجرا | نتیجه: %d/%d",
                    results, totalClicks));
        }
    }

    private void updateStatus(String value) {
        if (statusView != null) statusView.setText(value);
    }

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(ACTION_START_FROM_FLOATING);
        intent.putExtra("clock_source", clockSource);

        if (floatingTimeConfigured) {
            intent.putExtra("floating_start_time_configured", true);
            intent.putExtra("floating_start_hour", startHour);
            intent.putExtra("floating_start_minute", startMinute);
            intent.putExtra("floating_start_second", startSecond);
            intent.putExtra("floating_start_millis", startMillis);
        }

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);

        try {
            startActivity(intent);
        } catch (Exception e) {
            updateStatus("باز کردن صفحه اصلی ناموفق بود");
        }
    }

    public static void setOverlaysHidden(boolean hidden) {
        FloatingService s = instance;
        if (s == null) return;

        s.handler.post(() -> {
            int visibility = hidden ? View.INVISIBLE : View.VISIBLE;
            if (s.panel != null) s.panel.setVisibility(visibility);
            if (s.markerView != null)
                s.markerView.setVisibility(visibility);
        });
    }

    private void showFloatingViews() {
        try {
            if (panel != null) panel.setVisibility(View.VISIBLE);
            if (markerView != null)
                markerView.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
        }
    }

    private void updateView(
            View view, WindowManager.LayoutParams params) {
        try {
            if (view != null && view.getParent() != null)
                wm.updateViewLayout(view, params);
        } catch (Exception ignored) {
        }
    }

    private int dp(float value) {
        return (int) (
                value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    private void shutdown() {
        stopClicking();
        removeFloatingViews();

        if (Build.VERSION.SDK_INT >= 24)
            stopForeground(STOP_FOREGROUND_REMOVE);
        else
            stopForeground(true);

        stopSelf();
    }

    private void removeFloatingViews() {
        try {
            if (panel != null && panel.getParent() != null)
                wm.removeView(panel);
        } catch (Exception ignored) {
        }

        try {
            if (markerView != null && markerView.getParent() != null)
                wm.removeView(markerView);
        } catch (Exception ignored) {
        }

        panel = null;
        markerView = null;
        panelParams = null;
        markerParams = null;
        selectedTimeView = null;
        statusView = null;
        countdownView = null;
    }

    @Override
    public void onDestroy() {
        stopClicking();
        if (clockRunnable != null)
            handler.removeCallbacks(clockRunnable);
        if (countdownRunnable != null)
            handler.removeCallbacks(countdownRunnable);

        unregisterClockReceiver();
        removeFloatingViews();

        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
