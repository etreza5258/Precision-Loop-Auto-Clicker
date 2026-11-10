
package com.precision.loopclicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

public class MainActivity extends Activity {

    private EditText startHourInput, startMinuteInput;
    private EditText startSecondInput, startMillisInput;

    private EditText intervalSecondsInput, intervalMillisInput;
    private EditText cycleSecondsInput, cycleMillisInput;
    private EditText clickCountInput, touchMillisInput;
    private EditText pauseSecondsInput, pauseMillisInput;

    private TextView phoneClock;
    private TextView countdownHour, countdownMinute;
    private TextView countdownSecond, countdownMillis;
    private TextView countdownText, reportSummary;

    private Button startButton, stopButton;
    private Button accessibilityButton, overlayButton;
    private Button showFloatingButton, clearReportsButton;
    private Button shutdownButton;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updatePhoneClock();
            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setDefaultValues();
        setupButtons();
        refreshReports();

        handler.post(clockRunnable);
    }

    private void bindViews() {
        startHourInput = findViewById(R.id.startHourInput);
        startMinuteInput = findViewById(R.id.startMinuteInput);
        startSecondInput = findViewById(R.id.startSecondInput);
        startMillisInput = findViewById(R.id.startMillisInput);

        intervalSecondsInput = findViewById(R.id.intervalSecondsInput);
        intervalMillisInput = findViewById(R.id.intervalMillisInput);

        cycleSecondsInput = findViewById(R.id.cycleSecondsInput);
        cycleMillisInput = findViewById(R.id.cycleMillisInput);

        clickCountInput = findViewById(R.id.clickCountInput);
        touchMillisInput = findViewById(R.id.touchMillisInput);

        pauseSecondsInput = findViewById(R.id.pauseSecondsInput);
        pauseMillisInput = findViewById(R.id.pauseMillisInput);

        phoneClock = findViewById(R.id.phoneClock);

        countdownHour = findViewById(R.id.countdownHour);
        countdownMinute = findViewById(R.id.countdownMinute);
        countdownSecond = findViewById(R.id.countdownSecond);
        countdownMillis = findViewById(R.id.countdownMillis);
        countdownText = findViewById(R.id.countdownText);

        reportSummary = findViewById(R.id.reportSummary);

        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        overlayButton = findViewById(R.id.overlayButton);
        showFloatingButton = findViewById(R.id.showFloatingButton);
        clearReportsButton = findViewById(R.id.clearReportsButton);
        shutdownButton = findViewById(R.id.shutdownButton);
    }

    private void setDefaultValues() {
        setIfEmpty(intervalSecondsInput, "0");
        setIfEmpty(intervalMillisInput, "20");

        setIfEmpty(cycleSecondsInput, "60");
        setIfEmpty(cycleMillisInput, "0");

        setIfEmpty(clickCountInput, "2");
        setIfEmpty(touchMillisInput, "5");

        setIfEmpty(pauseSecondsInput, "0");
        setIfEmpty(pauseMillisInput, "0");
    }

    private void setIfEmpty(EditText input, String value) {
        if (input.getText().toString().trim().isEmpty()) {
            input.setText(value);
        }
    }

    private void setupButtons() {
        startButton.setOnClickListener(v -> startClicking());

        stopButton.setOnClickListener(v -> stopClicking());

        accessibilityButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        overlayButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                showMessage("مجوز پنل شناور فعال است.");
            }
        });

        showFloatingButton.setOnClickListener(v -> {
            if (!hasOverlayPermission()) {
                showMessage("ابتدا مجوز پنل شناور را فعال کنید.");
                return;
            }

            Intent intent = new Intent(this, FloatingService.class);
            intent.setAction("SHOW_PANEL");
            startService(intent);
        });

        clearReportsButton.setOnClickListener(v -> {
            CycleReportStore.clearReports(this);
            refreshReports();
            showMessage("گزارش‌ها پاک شدند.");
        });

        shutdownButton.setOnClickListener(v -> {
            stopClicking();

            Intent serviceIntent =
                    new Intent(this, FloatingService.class);
            stopService(serviceIntent);

            finish();
        });
    }

    private void startClicking() {
        if (!hasAccessibilityPermission()) {
            showMessage("ابتدا دسترسی لمس را فعال کنید.");
            return;
        }

        if (!hasOverlayPermission()) {
            showMessage("ابتدا مجوز پنل شناور را فعال کنید.");
            return;
        }

        int hour = readNumber(startHourInput, "ساعت");
        int minute = readNumber(startMinuteInput, "دقیقه");
        int second = readNumber(startSecondInput, "ثانیه");
        int millis = readNumber(startMillisInput, "میلی‌ثانیه");

        long intervalSeconds =
                readNumber(intervalSecondsInput, "ثانیه فاصله کلیک");
        long intervalMillis =
                readNumber(intervalMillisInput, "میلی‌ثانیه فاصله کلیک");

        long cycleSeconds =
                readNumber(cycleSecondsInput, "ثانیه چرخه");
        long cycleMillis =
                readNumber(cycleMillisInput, "میلی‌ثانیه چرخه");

        int clickCount = readNumber(clickCountInput, "تعداد کلیک");
        int touchMillis = readNumber(touchMillisInput, "مدت لمس");

        long pauseSeconds =
                readNumber(pauseSecondsInput, "ثانیه مکث");
        long pauseMillis =
                readNumber(pauseMillisInput, "میلی‌ثانیه مکث");

        if (hour < 0 || hour > 23
                || minute < 0 || minute > 59
                || second < 0 || second > 59
                || millis < 0 || millis > 999) {
            showMessage("زمان شروع معتبر نیست.");
            return;
        }

        if (intervalSeconds < 0 || intervalMillis < 0
                || intervalMillis > 999) {
            showMessage("فاصله کلیک معتبر نیست.");
            return;
        }

        if (cycleSeconds < 0 || cycleMillis < 0
                || cycleMillis > 999
                || (cycleSeconds == 0 && cycleMillis == 0)) {
            showMessage("مدت چرخه باید بیشتر از صفر باشد.");
            return;
        }

        if (clickCount < 1 || clickCount > 100000) {
            showMessage("تعداد کلیک باید بین ۱ تا ۱۰۰۰۰۰ باشد.");
            return;
        }

        if (touchMillis < 1 || touchMillis > 60000) {
            showMessage("مدت لمس باید بین ۱ تا ۶۰۰۰۰ میلی‌ثانیه باشد.");
            return;
        }

        if (pauseSeconds < 0 || pauseMillis < 0
                || pauseMillis > 999) {
            showMessage("مدت مکث معتبر نیست.");
            return;
        }

        long intervalDuration =
                intervalSeconds * 1000L + intervalMillis;

        long cycleDuration =
                cycleSeconds * 1000L + cycleMillis;

        long pauseDuration =
                pauseSeconds * 1000L + pauseMillis;

        Calendar startCalendar = Calendar.getInstance();
        startCalendar.set(Calendar.HOUR_OF_DAY, hour);
        startCalendar.set(Calendar.MINUTE, minute);
        startCalendar.set(Calendar.SECOND, second);
        startCalendar.set(Calendar.MILLISECOND, millis);

        long startTime = startCalendar.getTimeInMillis();

        if (startTime <= System.currentTimeMillis()) {
            showMessage("زمان شروع باید در آینده باشد.");
            return;
        }

        Intent intent = new Intent(this, FloatingService.class);
        intent.setAction("ACTION_START");

        intent.putExtra("start_time_epoch_ms", startTime);
        intent.putExtra("cycle_duration", cycleDuration);
        intent.putExtra("click_count", clickCount);
        intent.putExtra("touch_duration", touchMillis);
        intent.putExtra("pause_duration", pauseDuration);
        intent.putExtra("click_interval_ms", intervalDuration);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        countdownText.setText("زمان‌بندی ارسال شد.");
        showMessage("زمان‌بندی اجرا ثبت شد.");
    }

    private int readNumber(EditText input, String fieldName) {
        String value = input.getText().toString().trim();

        if (value.isEmpty()) {
            showMessage("مقدار «" + fieldName + "» را وارد کنید.");
            throw new IllegalArgumentException(fieldName);
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            showMessage("مقدار «" + fieldName + "» معتبر نیست.");
            throw new IllegalArgumentException(fieldName);
        }
    }

    private void stopClicking() {
        Intent intent = new Intent(this, FloatingService.class);
        intent.setAction("ACTION_STOP");
        startService(intent);

        countdownText.setText("متوقف شد");
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
    }

    private boolean hasAccessibilityPermission() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (enabledServices == null) return false;

        return enabledServices.toLowerCase().contains(
                getPackageName().toLowerCase());
    }

    private void updatePhoneClock() {
        long now = System.currentTimeMillis();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);

        String time = String.format(
                java.util.Locale.US,
                "ساعت گوشی: %02d:%02d:%02d.%03d",
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND),
                calendar.get(Calendar.MILLISECOND));

        phoneClock.setText(time);
    }

    private void refreshReports() {
        reportSummary.setText(
                CycleReportStore.getReportSummary(this));
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReports();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(clockRunnable);
        super.onDestroy();
    }
}
