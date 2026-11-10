
package com.precision.loopclicker;

import android.app.Activity;
import android.content.ComponentName;
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
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final ZoneId IRAN_ZONE =
            ZoneId.of("Asia/Tehran");

    private static final String[] NTP_SERVERS = {
            "time.google.com",
            "time.cloudflare.com",
            "pool.ntp.org"
    };

    private static final int NTP_PORT = 123;
    private static final int NTP_TIMEOUT_MS = 1800;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor();

    private EditText startHourInput;
    private EditText startMinuteInput;
    private EditText startSecondInput;
    private EditText startMillisInput;

    private EditText intervalSecondsInput;
    private EditText intervalMillisInput;

    private EditText cycleSecondsInput;
    private EditText cycleMillisInput;
    private EditText clickCountInput;
    private EditText touchMillisInput;

    private EditText pauseSecondsInput;
    private EditText pauseMillisInput;

    private TextView phoneClock;
    private TextView internetClock;
    private TextView clockDifference;
    private TextView syncStatus;

    private TextView countdownHour;
    private TextView countdownMinute;
    private TextView countdownSecond;
    private TextView countdownMillis;
    private TextView countdownText;

    private TextView reportSummary;

    private RadioGroup clockSourceGroup;

    private Button startButton;
    private Button stopButton;
    private Button accessibilityButton;
    private Button overlayButton;
    private Button showFloatingButton;
    private Button clearReportsButton;
    private Button shutdownButton;

    private volatile long trustedEpochAtSync = 0;
    private volatile long elapsedAtSync = 0;

    private volatile boolean timeSynced = false;
    private volatile boolean syncInProgress = false;

    private volatile long syncUncertaintyMs = 0;
    private volatile long lastSyncElapsed = 0;

    private boolean countdownActive = false;
    private long scheduledStartElapsed = 0;

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClocks();
            updateCountdown();

            if (!syncInProgress) {
                long now = SystemClock.elapsedRealtime();

                if (!timeSynced
                        || now - lastSyncElapsed >= 60000L) {
                    syncInternetTime();
                }
            }

            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        bindViews();
        setupButtons();

        refreshReports();
        updateClocks();
        syncInternetTime();

        handler.post(clockRunnable);

        // اگر صفحه از طریق دکمه شروع پنل شناور باز شده باشد،
        // پس از آماده‌شدن تمام Viewها فرمان اجرا می‌شود.
        handleFloatingStartIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        setIntent(intent);

        // وقتی Activity از قبل باز است،
        // فرمان جدید از این مسیر دریافت می‌شود.
        handleFloatingStartIntent(intent);
    }

    private void handleFloatingStartIntent(Intent intent) {

        if (intent == null) {
            return;
        }

        if (!FloatingService.ACTION_START_FROM_FLOATING.equals(
                intent.getAction()
        )) {
            return;
        }

        // ابتدا اطلاعات زمان انتخاب‌شده در پنل شناور را می‌خوانیم.
        boolean floatingTimeConfigured =
                intent.getBooleanExtra(
                        "floating_start_time_configured",
                        false
                );

        int floatingHour =
                intent.getIntExtra(
                        "floating_start_hour",
                        0
                );

        int floatingMinute =
                intent.getIntExtra(
                        "floating_start_minute",
                        0
                );

        int floatingSecond =
                intent.getIntExtra(
                        "floating_start_second",
                        0
                );

        int floatingMillis =
                intent.getIntExtra(
                        "floating_start_millis",
                        0
                );

        // جلوگیری از اجرای دوباره همین فرمان.
        intent.setAction(null);

        if (startButton == null
                || clockSourceGroup == null
                || startHourInput == null
                || startMinuteInput == null
                || startSecondInput == null
                || startMillisInput == null) {

            Toast.makeText(
                    this,
                    "صفحه اصلی هنوز آماده نیست.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        // اگر زمان از پنل شناور انتخاب شده باشد،
        // آن را در ورودی‌های زمان صفحه اصلی قرار می‌دهیم.
        if (floatingTimeConfigured) {

            startHourInput.setText(
                    String.valueOf(floatingHour)
            );

            startMinuteInput.setText(
                    String.valueOf(floatingMinute)
            );

            startSecondInput.setText(
                    String.valueOf(floatingSecond)
            );

            startMillisInput.setText(
                    String.valueOf(floatingMillis)
            );
        }

        // ادامه اجرا با همان تنظیمات و منطق قبلی.
        startClicking();
    }

    private void bindViews() {

        startHourInput = findViewById(R.id.startHourInput);
        startMinuteInput = findViewById(R.id.startMinuteInput);
        startSecondInput = findViewById(R.id.startSecondInput);
        startMillisInput = findViewById(R.id.startMillisInput);

        intervalSecondsInput =
                findViewById(R.id.intervalSecondsInput);

        intervalMillisInput =
                findViewById(R.id.intervalMillisInput);

        cycleSecondsInput =
                findViewById(R.id.cycleSecondsInput);

        cycleMillisInput =
                findViewById(R.id.cycleMillisInput);

        clickCountInput =
                findViewById(R.id.clickCountInput);

        touchMillisInput =
                findViewById(R.id.touchMillisInput);

        pauseSecondsInput =
                findViewById(R.id.pauseSecondsInput);

        pauseMillisInput =
                findViewById(R.id.pauseMillisInput);

        phoneClock = findViewById(R.id.phoneClock);
        internetClock = findViewById(R.id.internetClock);
        clockDifference = findViewById(R.id.clockDifference);
        syncStatus = findViewById(R.id.syncStatus);

        countdownHour = findViewById(R.id.countdownHour);
        countdownMinute = findViewById(R.id.countdownMinute);
        countdownSecond = findViewById(R.id.countdownSecond);
        countdownMillis = findViewById(R.id.countdownMillis);
        countdownText = findViewById(R.id.countdownText);

        reportSummary = findViewById(R.id.reportSummary);

        clockSourceGroup = findViewById(R.id.clockSourceGroup);

        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        overlayButton = findViewById(R.id.overlayButton);
        showFloatingButton = findViewById(R.id.showFloatingButton);
        clearReportsButton = findViewById(R.id.clearReportsButton);
        shutdownButton = findViewById(R.id.shutdownButton);
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

        overlayButton.setOnClickListener(v -> openOverlaySettings());

        showFloatingButton.setOnClickListener(v -> showFloatingPanel());

        clearReportsButton.setOnClickListener(v -> {
            CycleReportStore.clearReports(this);
            refreshReports();

            Toast.makeText(
                    this,
                    "گزارش‌ها پاک شدند",
                    Toast.LENGTH_SHORT
            ).show();
        });

        shutdownButton.setOnClickListener(v -> shutdownApplication());
    }

    // ---------------------------------------------------------
    // ساعت گوشی و ساعت اینترنتی
    // ---------------------------------------------------------

    private void updateClocks() {

        long phoneEpoch = System.currentTimeMillis();

        phoneClock.setText(
                "ساعت گوشی: " + formatIranTime(phoneEpoch)
        );

        if (timeSynced) {

            long internetEpoch = getTrustedNow();

            internetClock.setText(
                    "ساعت اینترنتی ایران: "
                            + formatIranTime(internetEpoch)
            );

            // اختلاف همیشه گوشی منهای اینترنت است.
            long difference = phoneEpoch - internetEpoch;

            clockDifference.setText(
                    "اختلاف ساعت گوشی با اینترنت: "
                            + String.format(
                                    Locale.US,
                                    "%+d ms",
                                    difference
                            )
            );

            syncStatus.setText(
                    "همگام‌سازی موفق | عدم‌قطعیت تقریبی: ±"
                            + syncUncertaintyMs
                            + " ms"
            );

        } else {

            internetClock.setText(
                    "ساعت اینترنتی ایران: در انتظار همگام‌سازی"
            );

            clockDifference.setText(
                    "اختلاف ساعت گوشی با اینترنت: -- ms"
            );

            if (syncInProgress) {
                syncStatus.setText(
                        "در حال دریافت زمان اینترنتی..."
                );
            } else {
                syncStatus.setText(
                        "زمان اینترنتی هنوز دریافت نشده است"
                );
            }
        }
    }

    private String formatIranTime(long epochMillis) {

        SimpleDateFormat format = new SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.US
        );

        format.setTimeZone(
                TimeZone.getTimeZone("Asia/Tehran")
        );

        return format.format(new Date(epochMillis));
    }

    private long getTrustedNow() {

        if (!timeSynced) {
            return 0;
        }

        long elapsedNow = SystemClock.elapsedRealtime();

        return trustedEpochAtSync
                + (elapsedNow - elapsedAtSync);
    }

    // ---------------------------------------------------------
    // همگام‌سازی NTP
    // ---------------------------------------------------------

    private void syncInternetTime() {

        if (syncInProgress) {
            return;
        }

        syncInProgress = true;
        handler.post(this::updateClocks);

        networkExecutor.execute(() -> {

            NtpResult bestResult = null;

            for (String server : NTP_SERVERS) {

                try {
                    NtpResult result = queryNtpServer(server);

                    if (result != null) {

                        if (bestResult == null
                                || result.roundTripMs
                                < bestResult.roundTripMs) {

                            bestResult = result;
                        }
                    }

                } catch (Exception ignored) {
                }
            }

            final NtpResult finalResult = bestResult;

            runOnUiThread(() -> {

                syncInProgress = false;

                if (finalResult != null) {

                    trustedEpochAtSync =
                            finalResult.estimatedEpochMs;

                    elapsedAtSync =
                            finalResult.receivedElapsedMs;

                    syncUncertaintyMs =
                            Math.max(
                                    1,
                                    (finalResult.roundTripMs + 1) / 2
                            );

                    lastSyncElapsed =
                            finalResult.receivedElapsedMs;

                    timeSynced = true;

                    syncStatus.setText(
                            "همگام‌سازی موفق از "
                                    + finalResult.server
                                    + " | عدم‌قطعیت تقریبی: ±"
                                    + syncUncertaintyMs
                                    + " ms"
                    );

                } else {

                    if (!timeSynced) {
                        syncStatus.setText(
                                "دریافت زمان اینترنتی ناموفق بود؛ "
                                        + "اتصال اینترنت و دسترسی NTP را بررسی کن."
                        );
                    } else {
                        syncStatus.setText(
                                "اتصال مجدد ناموفق؛ "
                                        + "آخرین زمان دریافت‌شده استفاده می‌شود."
                        );
                    }
                }

                updateClocks();
            });
        });
    }

    private NtpResult queryNtpServer(String server)
            throws IOException {

        DatagramSocket socket = new DatagramSocket();

        try {

            socket.setSoTimeout(NTP_TIMEOUT_MS);

            InetAddress address = InetAddress.getByName(server);

            byte[] request = new byte[48];

            // NTP version 4, client mode
            request[0] = 0x23;

            DatagramPacket requestPacket =
                    new DatagramPacket(
                            request,
                            request.length,
                            address,
                            NTP_PORT
                    );

            long sendElapsed =
                    SystemClock.elapsedRealtime();

            socket.send(requestPacket);

            byte[] response = new byte[48];

            DatagramPacket responsePacket =
                    new DatagramPacket(
                            response,
                            response.length
                    );

            socket.receive(responsePacket);

            long receiveElapsed =
                    SystemClock.elapsedRealtime();

            long roundTripMs = Math.max(
                    0,
                    receiveElapsed - sendElapsed
            );

            if (responsePacket.getLength() < 48) {
                return null;
            }

            int mode = response[0] & 0x07;
            int stratum = response[1] & 0xFF;

            if (mode != 4 && mode != 5) {
                return null;
            }

            if (stratum < 1 || stratum > 15) {
                return null;
            }

            long seconds = readUnsignedInt(response, 40);
            long fraction = readUnsignedInt(response, 44);

            if (seconds == 0) {
                return null;
            }

            long unixSeconds = seconds - 2208988800L;

            long fractionMillis =
                    (fraction * 1000L) >>> 32;

            long serverTransmitEpoch =
                    unixSeconds * 1000L + fractionMillis;

            long estimatedEpoch =
                    serverTransmitEpoch + roundTripMs / 2L;

            if (estimatedEpoch < 1577836800000L) {
                return null;
            }

            return new NtpResult(
                    server,
                    estimatedEpoch,
                    receiveElapsed,
                    roundTripMs
            );

        } finally {
            socket.close();
        }
    }

    private long readUnsignedInt(byte[] data, int offset) {

        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | ((long) (data[offset + 3] & 0xFF));
    }

    private static class NtpResult {

        final String server;
        final long estimatedEpochMs;
        final long receivedElapsedMs;
        final long roundTripMs;

        NtpResult(
                String server,
                long estimatedEpochMs,
                long receivedElapsedMs,
                long roundTripMs
        ) {
            this.server = server;
            this.estimatedEpochMs = estimatedEpochMs;
            this.receivedElapsedMs = receivedElapsedMs;
            this.roundTripMs = roundTripMs;
        }
    }

    // ---------------------------------------------------------
    // شروع اجرای لوپی
    // ---------------------------------------------------------

    private void startClicking() {

        if (!isAccessibilityEnabled()) {

            Toast.makeText(
                    this,
                    "ابتدا دسترسی لمس را فعال کن.",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        if (!Settings.canDrawOverlays(this)) {

            Toast.makeText(
                    this,
                    "ابتدا مجوز پنل شناور را فعال کن.",
                    Toast.LENGTH_LONG
            ).show();

            openOverlaySettings();
            return;
        }

        boolean useInternetClock =
                clockSourceGroup.getCheckedRadioButtonId()
                        == R.id.radioInternetClock;

        if (useInternetClock && !timeSynced) {

            Toast.makeText(
                    this,
                    "برای استفاده از ساعت اینترنتی، "
                            + "ابتدا همگام‌سازی موفق لازم است.",
                    Toast.LENGTH_LONG
            ).show();

            syncInternetTime();
            return;
        }

        try {

            int hour = (int) readNumber(startHourInput, 0);
            int minute = (int) readNumber(startMinuteInput, 0);
            int second = (int) readNumber(startSecondInput, 0);
            int millis = (int) readNumber(startMillisInput, 0);

            if (hour < 0 || hour > 23
                    || minute < 0 || minute > 59
                    || second < 0 || second > 59
                    || millis < 0 || millis > 999) {

                showError("زمان شروع معتبر نیست.");
                return;
            }

            long intervalSeconds =
                    readNumber(intervalSecondsInput, 0);

            long intervalMillis =
                    readNumber(intervalMillisInput, 20);

            long cycleSeconds =
                    readNumber(cycleSecondsInput, 60);

            long cycleMillis =
                    readNumber(cycleMillisInput, 0);

            long clickCount =
                    readNumber(clickCountInput, 2);

            long touchMillis =
                    readNumber(touchMillisInput, 5);

            long pauseSeconds =
                    readNumber(pauseSecondsInput, 0);

            long pauseMillis =
                    readNumber(pauseMillisInput, 0);

            if (intervalSeconds < 0
                    || intervalMillis < 0
                    || cycleSeconds < 0
                    || cycleMillis < 0
                    || clickCount <= 0
                    || clickCount > Integer.MAX_VALUE
                    || touchMillis <= 0
                    || pauseSeconds < 0
                    || pauseMillis < 0) {

                showError("یکی از مقادیر تنظیمات معتبر نیست.");
                return;
            }

            long intervalDuration =
                    combineDuration(
                            intervalSeconds,
                            intervalMillis
                    );

            long cycleDuration =
                    combineDuration(
                            cycleSeconds,
                            cycleMillis
                    );

            long pauseDuration =
                    combineDuration(
                            pauseSeconds,
                            pauseMillis
                    );

            if (cycleDuration <= 0) {
                showError("مدت چرخه باید بیشتر از صفر باشد.");
                return;
            }

            long sourceNow = useInternetClock
                    ? getTrustedNow()
                    : System.currentTimeMillis();

            if (sourceNow <= 0) {
                showError("زمان مبنا معتبر نیست.");
                return;
            }

            LocalDateTime currentIranTime =
                    Instant.ofEpochMilli(sourceNow)
                            .atZone(IRAN_ZONE)
                            .toLocalDateTime();

            LocalDateTime requestedIranTime =
                    currentIranTime
                            .withHour(hour)
                            .withMinute(minute)
                            .withSecond(second)
                            .withNano(millis * 1000000);

            long scheduledStartEpoch =
                    requestedIranTime
                            .atZone(IRAN_ZONE)
                            .toInstant()
                            .toEpochMilli();

            long startDelay =
                    scheduledStartEpoch - sourceNow;

            if (startDelay <= 0) {

                showError(
                        "زمان شروع باید از زمان فعلی "
                                + "ساعت انتخاب‌شده جلوتر باشد."
                );

                return;
            }

            long nowElapsed =
                    SystemClock.elapsedRealtime();

            if (startDelay > Long.MAX_VALUE - nowElapsed) {
                showError("فاصله زمانی شروع بیش از حد بزرگ است.");
                return;
            }

            scheduledStartElapsed =
                    nowElapsed + startDelay;

            countdownActive = true;

            Intent serviceIntent =
                    new Intent(this, FloatingService.class);

            serviceIntent.setAction(FloatingService.ACTION_START);

            serviceIntent.putExtra(
                    "start_time_epoch_ms",
                    System.currentTimeMillis() + startDelay
            );

            serviceIntent.putExtra(
                    "start_delay_ms",
                    startDelay
            );

            serviceIntent.putExtra(
                    "cycle_duration_ms",
                    cycleDuration
            );

            serviceIntent.putExtra(
                    "click_count",
                    clickCount
            );

            serviceIntent.putExtra(
                    "touch_duration_ms",
                    touchMillis
            );

            serviceIntent.putExtra(
                    "pause_duration_ms",
                    pauseDuration
            );

            serviceIntent.putExtra(
                    "click_interval_ms",
                    intervalDuration
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            String sourceName = useInternetClock
                    ? "ساعت اینترنتی ایران"
                    : "ساعت گوشی";

            countdownText.setText(
                    "زمان‌بندی ثبت شد بر اساس " + sourceName
            );

            Toast.makeText(
                    this,
                    "زمان شروع ثبت شد.",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (NumberFormatException e) {

            showError("لطفاً مقادیر عددی را بررسی کن.");

        } catch (ArithmeticException e) {

            showError("یکی از زمان‌ها بیش از محدوده مجاز است.");

        } catch (Exception e) {

            showError("خطا در ثبت زمان شروع: " + e.getMessage());
        }
    }

    private long readNumber(EditText input, long defaultValue) {

        String value = input.getText()
                .toString()
                .trim();

        if (value.isEmpty()) {
            return defaultValue;
        }

        return Long.parseLong(value);
    }

    private long combineDuration(long seconds, long millis) {

        if (seconds < 0 || millis < 0) {
            throw new ArithmeticException();
        }

        if (seconds > (Long.MAX_VALUE - millis) / 1000L) {
            throw new ArithmeticException();
        }

        return seconds * 1000L + millis;
    }

    private void showError(String message) {

        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
    }

    // ---------------------------------------------------------
    // شمارش معکوس
    // ---------------------------------------------------------

    private void updateCountdown() {

        if (!countdownActive) {
            return;
        }

        long remaining =
                scheduledStartElapsed
                        - SystemClock.elapsedRealtime();

        if (remaining <= 0) {

            countdownHour.setText("00");
            countdownMinute.setText("00");
            countdownSecond.setText("00");
            countdownMillis.setText("000");

            countdownText.setText(
                    "زمان شروع فرا رسیده است."
            );

            countdownActive = false;
            return;
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
    }

    // ---------------------------------------------------------
    // توقف و کنترل FloatingService
    // ---------------------------------------------------------

    private void stopClicking() {

        countdownActive = false;

        Intent intent =
                new Intent(this, FloatingService.class);

        intent.setAction(FloatingService.ACTION_STOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        countdownText.setText("اجرای کلیک‌ها متوقف شد.");

        Toast.makeText(
                this,
                "درخواست توقف ارسال شد.",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void showFloatingPanel() {

        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }

        Intent intent =
                new Intent(this, FloatingService.class);

        intent.setAction(FloatingService.SHOW_PANEL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void shutdownApplication() {

        countdownActive = false;

        Intent intent =
                new Intent(this, FloatingService.class);

        intent.setAction(FloatingService.ACTION_SHUTDOWN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        }
