package com.precision.loopclicker;

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
import android.util.Log;
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

    private static final String TAG = "PrecisionLoop";

    private static final String CHANNEL_ID =
            "precision_loop_channel";

    private static final int NOTIFICATION_ID = 4101;

    private static final String PREF_NAME =
            "precision_loop_settings";

    private static final String PREF_CLOCK_SOURCE =
            "clock_source";

    private static final String PREF_INTERNET_EPOCH =
            "internet_epoch_at_sync_ms";

    private static final String PREF_INTERNET_ELAPSED =
            "internet_elapsed_at_sync_ms";

    private static final String CLOCK_PHONE = "phone";
    private static final String CLOCK_INTERNET = "internet";

    private static final String ACTION_CLOCK_SOURCE_CHANGED =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";

    private static final int PANEL_BACKGROUND =
            Color.rgb(20, 30, 42);

    private static final int TITLE_COLOR =
            Color.rgb(255, 152, 0);

    private static volatile FloatingService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private WindowManager windowManager;

    private LinearLayout panel;
    private TextView selectedTimeView;
    private TextView statusView;

    private WindowManager.LayoutParams panelParams;

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

    private Runnable displayRunnable;

    // ---------------------------------------------------------
    // Click scheduler
    // ---------------------------------------------------------

    private boolean clicking;

    private boolean gestureWaiting;

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

    private Runnable dispatchRunnable;

    private int completedClicks;
    private int expiredClicks;

    private final StringBuilder reportBuffer =
            new StringBuilder();

    // ---------------------------------------------------------
    // Clock source receiver
    // ---------------------------------------------------------

    private final BroadcastReceiver clockSourceReceiver =
            new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent == null
                    || !ACTION_CLOCK_SOURCE_CHANGED.equals(
                            intent.getAction())) {
                return;
            }

            String newSource =
                    intent.getStringExtra("clock_source");

            if (!CLOCK_PHONE.equals(newSource)
                    && !CLOCK_INTERNET.equals(newSource)) {
                return;
            }

            boolean hasInternetAnchor =
                    intent.hasExtra("internet_epoch_at_sync_ms")
                            && intent.hasExtra("internet_elapsed_at_sync_ms");

            if (hasInternetAnchor) {

                long newEpoch = intent.getLongExtra(
                        "internet_epoch_at_sync_ms",
                        0L
                );

                long newElapsed = intent.getLongExtra(
                        "internet_elapsed_at_sync_ms",
                        0L
                );

                long currentElapsed =
                        SystemClock.elapsedRealtime();

                boolean validAnchor =
                        newEpoch > 0
                                && newElapsed > 0
                                && newElapsed <= currentElapsed;

                if (validAnchor) {

                    internetEpochAtSyncMs = newEpoch;
                    internetElapsedAtSyncMs = newElapsed;
                    internetTimeAnchorAvailable = true;

                    saveInternetAnchor(newEpoch, newElapsed);
                }
            }

            clockSource = newSource;

            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_CLOCK_SOURCE, clockSource)
                    .apply();

            updateClockDisplay();
        }
    };

    public static FloatingService getInstance() {
        return instance;
    }

    // ---------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

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

        if (ACTION_SHUTDOWN.equals(action)) {

            shutdownService();

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

    // ---------------------------------------------------------
    // Click scheduling
    // ---------------------------------------------------------

    private void startClicking(Intent intent) {

        stopClicking();

        int newGeneration = ++runGeneration;

        long now = SystemClock.elapsedRealtime();

        long requestedStart = intent.getLongExtra(
                "start_elapsed_realtime_ms",
                0L
        );

        if (requestedStart <= 0) {

            long delay = intent.getLongExtra(
                    "start_delay_ms",
                    0L
            );

            requestedStart = now + Math.max(0L, delay);
        }

        int requestedClicks = intent.getIntExtra(
                "clicks_per_cycle",
                intent.getIntExtra("click_count", 1)
        );

        int requestedCycles = intent.getIntExtra(
                "cycle_count",
                1
        );

        long requestedCycleDuration = intent.getLongExtra(
                "cycle_duration_ms",
                0L
        );

        long requestedInterval = intent.getLongExtra(
                "click_interval_ms",
                50L
        );

        long requestedTouchDuration = intent.getLongExtra(
                "touch_duration_ms",
                1L
        );

        long requestedCompensation = intent.getLongExtra(
                "compensation_ms",
                intent.getLongExtra("dispatch_lead_ms", 50L)
        );

        if (requestedClicks < 1
                || requestedCycles < 1
                || requestedInterval < 0
                || requestedCompensation < 0) {

            updateStatus("تنظیمات اجرای کلیک نامعتبر است");
            return;
        }

        if (requestedCycleDuration <= 0) {

            long lastOffset;

            try {
                lastOffset = Math.multiplyExact(
                        requestedClicks - 1L,
                        requestedInterval
                );
            } catch (ArithmeticException e) {
                updateStatus("مدت چرخه بیش از حد بزرگ است");
                return;
            }

            requestedCycleDuration = lastOffset + 1L;
        }

        long lastClickOffset;

        try {
            lastClickOffset = Math.multiplyExact(
                    requestedClicks - 1L,
                    requestedInterval
            );
        } catch (ArithmeticException e) {
            updateStatus("فاصله کلیک‌ها نامعتبر است");
            return;
        }

        if (lastClickOffset >= requestedCycleDuration) {
            updateStatus(
                    "آخرین کلیک خارج از محدوده چرخه قرار می‌گیرد"
            );
            return;
        }

        try {

            Math.multiplyExact(
                    requestedCycles,
                    requestedCycleDuration
            );

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

        cycleIndex = 0;
        clickIndex = 0;

        completedClicks = 0;
        expiredClicks = 0;

        gestureWaiting = false;

        reportBuffer.setLength(0);

        clicking = true;

        updateStatus("زمان‌بندی کلیک‌ها آغاز شد");

        Log.i(TAG,
                "START cycles=" + cycleCount
                        + " clicksPerCycle=" + clicksPerCycle
                        + " cycleMs=" + cycleDurationMs
                        + " intervalMs=" + clickIntervalMs
                        + " compensationMs=" + compensationMs
                        + " touchMs=" + touchDurationMs
        );

        scheduleNextClick(newGeneration);
    }

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

            scheduleNextClick(generation);
            return;
        }

        long cycleStart;
        long targetElapsed;
        long dispatchElapsed;
        long cycleEnd;

        try {

            cycleStart = Math.addExact(
                    runStartElapsedMs,
                    Math.multiplyExact(
                            cycleIndex,
                            cycleDurationMs
                    )
            );

            targetElapsed = Math.addExact(
                    cycleStart,
                    Math.multiplyExact(
                            clickIndex,
                            clickIntervalMs
                    )
            );

            dispatchElapsed =
                    targetElapsed - compensationMs;

            cycleEnd = Math.addExact(
                    cycleStart,
                    cycleDurationMs
            );

        } catch (ArithmeticException e) {

            updateStatus("خطای محاسبات زمان‌بندی");
            stopClicking();
            return;
        }

        final int currentCycle = cycleIndex;
        final int currentClick = clickIndex;

        final long currentTarget = targetElapsed;
        final long currentDispatchTarget = dispatchElapsed;
        final long currentCycleEnd = cycleEnd;

        long now = SystemClock.elapsedRealtime();

        if (now >= currentCycleEnd) {

            reportExpired(
                    currentCycle + 1,
                    currentClick + 1,
                    currentTarget
            );

            clickIndex++;

            scheduleNextClick(generation);
            return;
        }

        long delay = Math.max(
                0L,
                currentDispatchTarget - now
        );

        dispatchRunnable = () -> {

            dispatchRunnable = null;

            if (!clicking || generation != runGeneration) {
                return;
            }

            long actualNow =
                    SystemClock.elapsedRealtime();

            if (actualNow >= currentCycleEnd) {

                reportExpired(
                        currentCycle + 1,
                        currentClick + 1,
                        currentTarget
                );

                clickIndex++;

                scheduleNextClick(generation);
                return;
            }

            dispatchClick(
                    generation,
                    currentCycle,
                    currentClick,
                    currentTarget,
                    currentCycleEnd
            );
        };

        handler.postDelayed(dispatchRunnable, delay);
    }

    private void dispatchClick(
            int generation,
            int cycle,
            int click,
            long targetElapsed,
            long cycleEnd
    ) {

        if (!clicking || generation != runGeneration) {
            return;
        }

        if (gestureWaiting) {
            return;
        }

        AutoClickService service =
                AutoClickService.getInstance();

        if (service == null) {

            updateStatus(
                    "سرویس دسترس‌پذیری فعال نیست"
            );

            Log.e(TAG, "AutoClickService is null");

            stopClicking();
            return;
        }

        long dispatchTime =
                SystemClock.elapsedRealtime();

        if (dispatchTime >= cycleEnd) {

            reportExpired(
                    cycle + 1,
                    click + 1,
                    targetElapsed
            );

            clickIndex++;

            scheduleNextClick(generation);
            return;
        }

        long targetOffset =
                targetElapsed - runStartElapsedMs;

        long actualOffset =
                dispatchTime - runStartElapsedMs;

        long difference =
                dispatchTime - targetElapsed;

        String report = String.format(
                Locale.US,
                "چرخه %d | کلیک %d | هدف %dms | ارسال %dms | اختلاف %+dms",
                cycle + 1,
                click + 1,
                targetOffset,
                actualOffset,
                difference
        );

        addReport(report);

        gestureWaiting = true;

        clickIndex++;

        try {

            service.performClickAtTarget(
                    SystemClock.elapsedRealtimeNanos(),
                    () -> {

                        if (generation != runGeneration) {
                            return;
                        }

                        gestureWaiting = false;

                        completedClicks++;

                        Log.i(
                                TAG,
                                "GESTURE COMPLETED cycle="
                                        + (cycle + 1)
                                        + " click="
                                        + (click + 1)
                        );

                        scheduleNextClick(generation);
                    }
            );

        } catch (Exception e) {

            gestureWaiting = false;

            Log.e(TAG, "Click dispatch error", e);

            addReport("خطا در ارسال کلیک");

            stopClicking();
        }
    }

    private void reportExpired(
            int cycle,
            int click,
            long targetElapsed
    ) {

        expiredClicks++;

        long targetOffset =
                targetElapsed - runStartElapsedMs;

        String report = String.format(
                Locale.US,
                "منقضی: چرخه %d | کلیک %d | هدف %dms",
                cycle,
                click,
                targetOffset
        );

        addReport(report);

        Log.w(TAG, report);
    }

    private void addReport(String report) {

        Log.i(TAG, report);

        reportBuffer.append(report)
                .append('\n');

        if (reportBuffer.length() > 2500) {
            reportBuffer.delete(
                    0,
                    reportBuffer.length() - 1800
            );
        }

        updateStatus(report);
    }

    private void finishClicking() {

        clicking = false;
        gestureWaiting = false;

        updateStatus(
                "پایان اجرا | موفق: "
                        + completedClicks
                        + " | منقضی: "
                        + expiredClicks
        );

        Log.i(
                TAG,
                "FINISHED completed="
                        + completedClicks
                        + " expired="
                        + expiredClicks
        );
    }

    private void stopClicking() {

        clicking = false;

        runGeneration++;

        if (dispatchRunnable != null) {

            handler.removeCallbacks(dispatchRunnable);
            dispatchRunnable = null;
        }

        gestureWaiting = false;

        updateStatus("اجرای کلیک‌ها متوقف شد");

        Log.i(TAG, "STOP");
    }

    // ---------------------------------------------------------
    // Clock source
    // ---------------------------------------------------------

    private void loadSavedClockSource() {

        try {

            android.content.SharedPreferences preferences =
                    getSharedPreferences(
                            PREF_NAME,
                            MODE_PRIVATE
                    );

            String savedSource = preferences.getString(
                    PREF_CLOCK_SOURCE,
                    CLOCK_PHONE
            );

            clockSource = CLOCK_INTERNET.equals(savedSource)
                    ? CLOCK_INTERNET
                    : CLOCK_PHONE;

            internetEpochAtSyncMs = preferences.getLong(
                    PREF_INTERNET_EPOCH,
                    0L
            );

            internetElapsedAtSyncMs = preferences.getLong(
                    PREF_INTERNET_ELAPSED,
                    0L
            );

            long currentElapsed =
                    SystemClock.elapsedRealtime();

            internetTimeAnchorAvailable =
                    internetEpochAtSyncMs > 0
                            && internetElapsedAtSyncMs > 0
                            && internetElapsedAtSyncMs <= currentElapsed;

        } catch (Exception ignored) {

            clockSource = CLOCK_PHONE;
            internetEpochAtSyncMs = 0L;
            internetElapsedAtSyncMs = 0L;
            internetTimeAnchorAvailable = false;
        }
    }

    private void saveInternetAnchor(
            long epoch,
            long elapsed
    ) {

        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PREF_INTERNET_EPOCH, epoch)
                .putLong(PREF_INTERNET_ELAPSED, elapsed)
                .apply();
    }

    private void registerClockSourceReceiver() {

        if (clockSourceReceiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter(
                ACTION_CLOCK_SOURCE_CHANGED
        );

        try {

            if (Build.VERSION.SDK_INT >= 33) {

                registerReceiver(
                        clockSourceReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );

            } else {

                registerReceiver(
                        clockSourceReceiver,
                        filter
                );
            }

            clockSourceReceiverRegistered = true;

        } catch (Exception ignored) {
            clockSourceReceiverRegistered = false;
        }
    }

    private void unregisterClockSourceReceiver() {

        if (!clockSourceReceiverRegistered) {
            return;
        }

        try {
            unregisterReceiver(clockSourceReceiver);
        } catch (Exception ignored) {
        }

        clockSourceReceiverRegistered = false;
    }

    private long getCurrentClockEpochMs() {

        if (CLOCK_INTERNET.equals(clockSource)) {

            if (!internetTimeAnchorAvailable) {
                return 0L;
            }

            long elapsedNow =
                    SystemClock.elapsedRealtime();

            long difference =
                    elapsedNow - internetElapsedAtSyncMs;

            if (difference < 0
                    || internetEpochAtSyncMs <= 0
                    || internetEpochAtSyncMs
                    > Long.MAX_VALUE - difference) {
                return 0L;
            }

            return internetEpochAtSyncMs + difference;
        }

        return System.currentTimeMillis();
    }

    // ---------------------------------------------------------
    // Foreground service
    // ---------------------------------------------------------

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Precision Loop Auto Clicker",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription("سرویس تنظیم زمان");

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
                .setContentText("سرویس تنظیم زمان فعال است")
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
    // Floating panel
    // ---------------------------------------------------------

    private void createFloatingViews() {

        if (!Settings.canDrawOverlays(this)) {
            return;
        }

        if (windowManager == null) {
            windowManager = (WindowManager)
                    getSystemService(WINDOW_SERVICE);
        }

        if (panel == null) {
            createPanel();
        }

        showFloatingViews();
        updateClockDisplay();
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
        panel.setPadding(dp(8), dp(7), dp(8), dp(7));

        panel.setBackground(
                makeBackground(PANEL_BACKGROUND, dp(11))
        );

        panel.setClickable(true);

        createTimeSection();

        LinearLayout buttonsRow =
                new LinearLayout(this);

        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER);
        buttonsRow.setPadding(0, dp(5), 0, 0);

        TextView openButton =
                createPanelButton(
                        "تنظیمات",
                        Color.rgb(20, 125, 75)
                );

        TextView timeButton =
                createTimePickerButton();

        TextView shutdownButton =
                createPanelButton(
                        "خاموش",
                        Color.rgb(75, 75, 85)
                );

        openButton.setOnClickListener(v -> openMainForStart());

        timeButton.setOnClickListener(v -> showStartTimePicker());

        shutdownButton.setOnClickListener(v -> shutdownService());

        buttonsRow.addView(openButton);
        buttonsRow.addView(timeButton);
        buttonsRow.addView(shutdownButton);

        panel.addView(buttonsRow);

        panel.setOnTouchListener((v, event) -> {

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
                            + (int) (event.getRawX() - panelTouchRawX);

                    panelParams.y = panelStartY
                            + (int) (event.getRawY() - panelTouchRawY);

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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

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

    private void createTimeSection() {

        LinearLayout container =
                new LinearLayout(this);

        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(7), dp(6), dp(7), dp(6));

        container.setBackground(
                makeBackground(Color.rgb(13, 25, 36), dp(8))
        );

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

        statusView = new TextView(this);

        statusView.setText("آماده");
        statusView.setTextColor(Color.rgb(180, 190, 205));
        statusView.setTextSize(10);
        statusView.setGravity(Gravity.CENTER);

        container.addView(heading);
        container.addView(selectedTimeView);
        container.addView(statusView);

        panel.addView(container);
    }

    private TextView createPanelButton(
            String text,
            int backgroundColor
    ) {

        TextView button = new TextView(this);

        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), dp(5), dp(5), dp(5));

        button.setBackground(
                makeBackground(backgroundColor, dp(7))
        );

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
                makeBackground(Color.rgb(55, 85, 110), dp(7))
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(38), dp(34));

        params.setMargins(dp(2), 0, dp(2), 0);

        button.setLayoutParams(params);
        button.setClickable(true);

        return button;
    }

    // ---------------------------------------------------------
    // Start time picker
    // ---------------------------------------------------------

    private void showStartTimePicker() {

        if (!Settings.canDrawOverlays(this)) {
            updateStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (CLOCK_INTERNET.equals(clockSource)
                && !internetTimeAnchorAvailable) {

            updateStatus(
                    "مرجع ساعت اینترنتی هنوز دریافت نشده است"
            );

            return;
        }

        if (!floatingTimeConfigured) {

            long currentEpoch = getCurrentClockEpochMs();

            if (currentEpoch <= 0) {
                updateStatus("زمان مرجع ساعت در دسترس نیست");
                return;
            }

            Calendar calendar = Calendar.getInstance(
                    TimeZone.getTimeZone("Asia/Tehran")
            );

            calendar.setTimeInMillis(currentEpoch);

            floatingStartHour =
                    calendar.get(Calendar.HOUR_OF_DAY);

            floatingStartMinute =
                    calendar.get(Calendar.MINUTE);

            floatingStartSecond =
                    calendar.get(Calendar.SECOND);

            floatingStartMillis =
                    calendar.get(Calendar.MILLISECOND);
        }

        Dialog dialog = new Dialog(
                this,
                android.R.style.Theme_Material_Light_Dialog_Alert
        );

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(10));

        root.setBackground(
                makeBackground(Color.WHITE, dp(12))
        );

        TextView title = new TextView(this);

        title.setText("تنظیم زمان شروع");
        title.setTextColor(TITLE_COLOR);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));

        root.addView(title);

        LinearLayout pickersRow =
                new LinearLayout(this);

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
                String.format(Locale.US, "%03d", value)
        );

        addPickerColumn(pickersRow, "ساعت", hourPicker);
        addPickerColumn(pickersRow, "دقیقه", minutePicker);
        addPickerColumn(pickersRow, "ثانیه", secondPicker);
        addPickerColumn(pickersRow, "میلی‌ثانیه", millisPicker);

        root.addView(pickersRow);

        LinearLayout buttons =
                new LinearLayout(this);

        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(8), 0, 0);

        TextView cancelButton =
                createDialogButton(
                        "انصراف",
                        Color.rgb(90, 90, 100)
                );

        TextView confirmButton =
                createDialogButton(
                        "تأیید",
                        Color.rgb(20, 125, 75)
                );

        buttons.addView(cancelButton);
        buttons.addView(confirmButton);

        root.addView(buttons);

        dialog.setContentView(root);
        dialog.setCancelable(true);

        WindowManager.LayoutParams params;

        android.view.Window window = dialog.getWindow();

        if (window != null) {

            window.setType(getOverlayType());

            window.setBackgroundDrawableResource(
                    android.R.color.transparent
            );

            window.addFlags(
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
            );

            params = window.getAttributes();

            params.width = dp(340);
            params.height =
                    WindowManager.LayoutParams.WRAP_CONTENT;

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
                        WindowManager.LayoutParams.WRAP_CONTENT
                );
            }

        } catch (Exception e) {

            updateStatus("باز کردن انتخاب‌گر زمان ناموفق بود");
        }
    }

    private NumberPicker createNumberPicker(
            int min,
            int max,
            int value
    ) {

        NumberPicker picker = new NumberPicker(this);

        picker.setMinValue(min);
        picker.setMaxValue(max);

        picker.setValue(
                Math.max(min, Math.min(max, value))
        );

        picker.setWrapSelectorWheel(true);

        picker.setDescendantFocusability(
                NumberPicker.FOCUS_AFTER_DESCENDANTS
        );

        picker.setBackgroundColor(Color.WHITE);

        picker.setLayoutParams(
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(105)
                )
        );

        try {

            Field inputField =
                    NumberPicker.class.getDeclaredField("mInputText");

            inputField.setAccessible(true);

            EditText input =
                    (EditText) inputField.get(picker);

            if (input != null) {
                input.setTextColor(Color.BLACK);
                input.setBackgroundColor(Color.TRANSPARENT);
            }

        } catch (Exception ignored) {
        }

        try {

            Field paintField =
                    NumberPicker.class.getDeclaredField(
                            "mSelectorWheelPaint"
                    );

            paintField.setAccessible(true);

            Paint paint = (Paint) paintField.get(picker);

            if (paint != null) {
                paint.setColor(Color.BLACK);
            }

        } catch (Exception ignored) {
        }

        return picker;
    }

    private void addPickerColumn(
            LinearLayout parent,
            String label,
            NumberPicker picker
    ) {

        LinearLayout column =
                new LinearLayout(this);

        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);

        title.setText(label);
        title.setTextColor(Color.BLACK);
        title.setTextSize(10);
        title.setGravity(Gravity.CENTER);

        column.addView(title);
        column.addView(picker);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                );

        column.setLayoutParams(params);

        parent.addView(column);
    }

    private TextView createDialogButton(
            String text,
            int color
    ) {

        TextView button = new TextView(this);

        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);

        button.setPadding(
                dp(18),
                dp(8),
                dp(18),
                dp(8)
        );

        button.setBackground(
                makeBackground(color, dp(7))
        );

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
                floatingStartMillis
        );
    }

    // ---------------------------------------------------------
    // Clock display
    // ---------------------------------------------------------

    private void updateClockDisplay() {

        if (selectedTimeView == null) {
            return;
        }

        SimpleDateFormat format =
                new SimpleDateFormat(
                        "HH:mm:ss.SSS",
                        Locale.US
                );

        format.setTimeZone(
                TimeZone.getTimeZone("Asia/Tehran")
        );

        long currentEpoch = getCurrentClockEpochMs();

        String currentTime;

        if (currentEpoch > 0) {

            currentTime = format.format(
                    new Date(currentEpoch)
            );

        } else {

            currentTime = "در انتظار همگام‌سازی";
        }

        String sourceLabel;

        if (CLOCK_INTERNET.equals(clockSource)) {

            sourceLabel = internetTimeAnchorAvailable
                    ? "ساعت اینترنتی ایران"
                    : "ساعت اینترنتی (بدون مرجع)";

        } else {

            sourceLabel = "ساعت گوشی";
        }

        String displayText =
                sourceLabel + ": " + currentTime;

        if (floatingTimeConfigured) {

            displayText += "\nزمان شروع: "
                    + formatFloatingStartTime();
        }

        selectedTimeView.setText(displayText);
    }

    private void updateStatus(String message) {

        if (statusView != null) {
            statusView.setText(message);
        }
    }

    // ---------------------------------------------------------
    // Open MainActivity
    // ---------------------------------------------------------

    private void openMainForStart() {

        Intent intent =
                new Intent(this, MainActivity.class);

        intent.setAction(ACTION_START_FROM_FLOATING);

        intent.putExtra("clock_source", clockSource);

        if (floatingTimeConfigured) {

            intent.putExtra(
                    "floating_start_time_configured",
                    true
            );

            intent.putExtra(
                    "floating_start_hour",
                    floatingStartHour
            );

            intent.putExtra(
                    "floating_start_minute",
                    floatingStartMinute
            );

            intent.putExtra(
                    "floating_start_second",
                    floatingStartSecond
            );

            intent.putExtra(
                    "floating_start_millis",
                    floatingStartMillis
            );
        }

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        try {

            startActivity(intent);

        } catch (Exception e) {

            updateStatus("باز کردن صفحه اصلی ناموفق بود");
        }
    }

    // ---------------------------------------------------------
    // Overlay visibility and cleanup
    // ---------------------------------------------------------

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
        });
    }

    private void showFloatingViews() {

        try {

            if (panel != null) {
                panel.setVisibility(View.VISIBLE);
            }

        } catch (Exception ignored) {
        }
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

    private int dp(float value) {

        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
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

        panel = null;
        panelParams = null;

        selectedTimeView = null;
        statusView = null;
    }

    @Override
    public void onDestroy() {

        stopClicking();

        if (displayRunnable != null) {
            handler.removeCallbacks(displayRunnable);
            displayRunnable = null;
        }

        unregisterClockSourceReceiver();

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