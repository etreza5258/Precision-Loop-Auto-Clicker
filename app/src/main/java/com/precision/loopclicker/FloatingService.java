package com.precision.loopclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FloatingService extends Service {

    private static final String CHANNEL_ID = "precision_loop_channel";

    private static final String ACTION_START =
            "com.precision.loopclicker.START";

    private static final String ACTION_STOP =
            "com.precision.loopclicker.STOP";

    private static final String ACTION_SHUTDOWN =
            "com.precision.loopclicker.SHUTDOWN";

    private static final long MAX_HANDLER_DELAY = 60_000L;

    private static FloatingService instance;

    private WindowManager windowManager;

    private View panelView;
    private TextView clockView;
    private TextView countdownView;
    private TextView statusView;
    private TextView markerView;

    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams markerParams;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean panelAdded = false;
    private boolean markerAdded = false;
    private boolean running = false;
    private boolean cycleActive = false;
    private boolean overlaysHidden = false;

    private long cycleDurationMs = 1000;
    private long pauseDurationMs = 0;
    private long touchDurationMs = 5;

    private int clickCount = 20;
    private int currentClick = 0;

    private long cycleNumber = 0;
    private long cycleStartElapsed = 0;
    private long cycleStartWall = 0;
    private long phaseDeadline = 0;

    private long successAtStart = 0;
    private long failedAtStart = 0;

    private int runGeneration = 0;

    private float targetX = 500;
    private float targetY = 500;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private final Runnable displayUpdater = new Runnable() {
        @Override
        public void run() {
            updateDisplay();
            handler.postDelayed(this, 50);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        startForegroundSafely();

        handler.post(displayUpdater);
    }

    public static void setOverlaysHidden(boolean hidden) {
        if (instance != null) {
            instance.changeOverlayVisibility(hidden);
        }
    }

    private void changeOverlayVisibility(boolean hidden) {

        if (overlaysHidden == hidden) {
            return;
        }

        overlaysHidden = hidden;

        if (panelView != null && panelAdded && panelParams != null) {
            try {
                if (hidden) {
                    panelParams.flags |=
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    panelView.setVisibility(View.INVISIBLE);
                } else {
                    panelParams.flags &=
                            ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    panelView.setVisibility(View.VISIBLE);
                }

                windowManager.updateViewLayout(panelView, panelParams);

            } catch (Exception ignored) {
            }
        }

        if (markerView != null && markerAdded && markerParams != null) {
            try {
                if (hidden) {
                    markerParams.flags |=
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    markerView.setVisibility(View.INVISIBLE);
                } else {
                    markerParams.flags &=
                            ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    markerView.setVisibility(View.VISIBLE);
                }

                windowManager.updateViewLayout(markerView, markerParams);

            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null) {

            String action = intent.getAction();

            if (ACTION_START.equals(action)) {

                cycleDurationMs = intent.getLongExtra(
                        "cycle_duration", 1000);

                clickCount = intent.getIntExtra(
                        "click_count", 20);

                touchDurationMs = intent.getLongExtra(
                        "touch_duration", 5);

                pauseDurationMs = intent.getLongExtra(
                        "pause_duration", 0);

                createFloatingViews();
                startLoop();

            } else if (ACTION_STOP.equals(action)) {

                stopLoop();

            } else if (ACTION_SHUTDOWN.equals(action)) {

                stopLoop();
                removeFloatingViews();
                stopForeground(true);
                stopSelf();

                return START_NOT_STICKY;

            } else {
                createFloatingViews();
            }

        } else {
            createFloatingViews();
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Precision Loop Service",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "Floating auto clicker controls");

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundSafely() {

        Notification notification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Precision Loop Auto Clicker")
                    .setContentText("سرویس آماده اجرا است")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();

        } else {

            notification = new Notification.Builder(this)
                    .setContentTitle("Precision Loop Auto Clicker")
                    .setContentText("سرویس آماده اجرا است")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();
        }

        if (Build.VERSION.SDK_INT >= 34) {

            startForeground(
                    1,
                    notification,
                    android.content.pm.ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );

        } else {
            startForeground(1, notification);
        }
    }

    private GradientDrawable roundedBackground(int color) {

        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(18);

        return drawable;
    }

    private TextView createText(String value, int size) {

        TextView text = new TextView(this);

        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);
        text.setPadding(8, 8, 8, 8);

        return text;
    }

    private Button createButton(String title) {

        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(12);

        return button;
    }

    private void createFloatingViews() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || !android.provider.Settings.canDrawOverlays(this)) {
            return;
        }

        if (panelAdded && markerAdded) {
            return;
        }

        createPanel();
        createMarker();
    }

    private void createPanel() {

        if (panelAdded) return;

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(10, 8, 10, 8);
        root.setBackground(roundedBackground(Color.rgb(25, 35, 48)));

        TextView header = createText("PRECISION LOOP  ⋮", 14);

        header.setBackground(
                roundedBackground(Color.rgb(13, 71, 161)));

        clockView = createText("ساعت: --:--:--.---", 14);

        countdownView = createText(
                "شمارش معکوس: آماده", 14);

        statusView = createText("وضعیت: آماده", 12);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button homeButton = createButton("خانه");
        Button stopButton = createButton("توقف");
        Button powerButton = createButton("خاموش");

        controls.addView(homeButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        controls.addView(stopButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        controls.addView(powerButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(header);
        root.addView(clockView);
        root.addView(countdownView);
        root.addView(statusView);
        root.addView(controls);

        panelView = root;

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        panelParams.gravity = Gravity.TOP | Gravity.LEFT;
        panelParams.x = 10;
        panelParams.y = 100;

        try {
            windowManager.addView(panelView, panelParams);
            panelAdded = true;

        } catch (Exception e) {
            panelAdded = false;
            panelView = null;
            return;
        }

        homeButton.setOnClickListener(v -> {

            Intent intent = new Intent(
                    FloatingService.this,
                    MainActivity.class
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            startActivity(intent);
        });

        stopButton.setOnClickListener(v -> stopLoop());

        powerButton.setOnClickListener(v -> {

            stopLoop();
            removeFloatingViews();
            stopForeground(true);
            stopSelf();
        });

        header.setOnTouchListener(
                createDragListener(panelParams, panelView));
    }

    private void createMarker() {

        if (markerAdded) return;

        markerView = createText("⊕", 36);

        markerView.setTextColor(Color.RED);
        markerView.setBackground(
                roundedBackground(Color.argb(70, 255, 255, 255)));

        markerParams = new WindowManager.LayoutParams(
                58,
                58,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        markerParams.gravity = Gravity.TOP | Gravity.LEFT;
        markerParams.x = 350;
        markerParams.y = 700;

        try {
            windowManager.addView(markerView, markerParams);
            markerAdded = true;

            updateTargetPosition();

        } catch (Exception e) {
            markerAdded = false;
            markerView = null;
            return;
        }

        markerView.setOnTouchListener(createMarkerDragListener());
    }

    private View.OnTouchListener createDragListener(
            WindowManager.LayoutParams params,
            View view) {

        return new View.OnTouchListener() {

            private int initialX;
            private int initialY;

            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:

                        initialX = params.x;
                        initialY = params.y;

                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();

                        return true;

                    case MotionEvent.ACTION_MOVE:

                        params.x = initialX
                                + (int) (event.getRawX() - initialTouchX);

                        params.y = initialY
                                + (int) (event.getRawY() - initialTouchY);

                        try {
                            windowManager.updateViewLayout(view, params);
                        } catch (Exception ignored) {
                        }

                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }

                return false;
            }
        };
    }

    private View.OnTouchListener createMarkerDragListener() {

        return new View.OnTouchListener() {

            private int initialX;
            private int initialY;

            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:

                        initialX = markerParams.x;
                        initialY = markerParams.y;

                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();

                        return true;

                    case MotionEvent.ACTION_MOVE:

                        markerParams.x = initialX
                                + (int) (event.getRawX() - initialTouchX);

                        markerParams.y = initialY
                                + (int) (event.getRawY() - initialTouchY);

                        try {
                            windowManager.updateViewLayout(
                                    markerView, markerParams);
                        } catch (Exception ignored) {
                        }

                        updateTargetPosition();

                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }

                return false;
            }
        };
    }

    private void updateTargetPosition() {

        if (markerParams == null || markerView == null) return;

        targetX = markerParams.x + markerParams.width / 2f;
        targetY = markerParams.y + markerParams.height / 2f;

        AutoClickService service = AutoClickService.getInstance();

        if (service != null) {
            service.setTarget(
                    Math.round(targetX),
                    Math.round(targetY));
        }
    }

    private void startLoop() {

        stopLoop();

        if (cycleDurationMs <= 0
                || clickCount <= 0
                || touchDurationMs <= 0
                || pauseDurationMs < 0) {

            updateStatus("تنظیمات نامعتبر است");
            return;
        }

        AutoClickService accessibility =
                AutoClickService.getInstance();

        if (accessibility == null) {
            updateStatus("ابتدا دسترس‌پذیری را فعال کنید");
            return;
        }

        if (touchDurationMs > cycleDurationMs / clickCount) {
            updateStatus("زمان لمس‌ها برای این چرخه زیاد است");
            return;
        }

        running = true;
        cycleNumber = 0;

        runGeneration++;

        startCycle(runGeneration);
    }

    private long safeAdd(long first, long second) {

        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }

        return first + second;
    }

    private void startCycle(int generation) {

        if (!running || generation != runGeneration) return;

        AutoClickService accessibility =
                AutoClickService.getInstance();

        if (accessibility == null) {
            stopLoop();
            updateStatus("سرویس دسترس‌پذیری قطع شده است");
            return;
        }

        cycleNumber++;
        currentClick = 0;
        cycleActive = true;

        cycleStartElapsed = SystemClock.elapsedRealtime();
        cycleStartWall = System.currentTimeMillis();

        phaseDeadline = safeAdd(
                cycleStartElapsed,
                cycleDurationMs);

        accessibility.setTouchDuration(touchDurationMs);

        accessibility.setTarget(
                Math.round(targetX),
                Math.round(targetY));

        accessibility.resetClickCounters();

        successAtStart = accessibility.getSuccessfulClicks();
        failedAtStart = accessibility.getFailedClicks();

        updateStatus("اجرای چرخه " + cycleNumber);

        scheduleNextClick(generation);
    }

    private void scheduleNextClick(int generation) {

        if (!running || generation != runGeneration) return;
        if (!cycleActive) return;

        long now = SystemClock.elapsedRealtime();

        // بعد از مهلت چرخه، لمس جدیدی آغاز نمی‌شود.
        if (now >= phaseDeadline) {
            finishCycle(generation);
            return;
        }

        if (currentClick >= clickCount) {

            scheduleFinishAtDeadline(generation);
            return;
        }

        long targetOffset =
                (cycleDurationMs / clickCount) * currentClick
                        + ((cycleDurationMs % clickCount)
                        * currentClick) / clickCount;

        long targetTime = safeAdd(cycleStartElapsed, targetOffset);

        long delay = Math.max(0, targetTime - now);

        handler.postDelayed(() -> {

            if (!running || generation != runGeneration) return;
            if (!cycleActive) return;

            executeCurrentClick(generation);

        }, delay);
    }

    private void scheduleFinishAtDeadline(int generation) {

        if (!running || generation != runGeneration) return;
        if (!cycleActive) return;

        long remaining = Math.max(
                0,
                phaseDeadline - SystemClock.elapsedRealtime());

        long delay = Math.min(remaining, MAX_HANDLER_DELAY);

        handler.postDelayed(() -> {

            if (!running || generation != runGeneration) return;
            if (!cycleActive) return;

            if (SystemClock.elapsedRealtime() < phaseDeadline) {
                scheduleFinishAtDeadline(generation);
            } else {
                finishCycle(generation);
            }

        }, delay);
    }

    private void executeCurrentClick(int generation) {

        if (!running || generation != runGeneration) return;
        if (!cycleActive) return;

        if (SystemClock.elapsedRealtime() >= phaseDeadline) {
            finishCycle(generation);
            return;
        }

        AutoClickService accessibility =
                AutoClickService.getInstance();

        if (accessibility == null) {
            stopLoop();
            updateStatus("سرویس دسترس‌پذیری قطع شده است");
            return;
        }

        if (accessibility.isGestureInProgress()) {

            handler.postDelayed(
                    () -> executeCurrentClick(generation),
                    1
            );

            return;
        }

        currentClick++;

        accessibility.setTarget(
                Math.round(targetX),
                Math.round(targetY));

        accessibility.setTouchDuration(touchDurationMs);

        accessibility.performClick(() -> {

            if (!running || generation != runGeneration) return;
            if (!cycleActive) return;

            // اگر لمس از مهلت چرخه عبور کرده باشد،
            // لمس دیگری شروع نمی‌شود.
            scheduleNextClick(generation);
        });
    }

    private void finishCycle(int generation) {

        if (!running || generation != runGeneration) return;
        if (!cycleActive) return;

        AutoClickService accessibility =
                AutoClickService.getInstance();

        // تا پایان لمس جاری صبر می‌کنیم.
        if (accessibility != null
                && accessibility.isGestureInProgress()) {

            handler.postDelayed(
                    () -> finishCycle(generation),
                    1
            );

            return;
        }

        cycleActive = false;

        long actualDuration =
                SystemClock.elapsedRealtime() - cycleStartElapsed;

        long endWall = System.currentTimeMillis();

        long successful = 0;
        long failed = 0;

        if (accessibility != null) {

            successful = Math.max(
                    0,
                    accessibility.getSuccessfulClicks() - successAtStart);

            failed = Math.max(
                    0,
                    accessibility.getFailedClicks() - failedAtStart);
        }

        String start = timeFormat.format(new Date(cycleStartWall));
        String end = timeFormat.format(new Date(endWall));

        CycleReportStore.addReport(
                getApplicationContext(),
                cycleNumber,
                start,
                end,
                actualDuration,
                (int) Math.min(Integer.MAX_VALUE, successful),
                (int) Math.min(Integer.MAX_VALUE, failed),
                pauseDurationMs
        );

        phaseDeadline = safeAdd(
                SystemClock.elapsedRealtime(),
                pauseDurationMs);

        updateStatus("پایان چرخه " + cycleNumber);

        scheduleNextCycle(generation);
    }

    private void scheduleNextCycle(int generation) {

        if (!running || generation != runGeneration) return;

        long remaining = Math.max(
                0,
                phaseDeadline - SystemClock.elapsedRealtime());

        long delay = Math.min(remaining, MAX_HANDLER_DELAY);

        handler.postDelayed(() -> {

            if (!running || generation != runGeneration) return;

            if (SystemClock.elapsedRealtime() < phaseDeadline) {
                scheduleNextCycle(generation);
            } else {
                startCycle(generation);
            }

        }, delay);
    }

    private void stopLoop() {

        running = false;
        cycleActive = false;

        runGeneration++;

        updateStatus("متوقف شده");

        countdownViewSafe("شمارش معکوس: متوقف");
    }

    private void updateStatus(String text) {

        if (statusView != null) {
            statusView.setText("وضعیت: " + text);
        }
    }

    private void countdownViewSafe(String text) {

        if (countdownView != null) {
            countdownView.setText(text);
        }
    }

    private void updateDisplay() {

        if (clockView != null) {
            clockView.setText(
                    "ساعت: " + timeFormat.format(new Date()));
        }

        if (!running || countdownView == null) return;

        long remaining = Math.max(
                0,
                phaseDeadline - SystemClock.elapsedRealtime());

        String phase = cycleActive
                ? "پایان چرخه"
                : "شروع چرخه بعدی";

        countdownView.setText(
                phase + ": " + remaining + " ms");
    }

    private void removeFloatingViews() {

        changeOverlayVisibility(false);

        if (panelAdded && panelView != null) {
            try {
                windowManager.removeView(panelView);
            } catch (Exception ignored) {
            }

            panelAdded = false;
        }

        if (markerAdded && markerView != null) {
            try {
                windowManager.removeView(markerView);
            } catch (Exception ignored) {
            }

            markerAdded = false;
        }

        panelView = null;
        markerView = null;
        clockView = null;
        countdownView = null;
        statusView = null;

        panelParams = null;
        markerParams = null;
    }

    @Override
    public void onDestroy() {

        stopLoop();

        changeOverlayVisibility(false);

        handler.removeCallbacks(displayUpdater);

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
