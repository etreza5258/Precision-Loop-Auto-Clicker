
package com.precision.loopclicker;

import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import android.view.ViewGroup;
import android.view.Window;
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

    private static final String CHANNEL_ID =
            "precision_loop_channel";

    private static final int NOTIFICATION_ID = 4101;

    private static final long SERVICE_RETRY_MS = 250;
    private static final long GESTURE_RETRY_MS = 10;
    private static final long DISPLAY_UPDATE_MS = 50;

    private static final int PANEL_BACKGROUND =
            Color.rgb(20, 30, 42);

    private static final int ACCENT_COLOR =
            Color.rgb(3, 218, 197);

    private static final int TITLE_COLOR =
            Color.rgb(255, 152, 0);

    private static volatile FloatingService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private WindowManager windowManager;

    private LinearLayout panel;

    private TextView markerView;

    private TextView selectedTimeView;
    private TextView countdownStatusView;

    private TextView countdownHour;
    private TextView countdownMinute;
    private TextView countdownSecond;
    private TextView countdownMillis;

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
    private long selectedStartEpochMs;

    private long cycleDurationMs;
    private long pauseDurationMs;
    private long clickIntervalMs;

    private long cycleStartElapsedMs;
    private long cycleDeadlineElapsedMs;
    private long lastClickDispatchElapsedMs;
    private long lastBatchCount;
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

    // ---------------------------------------------------------
    // Floating start-time settings
    // ---------------------------------------------------------

    private boolean floatingTimeConfigured;

    private int floatingStartHour;
    private int floatingStartMinute;
    private int floatingStartSecond;
    private int floatingStartMillis;

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
                handler.postDelayed(this, DISPLAY_UPDATE_MS);
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

            shutdownService();

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

        if (running) {
            setMarkerTouchable(false);
        }
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
                dp(7),
                dp(6),
                dp(7),
                dp(6)
        );

        panel.setBackground(
                makeBackground(
                        PANEL_BACKGROUND,
                        dp(11)
                )
        );

        panel.setClickable(true);

        createCountdownSection();

        LinearLayout buttonsRow =
                new LinearLayout(this);

        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER);
        buttonsRow.setPadding(0, dp(3), 0, 0);

        TextView startButton =
                createSmallPanelButton(
                        "شروع",
                        Color.rgb(20, 125, 75)
                );

        TextView stopButton =
                createSmallPanelButton(
                        "توقف",
                        Color.rgb(170, 65, 45)
                );

        TextView shutdownButton =
                createSmallPanelButton(
                        "خاموش",
                        Color.rgb(75, 75, 85)
                );

        TextView timeButton =
                createTimePickerButton();

        startButton.setOnClickListener(v -> openMainForStart());

        stopButton.setOnClickListener(v -> stopLoop());

        shutdownButton.setOnClickListener(v -> shutdownService());

        timeButton.setOnClickListener(v -> showStartTimePicker());

        buttonsRow.addView(startButton);
        buttonsRow.addView(stopButton);
        buttonsRow.addView(shutdownButton);
        buttonsRow.addView(timeButton);

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

    private TextView createSmallPanelButton(
            String text,
            int backgroundColor
    ) {

        TextView button = new TextView(this);

        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);

        button.setPadding(
                dp(5),
                dp(5),
                dp(5),
                dp(5)
        );

        button.setBackground(
                makeBackground(
                        backgroundColor,
                        dp(7)
                )
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        dp(32),
                        1f
                );

        params.setMargins(
                dp(2),
                0,
                dp(2),
                0
        );

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
                makeBackground(
                        Color.rgb(55, 85, 110),
                        dp(7)
                )
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        dp(34),
                        dp(32)
                );

        params.setMargins(
                dp(2),
                0,
                dp(2),
                0
        );

        button.setLayoutParams(params);
        button.setClickable(true);

        return button;
    }

    // ---------------------------------------------------------
    // Floating time picker
    // ---------------------------------------------------------

    private void showStartTimePicker() {

        if (!Settings.canDrawOverlays(this)) {
            updateCountdownStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (!floatingTimeConfigured) {

            Calendar calendar = Calendar.getInstance(
                    TimeZone.getTimeZone("Asia/Tehran")
            );

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

        root.setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(10)
        );

        // فقط پس‌زمینه پنجره تنظیم ساعت سفید شده است.
        root.setBackground(
                makeBackground(
                        Color.WHITE,
                        dp(12)
                )
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

        Window window = dialog.getWindow();

        if (window != null) {

            window.setType(getOverlayType());

            window.setBackgroundDrawableResource(
                    android.R.color.transparent
            );

            window.addFlags(
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
            );

            WindowManager.LayoutParams params =
                    window.getAttributes();

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

            updateCountdownDisplay();

            updateCountdownStatus("زمان شروع انتخاب شد");

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

            updateCountdownStatus("باز کردن انتخاب‌گر زمان ناموفق بود");
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
        picker.setValue(Math.max(min, Math.min(max, value)));
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

        // رنگ متن عدد انتخاب‌شده
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

        // رنگ متن اعداد چرخ انتخاب‌گر
        try {

            Field paintField =
                    NumberPicker.class.getDeclaredField(
                            "mSelectorWheelPaint"
                    );

            paintField.setAccessible(true);

            Paint paint =
                    (Paint) paintField.get(picker);

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

        LinearLayout column = new LinearLayout(this);

        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);

        title.setText(label);

        // عنوان ستون‌ها مشکی برای خوانایی روی زمینه سفید
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
                new LinearLayout.LayoutParams(
                        0,
                        dp(38),
                        1f
                );

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

    private void openMainForStart() {

        Intent intent =
                new Intent(this, MainActivity.class);

        intent.setAction(ACTION_START_FROM_FLOATING);

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
            updateCountdownStatus("باز کردن صفحه اصلی ناموفق بود");
        }
    }

    private void shutdownService() {

        stopLoop();
        removeFloatingViews();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        stopSelf();
    }

    // ---------------------------------------------------------
    // Countdown UI
    // ---------------------------------------------------------

    private void createCountdownSection() {

        LinearLayout countdownContainer =
                new LinearLayout(this);

        countdownContainer.setOrientation(
                LinearLayout.VERTICAL
        );

        countdownContainer.setPadding(
                dp(6),
                dp(5),
                dp(6),
                dp(5)
        );

        countdownContainer.setBackground(
                makeBackground(
                        Color.rgb(13, 25, 36),
                        dp(8)
                )
        );

        LinearLayout.LayoutParams containerParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        containerParams.setMargins(
                0,
                0,
                0,
                dp(3)
        );

        countdownContainer.setLayoutParams(containerParams);

        TextView heading = new TextView(this);

        heading.setText("شمارش معکوس");
        heading.setTextColor(TITLE_COLOR);
        heading.setTextSize(12);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);

        selectedTimeView = new TextView(this);

        selectedTimeView.setText(
                "ساعت: --:--:--.---"
        );

        selectedTimeView.setTextColor(Color.WHITE);
        selectedTimeView.setTextSize(9);
        selectedTimeView.setGravity(Gravity.CENTER);
        selectedTimeView.setSingleLine(true);
        selectedTimeView.setPadding(
                0,
                dp(3),
                0,
                dp(2)
        );

        countdownStatusView = new TextView(this);

        countdownStatusView.setText("آماده");
        countdownStatusView.setTextColor(
                Color.rgb(180, 190, 205)
        );

        countdownStatusView.setTextSize(9);
        countdownStatusView.setGravity(Gravity.CENTER);

        countdownStatusView.setPadding(
                0,
                dp(1),
                0,
                dp(3)
        );

        LinearLayout digits =
                new LinearLayout(this);

        digits.setOrientation(
                LinearLayout.HORIZONTAL
        );

        digits.setGravity(Gravity.CENTER);

        countdownHour = createCountdownDigit("00");
        countdownMinute = createCountdownDigit("00");
        countdownSecond = createCountdownDigit("00");
        countdownMillis = createCountdownDigit("000");

        countdownMillis.setTextSize(15);

        digits.addView(countdownHour);
        digits.addView(createCountdownSeparator(":"));

        digits.addView(countdownMinute);
        digits.addView(createCountdownSeparator(":"));

        digits.addView(countdownSecond);
        digits.addView(createCountdownSeparator("."));

        digits.addView(countdownMillis);

        countdownContainer.addView(heading);
        countdownContainer.addView(selectedTimeView);
        countdownContainer.addView(countdownStatusView);
        countdownContainer.addView(digits);

        panel.addView(countdownContainer);
    }

    private TextView createCountdownDigit(String value) {

        TextView tv = new TextView(this);

        tv.setText(value);
        tv.setTextColor(ACCENT_COLOR);
        tv.setTextSize(17);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);

        tv.setBackground(
                makeBackground(
                        Color.rgb(25, 42, 55),
                        dp(5)
                )
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        dp(32),
                        value.length() == 3 ? 1.35f : 1f
                );

        params.setMargins(
                dp(1),
                0,
                dp(1),
                0
        );

        tv.setLayoutParams(params);

        return tv;
    }

    private TextView createCountdownSeparator(String value) {

        TextView tv = new TextView(this);

        tv.setText(value);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        dp(9),
                        dp(32)
                );

        tv.setLayoutParams(params);

        return tv;
    }

    private void updateCountdownDisplay() {

        if (countdownHour == null
                || countdownMinute == null
                || countdownSecond == null
                || countdownMillis == null) {
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

        if (selectedTimeView != null) {

            String currentTime = format.format(
                    new Date(System.currentTimeMillis())
            );

            String displayText = "ساعت: " + currentTime;

            if (selectedStartEpochMs > 0) {

                displayText += " | شروع: "
                        + format.format(
                        new Date(selectedStartEpochMs)
                );

            } else if (floatingTimeConfigured) {

                displayText += " | شروع: "
                        + formatFloatingStartTime();
            }

            selectedTimeView.setText(displayText);
        }

        long remaining = 0;

        if (running && !cycleActive) {

            remaining = Math.max(
                    0,
                    anchorElapsedMs - SystemClock.elapsedRealtime()
            );
        }

        long hours = remaining / 3600000L;
        long minutes = (remaining % 3600000L) / 60000L;
        long seconds = (remaining % 60000L) / 1000L;
        long millis = remaining % 1000L;

        countdownHour.setText(
                String.format(Locale.US, "%02d", hours)
        );

        countdownMinute.setText(
                String.format(Locale.US, "%02d", minutes)
        );

        countdownSecond.setText(
                String.format(Locale.US, "%02d", seconds)
        );

        countdownMillis.setText(
                String.format(Locale.US, "%03d", millis)
        );

        if (!running) {

            updateCountdownStatus("آماده شروع");

        } else if (cycleActive) {

            updateCountdownStatus("چرخه در حال اجرا است");

        } else if (remaining == 0) {

            updateCountdownStatus("در حال شروع کلیک‌ها...");

        } else {

            updateCountdownStatus("زمان باقی‌مانده تا شروع");
        }
    }

    private void updateCountdownStatus(String message) {

        if (countdownStatusView != null) {
            countdownStatusView.setText(message);
        }
    }

    // ---------------------------------------------------------
    // Marker
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // Marker touch control
    // ---------------------------------------------------------

    private void setMarkerTouchable(boolean touchable) {

        if (markerView == null || markerParams == null) {
            return;
        }

        int flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        if (!touchable) {

            flags |=
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        if (markerParams.flags == flags) {
            return;
        }

        markerParams.flags = flags;

        updateViewLayout(
                markerView,
                markerParams
        );
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

        selectedTimeView = null;
        countdownStatusView = null;

        countdownHour = null;
        countdownMinute = null;
        countdownSecond = null;
        countdownMillis = null;
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
            updateCountdownStatus("تنظیمات شروع دریافت نشد");
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

            updateCountdownStatus("مقادیر تنظیمات معتبر نیستند");
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
            updateCountdownStatus("زمان شروع گذشته است");
            return;
        }

        long nowWall = System.currentTimeMillis();

        if (config.hasExtra("start_time_epoch_ms")) {

            selectedStartEpochMs = config.getLongExtra(
                    "start_time_epoch_ms",
                    nowWall + delayMs
            );

        } else {

            selectedStartEpochMs = nowWall + delayMs;
        }

        long nowElapsed = SystemClock.elapsedRealtime();

        if (delayMs > Long.MAX_VALUE - nowElapsed) {
            updateCountdownStatus("زمان شروع بیش از حد بزرگ است");
            return;
        }

        anchorElapsedMs = nowElapsed + delayMs;

        accessibility = AutoClickService.getInstance();

        running = true;
        cycleActive = false;

        setMarkerTouchable(false);

        cycleIndex = 0;
        clickNumber = 0;
        lastBatchCount = 0;

        showFloatingViews();
        updateTarget();
        updateCountdownDisplay();

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

        updateCountdownStatus(
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

            updateCountdownStatus("سرویس کلیک متصل شد");

            if (!cycleActive) {
                startCycle(generation);
            } else {
                scheduleNextClick(generation);
            }

            return;
        }

        updateCountdownStatus(
                "منتظر فعال شدن سرویس دسترسی‌پذیری..."
        );

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
        lastBatchCount = 0;

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

        accessibility = getReadyAccessibility();

        if (accessibility == null) {
            waitForAccessibility(generation);
            return;
        }

        long effectiveInterval =
                Math.max(
                        1L,
                        accessibility.getEffectiveClickIntervalMs(
                                clickIntervalMs
                        )
                );

        long targetElapsed;

        if (clickNumber == 0 || lastBatchCount <= 0) {

            targetElapsed = SystemClock.elapsedRealtime();

        } else {

            long offset;

            if (lastBatchCount >
                    Long.MAX_VALUE / effectiveInterval) {

                waitForCycleEnd(generation);
                return;
            }

            offset = lastBatchCount * effectiveInterval;

            if (lastClickDispatchElapsedMs >
                    Long.MAX_VALUE - offset) {

                waitForCycleEnd(generation);
                return;
            }

            targetElapsed =
                    lastClickDispatchElapsedMs + offset;
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

            updateCountdownStatus(
                    "اتصال سرویس کلیک قطع شده؛ تلاش مجدد"
            );

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

        long remainingClicks = clickCount - clickNumber;

        int maxBatchCount =
                clickService.getMaxBatchClickCount(clickIntervalMs);

        if (maxBatchCount <= 0) {

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

        long effectiveInterval =
                Math.max(
                        1L,
                        clickService.getEffectiveClickIntervalMs(
                                clickIntervalMs
                        )
                );

        long remainingTime =
                cycleDeadlineElapsedMs - now;

        if (remainingTime <= 0) {
            waitForCycleEnd(generation);
            return;
        }

        long clicksFittingInCycle =
                ((remainingTime - 1L) / effectiveInterval) + 1L;

        long requestedBatchCount = Math.min(
                remainingClicks,
                Math.min(
                        (long) maxBatchCount,
                        clicksFittingInCycle
                )
        );

        if (requestedBatchCount <= 0) {
            waitForCycleEnd(generation);
            return;
        }

        final int batchCount = (int) requestedBatchCount;

        final long callbackGeneration = generation;

        clickNumber += batchCount;

        lastClickDispatchElapsedMs = now;
        lastBatchCount = batchCount;

        int acceptedCount = clickService.performClickBatch(
                batchCount,
                clickIntervalMs,
                () -> {

                    handler.post(() -> {

                        if (!isCurrentRun(callbackGeneration)
                                || !cycleActive) {
                            return;
                        }

                        if (SystemClock.elapsedRealtime()
                                >= cycleDeadlineElapsedMs
                                || clickNumber >= clickCount) {

                            waitForCycleEnd(callbackGeneration);

                        } else {

                            scheduleNextClick(callbackGeneration);
                        }

                        updateDisplay();
                    });
                }
        );

        if (acceptedCount <= 0) {

            clickNumber -= batchCount;
            lastBatchCount = 0;

            nextClickRunnable = () -> {

                if (isCurrentRun(generation) && cycleActive) {
                    dispatchNextClick(generation);
                }
            };

            handler.postDelayed(
                    nextClickRunnable,
                    GESTURE_RETRY_MS
            );

            updateDisplay();
            return;
        }

        if (acceptedCount < batchCount) {

            long difference = batchCount - acceptedCount;

            clickNumber -= difference;
            lastBatchCount = acceptedCount;
        }

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
            updateCountdownStatus("محدودیت زمان‌بندی چرخه");

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
    // Stop and display
    // ---------------------------------------------------------

    private void stopLoop() {

        running = false;
        cycleActive = false;

        runGeneration++;

        removeScheduledCallbacks();

        setMarkerTouchable(true);

        updateCountdownStatus("متوقف شد");
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

    private void updateDisplay() {

        updateCountdownDisplay();
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
