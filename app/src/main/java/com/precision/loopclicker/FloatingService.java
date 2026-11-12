
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Calendar;

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
    private static final long DASHBOARD_UPDATE_MS = 250;

    private static final int PANEL_BACKGROUND =
            Color.rgb(20, 30, 42);

    private static final int ACCENT_COLOR =
            Color.rgb(3, 218, 197);

    private static final int TITLE_COLOR =
            Color.rgb(255, 152, 0);

    private static final String PREF_NAME =
            "precision_loop_settings";

    private static final String PREF_CLOCK_SOURCE =
            "clock_source";

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
    private TextView reportsButton;

    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams markerParams;

    private int markerSize;

    private int targetX = 500;
    private int targetY = 500;

    private volatile boolean running;
    private boolean cycleActive;

    private long runGeneration;
    private long cycleIndex;
    private long totalCycleCount = 1;

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

    private boolean cycleYellow;
    private boolean cycleIntervalViolation;
    private boolean cycleDeadlineSkipped;
    private boolean cycleOtherFailure;

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

    private static final String CLOCK_PHONE = "phone";
    private static final String CLOCK_INTERNET = "internet";

    private String clockSource = CLOCK_PHONE;

    private static final String ACTION_CLOCK_SOURCE_CHANGED =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";

    private boolean clockSourceReceiverRegistered = false;

    private final BroadcastReceiver clockSourceReceiver =
            new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent == null
                    || !ACTION_CLOCK_SOURCE_CHANGED.equals(
                            intent.getAction()
                    )) {
                return;
            }

            String newSource =
                    intent.getStringExtra("clock_source");

            if (!CLOCK_PHONE.equals(newSource)
                    && !CLOCK_INTERNET.equals(newSource)) {
                return;
            }

            clockSource = newSource;

            updateCountdownDisplay();
            updateLiveDashboard();
        }
    };

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

        } catch (Exception e) {

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

    private void loadSavedClockSource() {

        try {

            String savedSource = getSharedPreferences(
                    PREF_NAME,
                    MODE_PRIVATE
            ).getString(
                    PREF_CLOCK_SOURCE,
                    CLOCK_PHONE
            );

            clockSource = CLOCK_INTERNET.equals(savedSource)
                    ? CLOCK_INTERNET
                    : CLOCK_PHONE;

        } catch (Exception ignored) {

            clockSource = CLOCK_PHONE;
        }
    }

    private boolean floatingTimeConfigured;

    private int floatingStartHour;
    private int floatingStartMinute;
    private int floatingStartSecond;
    private int floatingStartMillis;

    private Dialog dashboardDialog;

    private TextView dashboardStateView;
    private TextView dashboardCycleView;
    private TextView dashboardSuccessView;
    private TextView dashboardFailureView;
    private TextView dashboardDispatchedView;
    private TextView dashboardTargetView;
    private TextView dashboardErrorView;
    private TextView dashboardClockView;
    private TextView dashboardReportsView;

    private Runnable dashboardUpdateRunnable;

    private long completedSuccessfulClicks;
    private long completedFailedClicks;
    private long completedDispatchedClicks;

    private boolean currentCycleStatsFinalized;

    private final List<String> liveErrors = new ArrayList<>();

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

        loadSavedClockSource();
        registerClockSourceReceiver();

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
        panel.setPadding(dp(7), dp(6), dp(7), dp(6));

        panel.setBackground(
                makeBackground(PANEL_BACKGROUND, dp(11))
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

        TextView timeButton = createTimePickerButton();

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
        button.setPadding(dp(5), dp(5), dp(5), dp(5));

        button.setBackground(
                makeBackground(backgroundColor, dp(7))
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(32), 1f);

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
                new LinearLayout.LayoutParams(dp(34), dp(32));

        params.setMargins(dp(2), 0, dp(2), 0);

        button.setLayoutParams(params);
        button.setClickable(true);

        return button;
    }

    private void showStartTimePicker() {

        if (!Settings.canDrawOverlays(this)) {
            updateCountdownStatus("مجوز نمایش روی برنامه‌ها فعال نیست");
            return;
        }

        if (!floatingTimeConfigured) {

            Calendar calendar = Calendar.getInstance(
                    TimeZone.getTimeZone("Asia/Tehran")
            );

            floatingStartHour = calendar.get(Calendar.HOUR_OF_DAY);
            floatingStartMinute = calendar.get(Calendar.MINUTE);
            floatingStartSecond = calendar.get(Calendar.SECOND);
            floatingStartMillis = calendar.get(Calendar.MILLISECOND);
        }

        Dialog dialog = new Dialog(
                this,
                android.R.style.Theme_Material_Light_Dialog_Alert
        );

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
                String.format(Locale.US, "%03d", value)
        );

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

        Window window = dialog.getWindow();

        if (window != null) {

            window.setType(getOverlayType());
            window.setBackgroundDrawableResource(android.R.color.transparent);
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

        try {

            Field inputField =
                    NumberPicker.class.getDeclaredField("mInputText");

            inputField.setAccessible(true);

            EditText input = (EditText) inputField.get(picker);

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
                floatingStartMillis
        );
    }

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
        closeDashboard();
        removeFloatingViews();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        stopSelf();
    }

    private void createCountdownSection() {

        LinearLayout countdownContainer = new LinearLayout(this);
        countdownContainer.setOrientation(LinearLayout.VERTICAL);
        countdownContainer.setPadding(dp(6), dp(5), dp(6), dp(5));

        countdownContainer.setBackground(
                makeBackground(Color.rgb(13, 25, 36), dp(8))
        );

        LinearLayout.LayoutParams containerParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        containerParams.setMargins(0, 0, 0, dp(3));
        countdownContainer.setLayoutParams(containerParams);

        TextView heading = new TextView(this);
        heading.setText("شمارش معکوس");
        heading.setTextColor(TITLE_COLOR);
        heading.setTextSize(12);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);

        selectedTimeView = new TextView(this);
        selectedTimeView.setText("ساعت: --:--:--.---");
        selectedTimeView.setTextColor(Color.WHITE);
        selectedTimeView.setTextSize(9);
        selectedTimeView.setGravity(Gravity.CENTER);
        selectedTimeView.setSingleLine(true);
        selectedTimeView.setPadding(0, dp(3), 0, dp(2));

        countdownStatusView = new TextView(this);
        countdownStatusView.setText("آماده");
        countdownStatusView.setTextColor(Color.rgb(180, 190, 205));
        countdownStatusView.setTextSize(9);
        countdownStatusView.setGravity(Gravity.CENTER);
        countdownStatusView.setPadding(0, dp(1), 0, dp(3));

        LinearLayout digits = new LinearLayout(this);
        digits.setOrientation(LinearLayout.HORIZONTAL);
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

        LinearLayout digitsAndReports = new LinearLayout(this);
        digitsAndReports.setOrientation(LinearLayout.HORIZONTAL);
        digitsAndReports.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams digitsParams =
                new LinearLayout.LayoutParams(0, dp(32), 1f);

        digits.setLayoutParams(digitsParams);
        digitsAndReports.addView(digits);

        reportsButton = new TextView(this);
        reportsButton.setText("▤");
        reportsButton.setTextColor(Color.WHITE);
        reportsButton.setTextSize(19);
        reportsButton.setGravity(Gravity.CENTER);
        reportsButton.setBackground(
                makeBackground(Color.rgb(55, 85, 110), dp(6))
        );

        LinearLayout.LayoutParams reportsParams =
                new LinearLayout.LayoutParams(dp(34), dp(32));

        reportsParams.setMargins(dp(4), 0, 0, 0);
        reportsButton.setLayoutParams(reportsParams);
        reportsButton.setContentDescription("داشبورد زنده");
        reportsButton.setOnClickListener(v -> showLiveDashboard());

        digitsAndReports.addView(reportsButton);
        countdownContainer.addView(digitsAndReports);
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
                makeBackground(Color.rgb(25, 42, 55), dp(5))
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        dp(32),
                        value.length() == 3 ? 1.35f : 1f
                );

        params.setMargins(dp(1), 0, dp(1), 0);
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

        tv.setLayoutParams(
                new LinearLayout.LayoutParams(dp(9), dp(32))
        );

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
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

        format.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));

        if (selectedTimeView != null) {

            String currentTime = format.format(
                    new Date(System.currentTimeMillis())
            );

            String sourceLabel = CLOCK_INTERNET.equals(clockSource)
                    ? "اینترنت ایران"
                    : "ساعت گوشی";

            String displayText = sourceLabel + ": " + currentTime;

            if (selectedStartEpochMs > 0) {

                displayText += " | شروع: "
                        + format.format(new Date(selectedStartEpochMs));

            } else if (floatingTimeConfigured) {

                displayText += " | شروع: " + formatFloatingStartTime();
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

        countdownHour.setText(String.format(Locale.US, "%02d", hours));
        countdownMinute.setText(String.format(Locale.US, "%02d", minutes));
        countdownSecond.setText(String.format(Locale.US, "%02d", seconds));
        countdownMillis.setText(String.format(Locale.US, "%03d", millis));

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

        if (dashboardStateView != null) {
            dashboardStateView.setText("وضعیت: " + message);
        }
    }

    private void showLiveDashboard() {

        if (dashboardDialog != null && dashboardDialog.isShowing()) {
            dashboardDialog.dismiss();
            return;
        }

        Dialog dialog = new Dialog(
                this,
                android.R.style.Theme_Material_Dialog_Alert
        );

        dashboardDialog = dialog;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackground(
                makeBackground(Color.rgb(20, 30, 42), dp(12))
        );

        TextView title = new TextView(this);
        title.setText("داشبورد زنده اجرا");
        title.setTextColor(TITLE_COLOR);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        dashboardStateView = createDashboardText();
        dashboardCycleView = createDashboardText();
        dashboardSuccessView = createDashboardText();
        dashboardFailureView = createDashboardText();
        dashboardDispatchedView = createDashboardText();
        dashboardTargetView = createDashboardText();
        dashboardClockView = createDashboardText();
        dashboardErrorView = createDashboardText();

        root.addView(dashboardStateView);
        root.addView(dashboardCycleView);
        root.addView(dashboardSuccessView);
        root.addView(dashboardFailureView);
        root.addView(dashboardDispatchedView);
        root.addView(dashboardTargetView);
        root.addView(dashboardClockView);
        root.addView(dashboardErrorView);

        TextView reportsTitle = new TextView(this);
        reportsTitle.setText("گزارش چرخه‌های تکمیل‌شده");
        reportsTitle.setTextColor(TITLE_COLOR);
        reportsTitle.setTextSize(14);
        reportsTitle.setTypeface(null, Typeface.BOLD);
        reportsTitle.setPadding(0, dp(10), 0, dp(6));
        root.addView(reportsTitle);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        dashboardReportsView = new TextView(this);
        dashboardReportsView.setTextColor(Color.WHITE);
        dashboardReportsView.setTextSize(12);
        dashboardReportsView.setPadding(dp(8), dp(8), dp(8), dp(8));
        dashboardReportsView.setBackground(
                makeBackground(Color.rgb(30, 43, 57), dp(7))
        );

        scrollView.addView(dashboardReportsView);

        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(210)
                );

        root.addView(scrollView, scrollParams);

        TextView close = createDialogButton(
                "بستن داشبورد",
                Color.rgb(65, 85, 105)
        );

        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(40)
                );

        closeParams.setMargins(0, dp(10), 0, 0);
        close.setLayoutParams(closeParams);
        close.setOnClickListener(v -> dialog.dismiss());

        root.addView(close);

        dialog.setContentView(root);
        dialog.setCancelable(true);

        Window window = dialog.getWindow();

        if (window != null) {

            window.setType(getOverlayType());
            window.setBackgroundDrawableResource(
                    android.R.color.transparent
            );

            WindowManager.LayoutParams params = window.getAttributes();
            params.width = dp(330);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;

            window.setAttributes(params);
        }

        dialog.setOnDismissListener(d -> {

            stopDashboardUpdates();

            if (dashboardDialog == dialog) {
                dashboardDialog = null;
            }

            dashboardStateView = null;
            dashboardCycleView = null;
            dashboardSuccessView = null;
            dashboardFailureView = null;
            dashboardDispatchedView = null;
            dashboardTargetView = null;
            dashboardClockView = null;
            dashboardErrorView = null;
            dashboardReportsView = null;
        });

        try {

            dialog.show();

            if (window != null) {
                window.setLayout(
                        dp(330),
                        WindowManager.LayoutParams.WRAP_CONTENT
                );
            }

            updateLiveDashboard();

            startDashboardUpdates();

        } catch (Exception e) {

            dashboardDialog = null;
            updateCountdownStatus("نمایش داشبورد ممکن نشد");
        }
    }

    private TextView createDashboardText() {

        TextView view = new TextView(this);

        view.setTextColor(Color.WHITE);
        view.setTextSize(12);
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        view.setGravity(Gravity.CENTER_VERTICAL);

        return view;
    }

    private void startDashboardUpdates() {

        stopDashboardUpdates();

        dashboardUpdateRunnable = new Runnable() {
            @Override
            public void run() {

                if (dashboardDialog == null
                        || !dashboardDialog.isShowing()) {
                    return;
                }

                updateLiveDashboard();

                handler.postDelayed(
                        this,
                        DASHBOARD_UPDATE_MS
                );
            }
        };

        handler.post(dashboardUpdateRunnable);
    }

    private void stopDashboardUpdates() {

        if (dashboardUpdateRunnable != null) {
            handler.removeCallbacks(dashboardUpdateRunnable);
            dashboardUpdateRunnable = null;
        }
    }

    private void closeDashboard() {

        stopDashboardUpdates();

        if (dashboardDialog != null) {

            try {
                dashboardDialog.dismiss();
            } catch (Exception ignored) {
            }

            dashboardDialog = null;
        }
    }

    private void updateLiveDashboard() {

        if (dashboardDialog == null
                || !dashboardDialog.isShowing()) {
            return;
        }

        AutoClickService service = AutoClickService.getInstance();

        long currentSuccess = 0;
        long currentFailure = 0;
        long currentDispatched = 0;

        if (service != null) {

            if (!currentCycleStatsFinalized) {
                currentSuccess = service.getSuccessfulClicks();
                currentFailure = service.getFailedClicks();
                currentDispatched = service.getDispatchedClicks();
            }
        }

        long totalSuccess =
                completedSuccessfulClicks + currentSuccess;

        long totalFailure =
                completedFailedClicks + currentFailure;

        long totalDispatched =
                completedDispatchedClicks + currentDispatched;

        if (dashboardStateView != null) {

            String state;

            if (running && cycleActive) {
                state = "در حال اجرای چرخه";
            } else if (running) {
                state = "در انتظار چرخه بعدی یا زمان شروع";
            } else {
                state = "متوقف یا آماده";
            }

            dashboardStateView.setText("وضعیت: " + state);
        }

        if (dashboardCycleView != null) {

            long shownCycle = cycleActive
                    ? cycleIndex + 1
                    : Math.min(cycleIndex, totalCycleCount);

            dashboardCycleView.setText(
                    "چرخه: " + shownCycle + " از " + totalCycleCount
            );
        }

        if (dashboardSuccessView != null) {
            dashboardSuccessView.setText(
                    "کلیک‌های موفق: " + totalSuccess
            );
        }

        if (dashboardFailureView != null) {
            dashboardFailureView.setText(
                    "کلیک‌های ناموفق: " + totalFailure
            );
        }

        if (dashboardDispatchedView != null) {
            dashboardDispatchedView.setText(
                    "کلیک‌های ارسال‌شده: " + totalDispatched
            );
        }

        if (dashboardTargetView != null) {

            dashboardTargetView.setText(
                    "کلیک‌های پذیرفته‌شده در چرخه فعلی: "
                            + clickNumber + " از " + clickCount
            );
        }

        if (dashboardClockView != null) {

            String source = CLOCK_INTERNET.equals(clockSource)
                    ? "ساعت اینترنتی ایران"
                    : "ساعت گوشی";

            dashboardClockView.setText(
                    "مبنای ساعت شروع: " + source
            );
        }

        if (dashboardErrorView != null) {

            String error = service == null
                    ? "سرویس دسترسی‌پذیری متصل نیست"
                    : service.getLastError();

            if (error == null || error.trim().isEmpty()) {
                error = "خطای ثبت‌شده‌ای وجود ندارد";
            }

            dashboardErrorView.setText("آخرین خطا: " + error);
        }

        updateDashboardReports();
    }

    private void updateDashboardReports() {

        if (dashboardReportsView == null) {
            return;
        }

        List<String> reports =
                CycleReportStore.getFloatingReports(this);

        StringBuilder builder = new StringBuilder();

        if (reports == null || reports.isEmpty()) {

            builder.append("هنوز گزارشی ثبت نشده است");

        } else {

            for (String report : reports) {

                if (report != null) {
                    builder.append(report)
                            .append("\n\n");
                }
            }
        }

        if (cycleActive) {

            builder.insert(
                    0,
                    "چرخه فعلی هنوز تکمیل نشده است.\n\n"
            );
        }

        dashboardReportsView.setText(builder.toString());
    }

    private void addLiveError(String error) {

        if (error == null || error.trim().isEmpty()) {
            return;
        }

        liveErrors.add(0, error);

        while (liveErrors.size() > 10) {
            liveErrors.remove(liveErrors.size() - 1);
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
                            + (int) (event.getRawX() - markerTouchRawX);

                    markerParams.y = markerStartY
                            + (int) (event.getRawY() - markerTouchRawY);

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

        markerParams.gravity = Gravity.TOP | Gravity.START;
        markerParams.x = dp(120);
        markerParams.y = dp(350);

        try {
            windowManager.addView(markerView, markerParams);
        } catch (Exception e) {
            markerView = null;
            markerParams = null;
        }
    }

    private void setMarkerTouchable(boolean touchable) {

        if (markerView == null || markerParams == null) {
            return;
        }

        int flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        if (!touchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        if (markerParams.flags == flags) {
            return;
        }

        markerParams.flags = flags;
        updateViewLayout(markerView, markerParams);
    }

    private GradientDrawable makeBackground(
            int color,
            int radius
    ) {

        GradientDrawable drawable = new GradientDrawable();

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

        AutoClickService service = AutoClickService.getInstance();

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
        countdownStatusView = null;

        countdownHour = null;
        countdownMinute = null;
        countdownSecond = null;
        countdownMillis = null;
        reportsButton = null;
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

        clockSource = config.getStringExtra("clock_source");

        if (!CLOCK_INTERNET.equals(clockSource)) {
            clockSource = CLOCK_PHONE;
        }

        cycleDurationMs =
                config.getLongExtra("cycle_duration_ms", 1000);

        pauseDurationMs =
                config.getLongExtra("pause_duration_ms", 0);

        clickIntervalMs =
                config.getLongExtra("click_interval_ms", 50);

        clickCount =
                config.getLongExtra("click_count", 1);

        totalCycleCount =
                config.getLongExtra("cycle_total_count", 1);

        if (cycleDurationMs <= 0
                || pauseDurationMs < 0
                || clickIntervalMs < 0
                || clickCount <= 0
                || totalCycleCount <= 0) {

            updateCountdownStatus("مقادیر تنظیمات معتبر نیستند");
            return;
        }

        long delayMs;

        if (config.hasExtra("start_delay_ms")) {

            delayMs = config.getLongExtra("start_delay_ms", -1);

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

        cycleIndex = 0;
        clickNumber = 0;
        lastBatchCount = 0;
        lastClickDispatchElapsedMs = 0;

        completedSuccessfulClicks = 0;
        completedFailedClicks = 0;
        completedDispatchedClicks = 0;

        currentCycleStatsFinalized = false;
        liveErrors.clear();

        cycleYellow = false;
        cycleIntervalViolation = false;
        cycleDeadlineSkipped = false;
        cycleOtherFailure = false;

        setMarkerTouchable(false);

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

        postAtElapsedTime(cycleStartRunnable, anchorElapsedMs);

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

    private AutoClickService getReadyAccessibility() {

        AutoClickService service = AutoClickService.getInstance();

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

        handler.postDelayed(cycleStartRunnable, SERVICE_RETRY_MS);
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

            handler.postDelayed(cycleStartRunnable, GESTURE_RETRY_MS);
            return;
        }

        cycleActive = true;

        clickNumber = 0;
        lastBatchCount = 0;
        lastClickDispatchElapsedMs = 0;

        cycleYellow = false;
        cycleIntervalViolation = false;
        cycleDeadlineSkipped = false;
        cycleOtherFailure = false;

        cycleStartElapsedMs = SystemClock.elapsedRealtime();
        cycleDeadlineElapsedMs = cycleStartElapsedMs + cycleDurationMs;
        cycleStartWallMs = System.currentTimeMillis();

        accessibility.resetClickCounters();

        currentCycleStatsFinalized = false;

        accessibility.setTarget(targetX, targetY);

        scheduleNextClick(generation);
        updateDisplay();
    }

    /*
     * زمان مجاز کلیک بعدی را از خود AutoClickService می‌گیریم.
     * این کار مانع می‌شود Callback با تأخیر باعث ارسال زودهنگام
     * بسته بعدی و رد شدن آن به علت interval شود.
     */
    private long getNextAllowedElapsedMs(
            AutoClickService service
    ) {

        if (service == null) {
            return SystemClock.elapsedRealtime();
        }

        return service.getNextAllowedClickElapsedRealtimeMs();
    }

    private void scheduleNextClick(long generation) {

        if (!isCurrentRun(generation) || !cycleActive) {
            return;
        }

        if (clickNumber >= clickCount) {
            waitForCycleEnd(generation);
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now >= cycleDeadlineElapsedMs) {
            cycleYellow = true;
            cycleDeadlineSkipped = true;
            waitForCycleEnd(generation);
            return;
        }

        accessibility = getReadyAccessibility();

        if (accessibility == null) {
            waitForAccessibility(generation);
            return;
        }

        long effectiveInterval = Math.max(
                1L,
                accessibility.getEffectiveClickIntervalMs(clickIntervalMs)
        );

        long targetElapsed;

        if (clickNumber == 0 || lastBatchCount <= 0) {

            targetElapsed = now;

        } else {

            if (lastBatchCount > Long.MAX_VALUE / effectiveInterval) {
                cycleOtherFailure = true;
                addLiveError("محاسبه فاصله کلیک‌ها از محدوده مجاز خارج شد");
                waitForCycleEnd(generation);
                return;
            }

            long offset = lastBatchCount * effectiveInterval;

            if (lastClickDispatchElapsedMs > Long.MAX_VALUE - offset) {
                cycleOtherFailure = true;
                addLiveError("محاسبه زمان کلیک بعدی نامعتبر شد");
                waitForCycleEnd(generation);
                return;
            }

            targetElapsed = lastClickDispatchElapsedMs + offset;
        }

        /*
         * زمان مجاز اعلام‌شده از AutoClickService نیز بررسی می‌شود.
         * بنابراین اگر Callback دیر برسد، هدف زمان‌بندی در گذشته
         * باقی نمی‌ماند و بسته زودهنگام رد نمی‌شود.
         */
        long nextAllowedElapsed = getNextAllowedElapsedMs(accessibility);

        targetElapsed = Math.max(targetElapsed, nextAllowedElapsed);

        if (targetElapsed >= cycleDeadlineElapsedMs) {
            cycleYellow = true;
            cycleDeadlineSkipped = true;
            waitForCycleEnd(generation);
            return;
        }

        nextClickRunnable = () -> {

            if (isCurrentRun(generation) && cycleActive) {
                dispatchNextClick(generation);
            }
        };

        postAtElapsedTime(nextClickRunnable, targetElapsed);
    }

    private void dispatchNextClick(long generation) {

        if (!isCurrentRun(generation) || !cycleActive) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now >= cycleDeadlineElapsedMs) {
            cycleYellow = true;
            cycleDeadlineSkipped = true;
            waitForCycleEnd(generation);
            return;
        }

        accessibility = getReadyAccessibility();

        if (accessibility == null) {

            updateCountdownStatus("اتصال سرویس کلیک قطع شده؛ تلاش مجدد");

            nextClickRunnable = () -> {

                if (isCurrentRun(generation) && cycleActive) {
                    dispatchNextClick(generation);
                }
            };

            handler.postDelayed(nextClickRunnable, SERVICE_RETRY_MS);
            return;
        }

        if (accessibility.isGestureInProgress()) {

            nextClickRunnable = () -> {

                if (isCurrentRun(generation) && cycleActive) {
                    dispatchNextClick(generation);
                }
            };

            handler.postDelayed(nextClickRunnable, GESTURE_RETRY_MS);
            return;
        }

        accessibility.setTarget(targetX, targetY);

        final AutoClickService clickService = accessibility;

        /*
         * قبل از ارسال بسته، دوباره زمان مجاز را کنترل می‌کنیم.
         * اگر هنوز زمان آن نرسیده باشد، به‌جای ارسال زودهنگام،
         * دوباره در زمان مجاز برنامه‌ریزی می‌کنیم.
         */
        long allowedElapsed = getNextAllowedElapsedMs(clickService);
        long currentElapsed = SystemClock.elapsedRealtime();

        if (currentElapsed < allowedElapsed) {

            if (allowedElapsed >= cycleDeadlineElapsedMs) {
                cycleYellow = true;
                cycleDeadlineSkipped = true;
                waitForCycleEnd(generation);
                return;
            }

            nextClickRunnable = () -> {

                if (isCurrentRun(generation) && cycleActive) {
                    dispatchNextClick(generation);
                }
            };

            postAtElapsedTime(nextClickRunnable, allowedElapsed);
            return;
        }

        long remainingClicks = clickCount - clickNumber;

        int maxBatchCount =
                clickService.getMaxBatchClickCount(clickIntervalMs);

        if (maxBatchCount <= 0) {
            cycleOtherFailure = true;
            addLiveError("تعداد مجاز کلیک در بسته صفر شد");
            waitForCycleEnd(generation);
            return;
        }

        int requestedBatchCount = (int) Math.min(
                remainingClicks,
                (long) maxBatchCount
        );

        if (requestedBatchCount <= 0) {
            waitForCycleEnd(generation);
            return;
        }

        final long callbackGeneration = generation;

        long dispatchElapsed = SystemClock.elapsedRealtime();

        if (dispatchElapsed >= cycleDeadlineElapsedMs) {
            cycleYellow = true;
            cycleDeadlineSkipped = true;
            waitForCycleEnd(generation);
            return;
        }

        lastClickDispatchElapsedMs = dispatchElapsed;
        lastBatchCount = 0;

        int acceptedCount = clickService.performClickBatch(
                requestedBatchCount,
                clickIntervalMs,
                cycleDeadlineElapsedMs,
                () -> handler.post(() -> {

                    if (!isCurrentRun(callbackGeneration) || !cycleActive) {
                        return;
                    }

                    if (clickService.wasLastBatchRejectedForInterval()) {

                        cycleYellow = true;
                        cycleIntervalViolation = true;

                        addLiveError(
                                "بسته کلیک به علت رعایت نشدن فاصله زمانی رد شد"
                        );
                    }

                    if (clickService.getLastBatchSkippedByDeadline() > 0) {

                        cycleYellow = true;
                        cycleDeadlineSkipped = true;

                        addLiveError(
                                "بخشی از کلیک‌ها به علت پایان مهلت ارسال نشد"
                        );
                    }

                    if (clickService.getFailedClicks() > 0) {

                        cycleOtherFailure = true;

                        String error = clickService.getLastError();

                        addLiveError(
                                error == null || error.trim().isEmpty()
                                        ? "خطا در اجرای ژست لمس"
                                        : error
                        );
                    }

                    if (SystemClock.elapsedRealtime() >= cycleDeadlineElapsedMs
                            || clickNumber >= clickCount
                            || cycleIntervalViolation
                            || cycleOtherFailure) {

                        waitForCycleEnd(callbackGeneration);

                    } else {

                        scheduleNextClick(callbackGeneration);
                    }

                    updateDisplay();
                })
        );

        if (acceptedCount <= 0) {

            if (clickService.wasLastBatchRejectedForInterval()) {

                /*
                 * این حالت نباید در زمان‌بندی عادی رخ دهد.
                 * اگر رخ دهد، چرخه زرد ثبت می‌شود و بسته دوباره
                 * با فاصله نامعتبر ارسال نخواهد شد.
                 */
                cycleYellow = true;
                cycleIntervalViolation = true;

                addLiveError("ارسال کلیک به علت فاصله زمانی زودهنگام رد شد");

            } else if (clickService.getLastBatchSkippedByDeadline() > 0) {

                cycleYellow = true;
                cycleDeadlineSkipped = true;

                addLiveError("مهلت چرخه پیش از ارسال بسته کلیک پایان یافت");

            } else {

                cycleOtherFailure = true;

                String error = clickService.getLastError();

                addLiveError(
                        error == null || error.trim().isEmpty()
                                ? "ارسال بسته کلیک ناموفق بود"
                                : error
                );
            }

            lastBatchCount = 0;

            waitForCycleEnd(generation);
            updateDisplay();
            return;
        }

        /*
         * زمان واقعی Dispatch را از AutoClickService می‌خوانیم.
         * زمان ثبت‌شده قبل از فراخوانی فقط مقدار موقت بود.
         */
        long actualDispatchElapsed =
                clickService.getLastDispatchElapsedRealtimeMs();

        if (actualDispatchElapsed > 0) {
            lastClickDispatchElapsedMs = actualDispatchElapsed;
        }

        lastBatchCount = acceptedCount;
        clickNumber += acceptedCount;

        if (acceptedCount < requestedBatchCount) {

            cycleYellow = true;
            cycleDeadlineSkipped = true;

            addLiveError("همه کلیک‌های درخواستی در مهلت چرخه ارسال نشدند");
        }

        if (clickService.getLastBatchSkippedByDeadline() > 0) {

            cycleYellow = true;
            cycleDeadlineSkipped = true;
        }

        updateDisplay();
    }

    private void waitForCycleEnd(long generation) {

        if (!isCurrentRun(generation) || !cycleActive) {
            return;
        }

        if (nextClickRunnable != null) {
            handler.removeCallbacks(nextClickRunnable);
            nextClickRunnable = null;
        }

        if (cycleFinishRunnable != null) {
            handler.removeCallbacks(cycleFinishRunnable);
            cycleFinishRunnable = null;
        }

        cycleFinishRunnable = () -> {

            cycleFinishRunnable = null;

            if (isCurrentRun(generation) && cycleActive) {
                finishCycle(generation);
            }
        };

        postAtElapsedTime(
                cycleFinishRunnable,
                cycleDeadlineElapsedMs
        );
    }

    private void finishCycle(long generation) {

        if (!isCurrentRun(generation) || !cycleActive) {
            return;
        }

        cycleFinishRunnable = null;

        accessibility = getReadyAccessibility();

        if (accessibility != null && accessibility.isGestureInProgress()) {

            cycleFinishRunnable = () -> {

                if (isCurrentRun(generation)) {
                    finishCycle(generation);
                }
            };

            handler.postDelayed(cycleFinishRunnable, GESTURE_RETRY_MS);
            return;
        }

        cycleActive = false;

        long successes = accessibility == null
                ? 0L
                : accessibility.getSuccessfulClicks();

        long failures = accessibility == null
                ? 1L
                : accessibility.getFailedClicks();

        long dispatched = accessibility == null
                ? 0L
                : accessibility.getDispatchedClicks();

        if (accessibility == null) {
            cycleOtherFailure = true;
            addLiveError("سرویس دسترسی‌پذیری هنگام پایان چرخه متصل نبود");
        }

        if (!currentCycleStatsFinalized) {

            completedSuccessfulClicks += successes;
            completedFailedClicks += failures;
            completedDispatchedClicks += dispatched;

            currentCycleStatsFinalized = true;
        }

        boolean incomplete = successes < clickCount;

        if (incomplete
                && SystemClock.elapsedRealtime() >= cycleDeadlineElapsedMs) {

            cycleYellow = true;
            cycleDeadlineSkipped = true;
        }

        final String status;

        if (cycleIntervalViolation || cycleDeadlineSkipped) {

            status = CycleReportStore.STATUS_YELLOW;

        } else if (!cycleOtherFailure
                && failures == 0
                && successes >= clickCount) {

            status = CycleReportStore.STATUS_GREEN;

        } else {

            status = CycleReportStore.STATUS_RED;
        }

        CycleReportStore.addReport(
                this,
                cycleIndex + 1,
                cycleStartWallMs,
                status,
                successes
        );

        cycleIndex++;

        updateDisplay();

        if (cycleIndex >= totalCycleCount) {

            stopLoop();

            updateCountdownStatus("همهٔ چرخه‌ها به پایان رسیدند");
            updateDisplay();

            return;
        }

        long nextTarget = getCycleTargetElapsed(cycleIndex);

        if (nextTarget == Long.MAX_VALUE) {

            stopLoop();

            updateCountdownStatus("محدودیت زمان‌بندی چرخه");
            addLiveError("زمان‌بندی چرخه بعدی از محدوده مجاز خارج شد");

            return;
        }

        cycleStartRunnable = () -> {

            if (isCurrentRun(generation)) {
                startCycle(generation);
            }
        };

        postAtElapsedTime(cycleStartRunnable, nextTarget);

        updateDisplay();
    }

    private void stopLoop() {

        running = false;
        cycleActive = false;

        runGeneration++;

        removeScheduledCallbacks();

        setMarkerTouchable(true);

        if (countdownStatusView != null) {
            updateCountdownStatus("متوقف شد");
        }

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
        updateLiveDashboard();
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
        closeDashboard();

        if (displayRunnable != null) {
            handler.removeCallbacks(displayRunnable);
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
