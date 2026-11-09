
package com.precision.loopclicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private EditText startTimeInput;
    private EditText cycleSecondsInput;
    private EditText cycleMillisInput;
    private EditText clickCountInput;
    private EditText touchMillisInput;
    private EditText pauseSecondsInput;
    private EditText pauseMillisInput;

    private TextView phoneClock;
    private TextView countdownText;
    private TextView reportSummary;

    private final Handler handler = new Handler();

    private static final String ACTION_START =
            "com.precision.loopclicker.START";

    private static final String ACTION_STOP =
            "com.precision.loopclicker.STOP";

    private static final String ACTION_SHUTDOWN =
            "com.precision.loopclicker.SHUTDOWN";

    private static final Pattern TIME_PATTERN =
            Pattern.compile("^(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})$");

    private final java.text.SimpleDateFormat clockFormat =
            new java.text.SimpleDateFormat(
                    "HH:mm:ss.SSS",
                    Locale.getDefault()
            );

    private final Runnable clockUpdater = new Runnable() {
        @Override
        public void run() {

            if (phoneClock != null) {
                phoneClock.setText(
                        "ساعت گوشی: "
                                + clockFormat.format(
                                        new java.util.Date()
                                )
                );
            }

            handler.postDelayed(this, 20);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        initializeViews();
        initializeButtons();

        refreshReports();
        requestNotificationPermission();

        handler.post(clockUpdater);
    }

    private void initializeViews() {

        startTimeInput = findViewById(R.id.startTimeInput);

        cycleSecondsInput = findViewById(R.id.cycleSecondsInput);
        cycleMillisInput = findViewById(R.id.cycleMillisInput);
        clickCountInput = findViewById(R.id.clickCountInput);
        touchMillisInput = findViewById(R.id.touchMillisInput);
        pauseSecondsInput = findViewById(R.id.pauseSecondsInput);
        pauseMillisInput = findViewById(R.id.pauseMillisInput);

        phoneClock = findViewById(R.id.phoneClock);
        countdownText = findViewById(R.id.countdownText);
        reportSummary = findViewById(R.id.reportSummary);

        startTimeInput.setText("");

        cycleSecondsInput.setText("1");
        cycleMillisInput.setText("0");
        clickCountInput.setText("20");
        touchMillisInput.setText("5");
        pauseSecondsInput.setText("0");
        pauseMillisInput.setText("0");
    }

    private void initializeButtons() {

        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        Button accessibilityButton =
                findViewById(R.id.accessibilityButton);

        Button overlayButton =
                findViewById(R.id.overlayButton);

        Button floatingButton =
                findViewById(R.id.showFloatingButton);

        Button clearReportsButton =
                findViewById(R.id.clearReportsButton);

        Button shutdownButton =
                findViewById(R.id.shutdownButton);

        startButton.setOnClickListener(v -> startClicking());

        stopButton.setOnClickListener(v -> stopClicking());

        accessibilityButton.setOnClickListener(v ->
                openAccessibilitySettings()
        );

        overlayButton.setOnClickListener(v ->
                requestOverlayPermission()
        );

        floatingButton.setOnClickListener(v ->
                showFloatingPanel()
        );

        clearReportsButton.setOnClickListener(v -> {

            CycleReportStore.clearReports(this);

            refreshReports();

            Toast.makeText(
                    this,
                    "تمام گزارش‌ها پاک شدند",
                    Toast.LENGTH_SHORT
            ).show();
        });

        shutdownButton.setOnClickListener(v ->
                shutdownApplication()
        );
    }

    private long parseStartTime(String value)
            throws IllegalArgumentException {

        Matcher matcher = TIME_PATTERN.matcher(value);

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "زمان را با قالب HH:mm:ss.SSS وارد کنید"
            );
        }

        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = Integer.parseInt(matcher.group(3));
        int millisecond = Integer.parseInt(matcher.group(4));

        if (hour > 23 || minute > 59 || second > 59) {
            throw new IllegalArgumentException(
                    "ساعت واردشده معتبر نیست"
            );
        }

        Calendar calendar = Calendar.getInstance();

        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, millisecond);

        long targetTime = calendar.getTimeInMillis();

        if (targetTime <= System.currentTimeMillis()) {
            throw new IllegalArgumentException(
                    "زمان واردشده گذشته است. ساعت آینده را وارد کنید."
            );
        }

        return targetTime;
    }

    private void startClicking() {

        if (!isAccessibilityEnabled()) {

            showError("ابتدا دسترسی Accessibility را فعال کنید");

            openAccessibilitySettings();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {

            showError("ابتدا مجوز پنل شناور را فعال کنید");

            requestOverlayPermission();
            return;
        }

        try {

            String startTimeText =
                    startTimeInput.getText().toString().trim();

            if (startTimeText.isEmpty()) {
                showError("زمان دقیق شروع را وارد کنید");
                return;
            }

            long startTimeEpoch = parseStartTime(startTimeText);

            long cycleSeconds = readValue(cycleSecondsInput);
            long cycleMillis = readValue(cycleMillisInput);

            long clickCountValue = readValue(clickCountInput);

            long touchMillis = readValue(touchMillisInput);

            long pauseSeconds = readValue(pauseSecondsInput);
            long pauseMillis = readValue(pauseMillisInput);

            if (cycleSeconds < 0 || cycleMillis < 0
                    || cycleMillis > 999) {

                showError("مقدار زمان چرخه معتبر نیست");
                return;
            }

            if (pauseSeconds < 0 || pauseMillis < 0
                    || pauseMillis > 999) {

                showError("مقدار مکث معتبر نیست");
                return;
            }

            if (clickCountValue <= 0 || clickCountValue > 100000) {

                showError("تعداد لمس باید بین ۱ تا ۱۰۰۰۰۰ باشد");
                return;
            }

            if (touchMillis < 1 || touchMillis > 60000) {

                showError(
                        "مدت لمس باید بین ۱ تا ۶۰۰۰۰ میلی‌ثانیه باشد"
                );
                return;
            }

            if (cycleSeconds >
                    (Long.MAX_VALUE - cycleMillis) / 1000L) {

                showError("زمان چرخه بیش از حد بزرگ است");
                return;
            }

            if (pauseSeconds >
                    (Long.MAX_VALUE - pauseMillis) / 1000L) {

                showError("زمان مکث بیش از حد بزرگ است");
                return;
            }

            long cycleDuration =
                    cycleSeconds * 1000L + cycleMillis;

            long pauseDuration =
                    pauseSeconds * 1000L + pauseMillis;

            int clickCount = (int) clickCountValue;

            if (cycleDuration <= 0) {

                showError("زمان چرخه باید بیشتر از صفر باشد");
                return;
            }

            if (touchMillis > cycleDuration / clickCount) {

                showError(
                        "زمان لمس برای تعداد کلیک و مدت چرخه زیاد است"
                );
                return;
            }

            Intent intent = new Intent(
                    this,
                    FloatingService.class
            );

            intent.setAction(ACTION_START);

            intent.putExtra("start_time_epoch_ms", startTimeEpoch);
            intent.putExtra("cycle_duration", cycleDuration);
            intent.putExtra("click_count", clickCount);
            intent.putExtra("touch_duration", touchMillis);
            intent.putExtra("pause_duration", pauseDuration);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            countdownText.setText(
                    "منتظر زمان شروع: " + startTimeText
            );

        } catch (NumberFormatException e) {

            showError("لطفاً تمام مقادیر عددی را صحیح وارد کنید");

        } catch (IllegalArgumentException e) {

            showError(e.getMessage());

        } catch (Exception e) {

            showError("خطا در شروع برنامه: " + e.getMessage());
        }
    }

    private long readValue(EditText input) {

        String value = input.getText().toString().trim();

        if (value.isEmpty()) {
            return 0;
        }

        return Long.parseLong(value);
    }

    private void stopClicking() {

        Intent intent = new Intent(this, FloatingService.class);
        intent.setAction(ACTION_STOP);

        startService(intent);

        countdownText.setText("کلیک‌ها متوقف شدند");
    }

    private void showFloatingPanel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {

            requestOverlayPermission();
            return;
        }

        Intent intent = new Intent(this, FloatingService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(
                this,
                "درخواست نمایش پنل ارسال شد",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void shutdownApplication() {

        Intent intent = new Intent(this, FloatingService.class);
        intent.setAction(ACTION_SHUTDOWN);

        startService(intent);

        finishAffinity();
    }

    private void openAccessibilitySettings() {

        Intent intent = new Intent(
                Settings.ACTION_ACCESSIBILITY_SETTINGS
        );

        startActivity(intent);
    }

    private boolean isAccessibilityEnabled() {

        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabledServices == null) {
            return false;
        }

        return enabledServices.toLowerCase(Locale.ROOT)
                .contains(getPackageName().toLowerCase(Locale.ROOT));
    }

    private void requestOverlayPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );

            startActivity(intent);
        }
    }

    private void requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    1001
            );
        }
    }

    private void refreshReports() {

        List<String> reports =
                CycleReportStore.getReports(this);

        if (reports.isEmpty()) {

            reportSummary.setText(
                    "هنوز گزارشی ثبت نشده است."
            );

            return;
        }

        StringBuilder builder = new StringBuilder();

        builder.append("تعداد گزارش‌ها: ")
                .append(reports.size())
                .append(" از ۲۰\n\n");

        for (String report : reports) {

            builder.append(report)
                    .append("\n")
                    .append("------------------------")
                    .append("\n\n");
        }

        reportSummary.setText(builder.toString());
    }

    private void showError(String message) {

        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        refreshReports();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        handler.removeCallbacks(clockUpdater);
    }
}
