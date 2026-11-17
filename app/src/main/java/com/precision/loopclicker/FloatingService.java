
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
import java.util.Date;
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

    private static final String CHANNEL_ID = "precision_loop_channel";
    private static final int NOTIFICATION_ID = 4101;

    private static final String PREF_NAME = "precision_loop_settings";
    private static final String PREF_CLOCK_SOURCE = "clock_source";
    private static final String PREF_INTERNET_EPOCH =
            "internet_epoch_at_sync_ms";
    private static final String PREF_INTERNET_ELAPSED =
            "internet_elapsed_at_sync_ms";
    private static final String PREF_MARKER_X = "marker_x";
    private static final String PREF_MARKER_Y = "marker_y";

    private static final String CLOCK_PHONE = "phone";
    private static final String CLOCK_INTERNET = "internet";

    private static final String ACTION_CLOCK_SOURCE_CHANGED =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";

    private static final int PANEL_BACKGROUND = Color.rgb(20, 30, 42);
    private static final int TITLE_COLOR = Color.rgb(255, 152, 0);

    private static final long SERVICE_RETRY_MS = 25L;

    private static volatile FloatingService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;

    private LinearLayout panel;
    private TextView selectedTimeView;
    private TextView statusView;
    private TextView countdownView;
    private TextView markerView;

    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams markerParams;

    private int markerSize;
    private int targetX;
    private int targetY;

    private String clockSource = CLOCK_PHONE;

    private long internetEpochAtSyncMs;
    private long internetElapsedAtSyncMs;

    private boolean internetTimeAnchorAvailable;
    private boolean clockSourceReceiverRegistered;
    private boolean floatingTimeConfigured;

    private int floatingStartHour;
    private int floatingStartMinute;
    private int floatingStartSecond;
    private int floatingStartMillis;

    private float panelTouchRawX;
    private float panelTouchRawY;
    private int panelStartX;
    private int panelStartY;

    private float markerTouchRawX;
    private float markerTouchRawY;
    private int markerStartX;
    private int markerStartY;

    private Runnable displayRunnable;
    private Runnable countdownRunnable;

    // Click scheduling

    private boolean clicking;
    private boolean runDisplayActive;
    private boolean runFinished;

    private int runGeneration;
    private int cycleIndex;
    private int clickIndex;

    private int clicksPerCycle;
    private int cycleCount;

    private long cycleDurationMs;
    private long clickIntervalMs;
    private long touchDurationMs;
    private long compensationMs;

    private long runStartElapsedMs;
    private long runStartEpochMs;
    private long totalClicks;

    private int completedClicks;
    private int expiredClicks;
    private int failedClicks;

    public static FloatingService getInstance() {
        return instance;
    }

    // Clock source receiver

    private final BroadcastReceiver clockSourceReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null ||
                    !ACTION_CLOCK_SOURCE_CHANGED.equals(intent.getAction())) {
                return;
            }

            String source = intent.getStringExtra("clock_source");

            if (!CLOCK_PHONE.equals(source) &&
                    !CLOCK_INTERNET.equals(source)) {
                return;
            }

            if (intent.hasExtra("internet_epoch_at_sync_ms") &&
                    intent.hasExtra("internet_elapsed_at_sync_ms")) {

                long epoch = intent.getLongExtra(
                        "internet_epoch_at_sync_ms", 0L);
                long elapsed = intent.getLongExtra(
                        "internet_elapsed_at_sync_ms", 0L);

                if (epoch > 0 && elapsed > 0 &&
                        elapsed <= SystemClock.elapsedRealtime()) {

                    internetEpochAtSyncMs = epoch;
                    internetElapsedAtSyncMs = elapsed;
                    internetTimeAnchorAvailable = true;
                    saveInternetAnchor(epoch, elapsed);
                }
            }

            clockSource = source;

            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_CLOCK_SOURCE, clockSource)
                    .apply();

            updateClockDisplay();
        }
    };

    // Service lifecycle

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        markerSize = dp(48);

        loadSavedClockSource();
        registerClockSourceReceiver();
        createNotificationChannel();
        startForegroundCompat();

        displayRunnable = new Runnable() {
            @Override
            public void run() {
                updateClockDisplay();
                handler.postDelayed(this, 250);
            }
        };

        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                updateCountdownDisplay();
                handler.postDelayed(this, 50);
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

    // Click scheduling

    private void startClicking(Intent intent) {
        stopClicking();

        if (intent == null) {
            updateStatus("تنظیمات شروع دریافت نشد");
            return;
        }

        int generation = ++runGeneration;
        long now = SystemClock.elapsedRealtime();

        long requestedStart = intent.getLongExtra(
                "start_elapsed_realtime_ms", 0L);

        if (requestedStart <= 0) {
            long delay = intent.getLongExtra("start_delay_ms", 0L);

            if (delay < 0 || delay > Long.MAX_VALUE - now) {
                updateStatus("زمان شروع نامعتبر است");
                return;
            }

            requestedStart = now + delay;
        }

        // اصلاح دریافت تعداد کلیک‌ها و چرخه‌ها از نوع long

        long requestedClicksValue = intent.getLongExtra(
                "clicks_per_cycle",
                intent.getLongExtra("click_count", 1L));

        long requestedCyclesValue = intent.getLongExtra(
                "cycle_count", 1L);

        if (requestedClicksValue < 1 ||
                requestedClicksValue > Integer.MAX_VALUE ||
                requestedCyclesValue < 1 ||
                requestedCyclesValue > Integer.MAX_VALUE) {

            updateStatus("تعداد کلیک یا چرخه نامعتبر است");
            return;
        }

        int requestedClicks = (int) requestedClicksValue;
        int requestedCycles = (int) requestedCyclesValue;

        long requestedCycleDuration = intent.getLongExtra(
                "cycle_duration_ms", 0L);

        long requestedInterval = intent.getLongExtra(
                "click_interval_ms", 50L);

        long requestedTouchDuration = intent.getLongExtra(
                "touch_duration_ms", 1L);

        long requestedCompensation = intent.getLongExtra(
                "compensation_ms",
                intent.getLongExtra("dispatch_lead_ms", 50L));

        if (requestedInterval < 0 || requestedCompensation < 0 ||
                requestedTouchDuration < 1 ||
                requestedTouchDuration >
                        GestureDescription.getMaxGestureDuration()) {
            updateStatus("تنظیمات اجرای کلیک نامعتبر است");
            return;
        }

        long lastClickOffset;
        long lastTouchEndOffset;

        try {
            lastClickOffset = Math.multiplyExact(
                    requestedClicks - 1L, requestedInterval);

            lastTouchEndOffset = Math.addExact(
                    lastClickOffset, requestedTouchDuration);

        } catch (ArithmeticException e) {
            updateStatus("فاصله یا مدت کلیک‌ها نامعتبر است");
            return;
        }

        if (requestedCycleDuration <= 0) {
            requestedCycleDuration = lastTouchEndOffset;
        }

        if (lastTouchEndOffset > requestedCycleDuration) {
            updateStatus("آخرین لمس از پایان چرخه عبور می‌کند");
            return;
        }

        long calculatedTotalClicks;

        try {
            long totalDuration = Math.multiplyExact(
                    (long) requestedCycles, requestedCycleDuration);

            Math.addExact(requestedStart, totalDuration);

            calculatedTotalClicks = Math.multiplyExact(
                    (long) requestedClicks, requestedCycles);

        } catch (ArithmeticException e) {
            updateStatus("مدت کل اجرا بیش از حد بزرگ است");
            return;
        }

        clicksPerCycle = requestedClicks;
        cycleCount = requestedCycles;
        cycleDurationMs = requestedCycleDuration;
        clickIntervalMs = requestedInterval;
        touchDurationMs = requestedTouchDuration;
        compensationMs = requestedCompensation;

        runStartElapsedMs = requestedStart;
        totalClicks = calculatedTotalClicks;

        long requestedEpoch = intent.getLongExtra(
                "start_time_epoch_ms", 0L);

        long currentEpoch = getCurrentClockEpochMs();

        try {
            if (requestedEpoch > 0) {
                runStartEpochMs = requestedEpoch;
            } else if (currentEpoch > 0) {
                runStartEpochMs = Math.addExact(
                        currentEpoch,
                        Math.subtractExact(requestedStart, now));
            } else {
                runStartEpochMs = Math.addExact(
                        System.currentTimeMillis(),
                        Math.subtractExact(requestedStart, now));
            }
        } catch (ArithmeticException e) {
            updateStatus("زمان شروع نامعتبر است");
            return;
        }

        cycleIndex = 0;
        clickIndex = 0;

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
        updateCountdownDisplay();

        scheduleNextClick(generation);
    }

    /**
     * هر درخواست مستقل از Callback درخواست قبلی زمان‌بندی می‌شود.
     */
    private void scheduleNextClick(int generation) {
        if (!clicking || generation != runGeneration) {
            return;
        }

        if (cycleIndex >= cycleCount) {
            finishClicking();
            return;
        }

        if (clickIndex >= clicksPerCycle) {
            cycleIndex++;
            clickIndex = 0;

            handler.post(() -> scheduleNextClick(generation));
            return;
        }

        try {
            long cycleStart = Math.addExact(
                    runStartElapsedMs,
                    Math.multiplyExact(
                            (long) cycleIndex, cycleDurationMs));

            long target = Math.addExact(
                    cycleStart,
                    Math.multiplyExact(
                            (long) clickIndex, clickIntervalMs));

            long cycleEnd = Math.addExact(
                    cycleStart, cycleDurationMs);

            if (SystemClock.elapsedRealtime() >= cycleEnd) {
                expiredClicks++;
                clickIndex++;

                updateRunResultState();

                handler.post(() -> scheduleNextClick(generation));
                return;
            }

            dispatchClick(
                    generation,
                    cycleIndex,
                    clickIndex,
                    cycleStart,
                    target,
                    cycleEnd);

        } catch (ArithmeticException e) {
            updateStatus("خطای محاسبات زمان‌بندی");
            stopClicking();
        }
    }

    private void dispatchClick(
            int generation,
            int cycle,
            int click,
            long cycleStart,
            long targetElapsed,
            long cycleEnd
    ) {
        if (!clicking || generation != runGeneration) {
            return;
        }

        boolean requestIndexAdvanced = false;

        try {
            /*
             * جبران دستی فقط زمان ارسال را جلو می‌برد.
             * مرز پایان چرخه تغییر نمی‌کند.
             */
            long dispatchTarget = Math.subtractExact(
                    targetElapsed, compensationMs);

            long adjustedCycleStart = Math.subtractExact(
                    cycleStart, compensationMs);

            long targetNanos = Math.multiplyExact(
                    dispatchTarget, 1_000_000L);

            long cycleStartNanos = Math.multiplyExact(
                    adjustedCycleStart, 1_000_000L);

            long cycleEndNanos = Math.multiplyExact(
                    cycleEnd, 1_000_000L);

            updateTarget();

            AutoClickService service = AutoClickService.getInstance();

            /*
             * اگر سرویس آماده نیست، درخواست جاری مصرف نمی‌شود.
             * همان درخواست تا پایان مهلت چرخه دوباره بررسی می‌شود.
             */
            if (service == null || !service.isServiceReady()) {
                retryUnavailableRequest(
                        generation,
                        cycle,
                        click,
                        cycleStart,
                        targetElapsed,
                        cycleEnd);
                return;
            }

            /*
             * فقط وقتی سرویس آماده است، درخواست از صف زمان‌بندی
             * FloatingService خارج می‌شود.
             */
            clickIndex++;
            requestIndexAdvanced = true;

            boolean accepted = service.performClickAtTarget(
                    targetNanos,
                    cycleStartNanos,
                    cycleEndNanos,
                    touchDurationMs,

                    (success, expired) -> {
                        if (generation != runGeneration) {
                            return;
                        }

                        recordClickResult(success, expired);
                    }
            );

            /*
             * رد شدن درخواست فقط همان درخواست را ناموفق می‌کند.
             */
            if (!accepted) {
                failedClicks++;
                updateRunResultState();

                updateStatus(
                        "درخواست پذیرفته نشد؛ ادامه زمان‌بندی");

                handler.post(() -> scheduleNextClick(generation));
                return;
            }

            /*
             * برای ارسال درخواست بعدی منتظر Callback نمی‌مانیم.
             */
            handler.post(() -> scheduleNextClick(generation));

        } catch (ArithmeticException e) {
            if (!requestIndexAdvanced) {
                clickIndex++;
            }

            failedClicks++;
            updateRunResultState();

            updateStatus(
                    "خطای محاسبات یک درخواست؛ ادامه زمان‌بندی");

            handler.post(() -> scheduleNextClick(generation));

        } catch (Exception e) {
            if (!requestIndexAdvanced) {
                clickIndex++;
            }

            failedClicks++;
            updateRunResultState();

            updateStatus(
                    "خطا در یک درخواست؛ ادامه زمان‌بندی");

            handler.post(() -> scheduleNextClick(generation));
        }
    }

    /**
     * سرویس در دسترس نیست:
     * درخواست جاری حفظ می‌شود و هر ۲۵ میلی‌ثانیه دوباره بررسی می‌شود.
     * اگر مهلت چرخه تمام شود، فقط همان درخواست منقضی خواهد شد.
     */
    private void retryUnavailableRequest(
            int generation,
            int cycle,
            int click,
            long cycleStart,
            long targetElapsed,
            long cycleEnd
    ) {
        if (!clicking || generation != runGeneration ||
                cycle != cycleIndex || click != clickIndex) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now >= cycleEnd) {
            expiredClicks++;
            clickIndex++;

            updateRunResultState();
            updateStatus("مهلت درخواست تمام شد؛ ادامه زمان‌بندی");

            handler.post(() -> scheduleNextClick(generation));
            return;
        }

        handler.postDelayed(() -> {
            if (!clicking || generation != runGeneration ||
                    cycle != cycleIndex || click != clickIndex) {
                return;
            }

            dispatchClick(
                    generation,
                    cycle,
                    click,
                    cycleStart,
                    targetElapsed,
                    cycleEnd);

        }, SERVICE_RETRY_MS);
    }

    private void recordClickResult(boolean success, boolean expired) {
        if (success) {
            completedClicks++;
        } else if (expired) {
            expiredClicks++;
        } else {
            failedClicks++;
        }

        updateRunResultState();
    }

    /**
     * نتیجه Callbackها و درخواست‌های ردشده یا منقضی‌شده
     * برای تشخیص پایان گزارش بررسی می‌شوند.
     */
    private void updateRunResultState() {
        long resultCount =
                (long) completedClicks + expiredClicks + failedClicks;

        if (totalClicks > 0 && resultCount >= totalClicks) {
            runDisplayActive = false;
            runFinished = true;

            setMarkerTouchable(true);

            updateStatus("اجرای تمام درخواست‌ها به پایان رسید");
        }

        updateCountdownDisplay();
    }

    private void finishClicking() {
        clicking = false;

        /*
         * ممکن است Callback درخواست‌های پذیرفته‌شده هنوز نرسیده باشد.
         * نمایش نتیجه تا دریافت آن‌ها فعال می‌ماند.
         */
        setMarkerTouchable(true);

        updateStatus("تمام درخواست‌های کلیک زمان‌بندی شدند");
        updateRunResultState();
    }

    private void stopClicking() {
        clicking = false;
        runDisplayActive = false;
        runFinished = false;

        runGeneration++;

        AutoClickService service = AutoClickService.getInstance();

        if (service != null) {
            service.cancelPendingScheduledClick();
        }

        setMarkerTouchable(true);

        updateStatus("اجرای کلیک‌ها متوقف شد");
        updateCountdownDisplay();
    }

    // Clock source

    private void loadSavedClockSource() {
        try {
            SharedPreferences preferences =
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE);

            String savedSource = preferences.getString(
                    PREF_CLOCK_SOURCE, CLOCK_PHONE);

            clockSource = CLOCK_INTERNET.equals(savedSource)
                    ? CLOCK_INTERNET : CLOCK_PHONE;

            internetEpochAtSyncMs = preferences.getLong(
                    PREF_INTERNET_EPOCH, 0L);

            internetElapsedAtSyncMs = preferences.getLong(
                    PREF_INTERNET_ELAPSED, 0L);

            long now = SystemClock.elapsedRealtime();

            internetTimeAnchorAvailable =
                    internetEpochAtSyncMs > 0 &&
                    internetElapsedAtSyncMs > 0 &&
                    internetElapsedAtSyncMs <= now;

        } catch (Exception ignored) {
            clockSource = CLOCK_PHONE;
            internetEpochAtSyncMs = 0L;
            internetElapsedAtSyncMs = 0L;
            internetTimeAnchorAvailable = false;
        }
    }

    private void saveInternetAnchor(long epoch, long elapsed) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PREF_INTERNET_EPOCH, epoch)
                .putLong(PREF_INTERNET_ELAPSED, elapsed)
                .apply();
    }

    private void registerClockSourceReceiver() {
        if (clockSourceReceiverRegistered) return;

        IntentFilter filter =
                new IntentFilter(ACTION_CLOCK_SOURCE_CHANGED);

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                        clockSourceReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED);
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
        if (CLOCK_INTERNET.equals(clockSource)) {
            if (!internetTimeAnchorAvailable) return 0L;

            long now = SystemClock.elapsedRealtime();
            long difference = now - internetElapsedAtSyncMs;

            if (difference < 0 ||
                    internetEpochAtSyncMs <= 0 ||
                    internetEpochAtSyncMs >
                            Long.MAX_VALUE - difference) {
                return 0L;
            }

            return internetEpochAtSyncMs + difference;
        }

        return System.currentTimeMillis();
    }

    // Foreground service

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Precision Loop Auto Clicker",
                NotificationManager.IMPORTANCE_LOW);

        channel.setDescription("سرویس تنظیم زمان");

        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat() {
        Intent openIntent = new Intent(this, MainActivity.class);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 100, openIntent, flags);

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Precision Loop Auto Clicker")
                .setContentText("سرویس تنظیم زمان فعال است")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // Floating panel and marker

    private void createFloatingViews() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (windowManager == null) {
            windowManager =
                    (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        if (panel == null) createPanel();
        if (markerView == null) createMarker();

        showFloatingViews();
        updateTarget();
        updateClockDisplay();
        updateCountdownDisplay();
    }

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
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

        LinearLayout buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER);
        buttonsRow.setPadding(0, dp(5), 0, 0);

        TextView openButton =
                createPanelButton("تنظیمات", Color.rgb(20, 125, 75));

        TextView timeButton = createTimePickerButton();

        TextView shutdownButton =
                createPanelButton("خاموش", Color.rgb(75, 75, 85));

        openButton.setOnClickListener(v -> openMainForStart());
        timeButton.setOnClickListener(v -> showStartTimePicker());
        shutdownButton.setOnClickListener(v -> shutdownService());

        buttonsRow.addView(openButton);
        buttonsRow.addView(timeButton);
        buttonsRow.addView(shutdownButton);
        panel.addView(buttonsRow);

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
                    panelParams.x = panelStartX +
                            (int) (event.getRawX() - panelTouchRawX);
                    panelParams.y = panelStartY +
                            (int) (event.getRawY() - panelTouchRawY);
                    updateViewLayout(panel, panelParams);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }

            return false;
        });

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
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
        SharedPreferences preferences =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        markerView = new TextView(this);
        markerView.setText("⊕");
        markerView.setTextSize(39);
        markerView.setGravity(Gravity.CENTER);
        markerView.setTextColor(Color.WHITE);
        markerView.setBackground(
                makeBackground(Color.argb(220, 220, 35, 35), dp(24)));

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
                    markerParams.x = markerStartX +
                            (int) (event.getRawX() - markerTouchRawX);
                    markerParams.y = markerStartY +
                            (int) (event.getRawY() - markerTouchRawY);

                    updateViewLayout(markerView, markerParams);
                    updateTarget();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    updateTarget();

                    preferences.edit()
                            .putInt(PREF_MARKER_X, markerParams.x)
                            .putInt(PREF_MARKER_Y, markerParams.y)
                            .apply();
                    return true;
            }

            return false;
        });

        markerParams = new WindowManager.LayoutParams(
                markerSize,
                markerSize,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        markerParams.gravity = Gravity.TOP | Gravity.START;
        markerParams.x = preferences.getInt(PREF_MARKER_X, dp(120));
        markerParams.y = preferences.getInt(PREF_MARKER_Y, dp(350));

        try {
            windowManager.addView(markerView, markerParams);
            updateTarget();
        } catch (Exception e) {
            markerView = null;
            markerParams = null;
        }
    }

    private void updateTarget() {
        if (markerParams == null) return;

        targetX = markerParams.x + markerSize / 2;
        targetY = markerParams.y + markerSize / 2;

        AutoClickService service = AutoClickService.getInstance();

        if (service != null) {
            service.setTarget(targetX, targetY);
        }
    }

    private void setMarkerTouchable(boolean touchable) {
        if (markerView == null || markerParams == null) return;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        if (!touchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        if (markerParams.flags == flags) return;

        markerParams.flags = flags;
        updateViewLayout(markerView, markerParams);
    }

    private void createTimeSection() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(7), dp(6), dp(7), dp(6));
        container.setBackground(
                makeBackground(Color.rgb(13, 25, 36), dp(8)));

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

        container.addView(heading);
        container.addView(selectedTimeView);
        container.addView(countdownView);
        container.addView(statusView);
        panel.addView(container);
    }

    private TextView createPanelButton(String text, int color) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), dp(5), dp(5), dp(5));
        button.setBackground(makeBackground(color, dp(7)));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(34), 1f);

        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
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
        button.setBackground(
                makeBackground(Color.rgb(55, 85, 110), dp(7)));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(38), dp(34));

        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
        button.setClickable(true);

        return button;
    }

    // Start time picker

    private void showStartTimePicker() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (CLOCK_INTERNET.equals(clockSource) &&
                !internetTimeAnchorAvailable) {
            updateStatus("مرجع ساعت اینترنتی هنوز دریافت نشده است");
            return;
        }

        if (!floatingTimeConfigured) {
            long currentEpoch = getCurrentClockEpochMs();

            if (currentEpoch <= 0) {
                updateStatus("زمان مرجع ساعت در دسترس نیست");
                return;
            }

            Calendar calendar = Calendar.getInstance(
                    TimeZone.getTimeZone("Asia/Tehran"));

            calendar.setTimeInMillis(currentEpoch);

            floatingStartHour = calendar.get(Calendar.HOUR_OF_DAY);
            floatingStartMinute = calendar.get(Calendar.MINUTE);
            floatingStartSecond = calendar.get(Calendar.SECOND);
            floatingStartMillis = calendar.get(Calendar.MILLISECOND);
        }

        Dialog dialog = new Dialog(
                this,
                android.R.style.Theme_Material_Light_Dialog_Alert);

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

        LinearLayout pickersRow = new LinearLayout(this);
        pickersRow.setOrientation(LinearLayout.HORIZONTAL);
        pickersRow.setGravity(Gravity.CENTER);

        NumberPicker hourPicker =
                createNumberPicker(0, 23, floatingStartHour);
        NumberPicker minutePicker =
                createNumberPicker(0, 59, floatingStartMinute);
        NumberPicker secondPicker =
                createNumberPicker(0, 59, floatingStartSecond);
        NumberPicker millisPicker =
                createNumberPicker(0, 999, floatingStartMillis);

        millisPicker.setFormatter(value ->
                String.format(Locale.US, "%03d", value));

        addPickerColumn(pickersRow, "ساعت", hourPicker);
        addPickerColumn(pickersRow, "دقیقه", minutePicker);
        addPickerColumn(pickersRow, "ثانیه", secondPicker);
        addPickerColumn(pickersRow, "میلی‌ثانیه", millisPicker);
        root.addView(pickersRow);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(8), 0, 0);

        TextView cancelButton =
                createDialogButton("انصراف", Color.rgb(90, 90, 100));
        TextView confirmButton =
                createDialogButton("تأیید", Color.rgb(20, 125, 75));

        buttons.addView(cancelButton);
        buttons.addView(confirmButton);
        root.addView(buttons);

        dialog.setContentView(root);
        dialog.setCancelable(true);

        android.view.Window window = dialog.getWindow();

        if (window != null) {
            window.setType(getOverlayType());
            window.setBackgroundDrawableResource(
                    android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            WindowManager.LayoutParams params = window.getAttributes();
            params.width = dp(340);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            params.dimAmount = 0.35f;
            window.setAttributes(params);
        }

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        confirmButton.setOnClickListener(v -> {
            floatingStartHour = hourPicker.getValue();
            floatingStartMinute = minutePicker.getValue();
            floatingStartSecond = secondPicker.getValue();
            floatingStartMillis = millisPicker.getValue();

            floatingTimeConfigured = true;
            updateClockDisplay();
            updateStatus("زمان شروع انتخاب شد");
            dialog.dismiss();
        });

        try {
            dialog.show();

            if (window != null) {
                window.setLayout(
                        dp(340),
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            updateStatus("باز کردن انتخاب‌گر زمان ناموفق بود");
        }
    }

    private NumberPicker createNumberPicker(
            int min, int max, int value) {

        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        picker.setWrapSelectorWheel(true);
        picker.setDescendantFocusability(
                NumberPicker.FOCUS_AFTER_DESCENDANTS);
        picker.setBackgroundColor(Color.WHITE);

        picker.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(105)));

        try {
            Field field =
                    NumberPicker.class.getDeclaredField("mInputText");
            field.setAccessible(true);

            EditText input = (EditText) field.get(picker);

            if (input != null) {
                input.setTextColor(Color.BLACK);
                input.setBackgroundColor(Color.TRANSPARENT);
            }
        } catch (Exception ignored) {
        }

        try {
            Field field = NumberPicker.class.getDeclaredField(
                    "mSelectorWheelPaint");
            field.setAccessible(true);

            Paint paint = (Paint) field.get(picker);

            if (paint != null) paint.setColor(Color.BLACK);
        } catch (Exception ignored) {
        }

        return picker;
    }

    private void addPickerColumn(
            LinearLayout parent,
            String label,
            NumberPicker picker) {

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

        column.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));

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

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(38), 1f);

        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);

        return button;
    }

    private String formatFloatingStartTime() {
        return String.format(
                Locale.US,
                "%02d:%02d:%02d.%03d",
                floatingStartHour,
                floatingStartMinute,
                floatingStartSecond,
                floatingStartMillis);
    }

    // Clock and countdown display

    private void updateClockDisplay() {
        if (selectedTimeView == null) return;

        SimpleDateFormat format =
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

        format.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));

        long epoch = getCurrentClockEpochMs();

        String currentTime = epoch > 0
                ? format.format(new Date(epoch))
                : "در انتظار همگام‌سازی";

        String sourceLabel = CLOCK_INTERNET.equals(clockSource)
                ? (internetTimeAnchorAvailable
                    ? "ساعت اینترنتی ایران"
                    : "ساعت اینترنتی (بدون مرجع)")
                : "ساعت گوشی";

        String display = sourceLabel + ": " + currentTime;

        if (floatingTimeConfigured) {
            display += "\nزمان شروع: " + formatFloatingStartTime();
        }

        selectedTimeView.setText(display);
    }

    private void updateCountdownDisplay() {
        if (countdownView == null) return;

        if (runFinished) {
            countdownView.setText(String.format(
                    Locale.US,
                    "پایان اجرا | موفق: %d | منقضی: %d | خطا: %d",
                    completedClicks,
                    expiredClicks,
                    failedClicks));
            return;
        }

        if (!runDisplayActive) {
            countdownView.setText("آماده");
            return;
        }

        long remaining =
                runStartElapsedMs - SystemClock.elapsedRealtime();

        if (remaining > 0) {
            long hours = remaining / 3_600_000L;
            long minutes = (remaining % 3_600_000L) / 60_000L;
            long seconds = (remaining % 60_000L) / 1_000L;
            long millis = remaining % 1_000L;

            countdownView.setText(String.format(
                    Locale.US,
                    "شروع تا: %02d:%02d:%02d.%03d",
                    hours, minutes, seconds, millis));
            return;
        }

        long resultCount =
                (long) completedClicks + expiredClicks + failedClicks;

        countdownView.setText(String.format(
                Locale.US,
                "در حال اجرا | نتیجه: %d/%d",
                resultCount,
                totalClicks));
    }

    private void updateStatus(String message) {
        if (statusView != null) {
            statusView.setText(message);
        }
    }

    // Open MainActivity

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

    // Overlay visibility and cleanup

    public static void setOverlaysHidden(boolean hidden) {
        FloatingService service = instance;
        if (service == null) return;

        service.handler.post(() -> {
            int visibility = hidden ? View.INVISIBLE : View.VISIBLE;

            if (service.panel != null) {
                service.panel.setVisibility(visibility);
            }

            if (service.markerView != null) {
                service.markerView.setVisibility(visibility);
            }
        });
    }

    private void showFloatingViews() {
        try {
            if (panel != null) panel.setVisibility(View.VISIBLE);
            if (markerView != null) markerView.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
        }
    }

    private void updateViewLayout(
            View view,
            WindowManager.LayoutParams params) {

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
        drawable.setStroke(
                dp(1), Color.argb(100, 255, 255, 255));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private void shutdownService() {
        stopClicking();
        removeFloatingViews();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
