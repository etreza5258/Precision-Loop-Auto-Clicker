
package com.precision.loopclicker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final ZoneId IRAN_ZONE =
            ZoneId.of("Asia/Tehran");

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

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService timeExecutor =
            Executors.newSingleThreadExecutor();

    private volatile long trustedEpochAtSync = 0L;
    private volatile long elapsedAtSync = 0L;
    private volatile boolean timeSynced = false;
    private volatile boolean syncInProgress = false;

    private long scheduledStartTime = 0L;
    private boolean countdownActive = false;

    private long lastReportRefreshTime = 0L;

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            syncInternetTime();
            handler.postDelayed(this, 60000L);
        }
    };

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {

            updateIranClock();
            updateCountdown();

            long now = SystemClock.elapsedRealtime();

            if (now - lastReportRefreshTime >= 1000L) {
                refreshReports();
                lastReportRefreshTime = now;
            }

            handler.postDelayed(this, 30L);
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
        handler.post(syncRunnable);
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
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
            );
            startActivity(intent);
        });

        overlayButton.setOnClickListener(v -> {

            if (!hasOverlayPermission()) {

                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );

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

            Intent intent = new Intent(
                    this,
                    FloatingService.class
            );

            intent.setAction("SHOW_PANEL");

            try {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }

            } catch (Exception e) {
                showMessage("نمایش پنل شناور ناموفق بود.");
            }
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

    // دریافت زمان از سرور اینترنتی
    private void syncInternetTime() {

        if (syncInProgress) {
            return;
        }

        syncInProgress = true;

        timeExecutor.execute(() -> {

            HttpURLConnection connection = null;

            try {

                long requestStart = SystemClock.elapsedRealtime();

                URL url = new URL(
                        "https://www.google.com/generate_204"
                );

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);

                connection.connect();

                long serverDate = connection.getHeaderFieldDate(
                        "Date",
                        -1L
                );

                long requestEnd = SystemClock.elapsedRealtime();

                if (serverDate <= 0L) {
                    throw new IOException("زمان سرور دریافت نشد.");
                }

                // تاریخ HTTP دقت ثانیه‌ای دارد؛
                // نصف زمان رفت‌وبرگشت برای تخمین زمان دریافت لحاظ می‌شود.
                long midpoint =
                        requestStart + (requestEnd - requestStart) / 2L;

                long estimatedServerTime =
                        serverDate + (requestEnd - midpoint);

                trustedEpochAtSync = estimatedServerTime;
                elapsedAtSync = requestEnd;
                timeSynced = true;

                handler.post(() -> {
                    updateIranClock();
                });

            } catch (Exception e) {

                handler.post(() -> {
                    if (!timeSynced) {
                        phoneClock.setText(
                                "ساعت ایران: در انتظار اتصال به اینترنت"
                        );
                    }
                });

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }

                syncInProgress = false;
            }
        });
    }

    // زمان فعلی بر اساس ساعت همگام‌شده
    private long getTrustedNow() {

        if (!timeSynced) {
            return 0L;
        }

        long elapsedNow = SystemClock.elapsedRealtime();

        return trustedEpochAtSync
                + (elapsedNow - elapsedAtSync);
    }

    private void updateIranClock() {

        if (!timeSynced) {

            phoneClock.setText(
                    "ساعت ایران: در حال همگام‌سازی..."
            );

            return;
        }

        long now = getTrustedNow();

        ZonedDateTime iranTime =
                Instant.ofEpochMilli(now).atZone(IRAN_ZONE);

        String time = String.format(
                Locale.US,
                "ساعت ایران: %02d:%02d:%02d.%03d  |  همگام‌شده",
                iranTime.getHour(),
                iranTime.getMinute(),
                iranTime.getSecond(),
                iranTime.getNano() / 1000000
        );

        phoneClock.setText(time);
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

        if (!timeSynced) {
            syncInternetTime();
            showMessage("ابتدا منتظر همگام‌سازی ساعت ایران بمانید.");
            return;
        }

        Long hourValue = readLong(startHourInput, "ساعت");
        Long minuteValue = readLong(startMinuteInput, "دقیقه");
        Long secondValue = readLong(startSecondInput, "ثانیه");
        Long millisValue = readLong(startMillisInput, "میلی‌ثانیه");

        Long intervalSecondsValue =
                readLong(intervalSecondsInput, "ثانیه فاصله کلیک");

        Long intervalMillisValue =
                readLong(intervalMillisInput, "میلی‌ثانیه فاصله کلیک");

        Long cycleSecondsValue =
                readLong(cycleSecondsInput, "ثانیه چرخه");

        Long cycleMillisValue =
                readLong(cycleMillisInput, "میلی‌ثانیه چرخه");

        Long clickCountValue =
                readLong(clickCountInput, "تعداد کلیک");

        Long touchMillisValue =
                readLong(touchMillisInput, "مدت لمس");

        Long pauseSecondsValue =
                readLong(pauseSecondsInput, "ثانیه مکث");

        Long pauseMillisValue =
                readLong(pauseMillisInput, "میلی‌ثانیه مکث");

        if (hourValue == null || minuteValue == null
                || secondValue == null || millisValue == null
                || intervalSecondsValue == null
                || intervalMillisValue == null
                || cycleSecondsValue == null
                || cycleMillisValue == null
                || clickCountValue == null
                || touchMillisValue == null
                || pauseSecondsValue == null
                || pauseMillisValue == null) {
            return;
        }

        long hour = hourValue;
        long minute = minuteValue;
        long second = secondValue;
        long millis = millisValue;

        long intervalSeconds = intervalSecondsValue;
        long intervalMillis = intervalMillisValue;

        long cycleSeconds = cycleSecondsValue;
        long cycleMillis = cycleMillisValue;

        long clickCount = clickCountValue;
        long touchMillis = touchMillisValue;

        long pauseSeconds = pauseSecondsValue;
        long pauseMillis = pauseMillisValue;

        if (hour > 23 || minute > 59
                || second > 59 || millis > 999) {
            showMessage("زمان شروع معتبر نیست.");
            return;
        }

        if (intervalMillis > 999) {
            showMessage("میلی‌ثانیه فاصله باید بین ۰ تا ۹۹۹ باشد.");
            return;
        }

        if (cycleMillis > 999
                || (cycleSeconds == 0 && cycleMillis == 0)) {
            showMessage("مدت چرخه معتبر نیست.");
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

        if (pauseMillis > 999) {
            showMessage("میلی‌ثانیه مکث باید بین ۰ تا ۹۹۹ باشد.");
            return;
        }

        if (intervalSeconds >
                (Long.MAX_VALUE - intervalMillis) / 1000L
                || cycleSeconds >
                (Long.MAX_VALUE - cycleMillis) / 1000L
                || pauseSeconds >
                (Long.MAX_VALUE - pauseMillis) / 1000L) {

            showMessage("یکی از زمان‌ها بیش از حد بزرگ است.");
            return;
        }

        long intervalDuration =
                intervalSeconds * 1000L + intervalMillis;

        long cycleDuration =
                cycleSeconds * 1000L + cycleMillis;

        long pauseDuration =
                pauseSeconds * 1000L + pauseMillis;

        long trustedNow = getTrustedNow();

        ZonedDateTime iranNow =
                Instant.ofEpochMilli(trustedNow).atZone(IRAN_ZONE);

        LocalDate iranDate = iranNow.toLocalDate();

        LocalDateTime requestedDateTime =
                iranDate.atTime(
                        (int) hour,
                        (int) minute,
                        (int) second,
                        (int) millis * 1000000
                );

        long startTime = requestedDateTime
                .atZone(IRAN_ZONE)
                .toInstant()
                .toEpochMilli();

        if (startTime <= trustedNow) {
            showMessage("زمان شروع باید از ساعت فعلی ایران جلوتر باشد.");
            return;
        }

        long startDelay = startTime - trustedNow;

        // سازگاری با FloatingService فعلی که از ساعت گوشی استفاده می‌کند:
        // زمان معادل روی ساعت گوشی را می‌فرستیم تا تأخیر شروع درست بماند.
        long serviceStartTime =
                System.currentTimeMillis() + startDelay;

        Intent intent = new Intent(
                this,
                FloatingService.class
        );

        intent.setAction("ACTION_START");

        intent.putExtra("start_time_epoch_ms", serviceStartTime);
        intent.putExtra("start_delay_ms", startDelay);

        intent.putExtra("cycle_duration", cycleDuration);
        intent.putExtra("click_count", (int) clickCount);
        intent.putExtra("touch_duration", (int) touchMillis);
        intent.putExtra("pause_duration", pauseDuration);
        intent.putExtra("click_interval_ms", intervalDuration);

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            scheduledStartTime = startTime;
            countdownActive = true;

            countdownText.setText("در انتظار زمان شروع");

            updateCountdown();

            showMessage("زمان‌بندی بر اساس ساعت ایران ثبت شد.");

        } catch (Exception e) {

            countdownActive = false;

            showMessage(
                    "شروع سرویس ناموفق بود: " + e.getMessage()
            );
        }
    }

    private Long readLong(EditText input, String fieldName) {

        String value = input.getText().toString().trim();

        if (value.isEmpty()) {
            showMessage("مقدار «" + fieldName + "» را وارد کنید.");
            input.requestFocus();
            return null;
        }

        try {

            long number = Long.parseLong(value);

            if (number < 0) {
                showMessage(
                        "مقدار «" + fieldName
                                + "» نمی‌تواند منفی باشد."
                );
                input.requestFocus();
                return null;
            }

            return number;

        } catch (NumberFormatException e) {

            showMessage("مقدار «" + fieldName + "» معتبر نیست.");
            input.requestFocus();
            return null;
        }
    }

    private void stopClicking() {

        countdownActive = false;
        scheduledStartTime = 0L;

        setCountdownValues(0, 0, 0, 0);
        countdownText.setText("متوقف شد");

        Intent intent = new Intent(
                this,
                FloatingService.class
        );

        intent.setAction("ACTION_STOP");

        try {
            startService(intent);
        } catch (Exception ignored) {
        }
    }

    private void updateCountdown() {

        if (!countdownActive || scheduledStartTime == 0L) {
            return;
        }

        if (!timeSynced) {
            countdownText.setText("در انتظار همگام‌سازی ساعت ایران");
            return;
        }

        long remaining =
                scheduledStartTime - getTrustedNow();

        if (remaining <= 0L) {

            setCountdownValues(0, 0, 0, 0);

            countdownText.setText("زمان شروع فرا رسیده است.");

            countdownActive = false;
            return;
        }

        long hours = remaining / 3600000L;
        long minutes = (remaining % 3600000L) / 60000L;
        long seconds = (remaining % 60000L) / 1000L;
        long milliseconds = remaining % 1000L;

        setCountdownValues(
                hours,
                minutes,
                seconds,
                milliseconds
        );

        countdownText.setText("زمان باقی‌مانده تا شروع");
    }

    private void setCountdownValues(
            long hours,
            long minutes,
            long seconds,
            long milliseconds
    ) {

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
                String.format(Locale.US, "%03d", milliseconds)
        );
    }

    private boolean hasOverlayPermission() {

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
    }

    private boolean hasAccessibilityPermission() {

        String enabledServices = Secure.getString(
                getContentResolver(),
                Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabledServices == null) {
            return false;
        }

        return enabledServices.toLowerCase(Locale.ROOT)
                .contains(getPackageName().toLowerCase(Locale.ROOT));
    }

    private void refreshReports() {

        reportSummary.setText(
                CycleReportStore.getReportSummary(this)
        );
    }

    private void showMessage(String message) {

        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        refreshReports();
        lastReportRefreshTime = SystemClock.elapsedRealtime();

        if (!timeSynced) {
            syncInternetTime();
        }
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(clockRunnable);
        handler.removeCallbacks(syncRunnable);

        timeExecutor.shutdownNow();

        super.onDestroy();
    }
}
