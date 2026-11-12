package com.precision.loopclicker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
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
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final ZoneId IRAN_ZONE = ZoneId.of("Asia/Tehran");

    private static final String[] NTP_SERVERS = {
            "time.google.com",
            "time.cloudflare.com",
            "pool.ntp.org"
    };

    private static final String PREF_NAME = "precision_loop_settings";
    private static final String PREF_CLOCK_SOURCE = "clock_source";

    private static final String CLOCK_PHONE = "phone";
    private static final String CLOCK_INTERNET = "internet";

    // اطلاع‌رسانی تغییر مبنای ساعت به FloatingService
    private static final String ACTION_CLOCK_SOURCE_CHANGED =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";

    private static final long NTP_EPOCH_OFFSET = 2208988800L;
    private static final int NTP_PORT = 123;
    private static final int NTP_TIMEOUT_MS = 1800;
    private static final int NTP_PACKET_SIZE = 48;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

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
    private EditText cycleTotalCountInput;
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

    private SharedPreferences preferences;

    // زمان مرجع اینترنتی در لحظه دریافت پاسخ NTP
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

                if (!timeSynced || now - lastSyncElapsed >= 60000L) {
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

        preferences = getSharedPreferences(
                PREF_NAME,
                MODE_PRIVATE
        );

        bindViews();

        restoreClockSourceSelection();

        setupButtons();

        refreshReports();
        updateClocks();
        syncInternetTime();

        handler.post(clockRunnable);

        handleFloatingStartIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        setIntent(intent);

        handleFloatingStartIntent(intent);
    }

    // ---------------------------------------------------------
    // ذخیره و بازیابی مبنای ساعت
    // ---------------------------------------------------------

    private String getSavedClockSource() {

        return preferences.getString(
                PREF_CLOCK_SOURCE,
                CLOCK_PHONE
        );
    }

    private void saveClockSource(String source) {

        preferences.edit()
                .putString(PREF_CLOCK_SOURCE, source)
                .apply();
    }

    private void restoreClockSourceSelection() {

        if (clockSourceGroup == null) {
            return;
        }

        String source = getSavedClockSource();

        boolean useInternetClock =
                CLOCK_INTERNET.equals(source);

        setClockSourceSelection(useInternetClock);
    }

    private void setClockSourceSelection(boolean useInternetClock) {

        if (clockSourceGroup == null) {
            return;
        }

        if (useInternetClock) {

            clockSourceGroup.check(R.id.radioInternetClock);

        } else {

            int phoneRadioId = findPhoneClockRadioId();

            if (phoneRadioId != -1) {
                clockSourceGroup.check(phoneRadioId);
            }
        }

        saveClockSource(
                useInternetClock ? CLOCK_INTERNET : CLOCK_PHONE
        );
    }

    private int findPhoneClockRadioId() {

        if (clockSourceGroup == null) {
            return -1;
        }

        for (int i = 0; i < clockSourceGroup.getChildCount(); i++) {

            android.view.View child =
                    clockSourceGroup.getChildAt(i);

            if (child instanceof RadioButton) {

                RadioButton radioButton = (RadioButton) child;

                if (radioButton.getId() != R.id.radioInternetClock) {
                    return radioButton.getId();
                }
            }
        }

        return -1;
    }

    private boolean isInternetClockSelected() {

        return clockSourceGroup.getCheckedRadioButtonId()
                == R.id.radioInternetClock;
    }

    // ---------------------------------------------------------
    // اطلاع‌رسانی تغییر مبنای ساعت به پنل شناور
    // ---------------------------------------------------------

    private void notifyFloatingClockSourceChanged(String source) {

        Intent intent = new Intent(ACTION_CLOCK_SOURCE_CHANGED);

        // فقط گیرنده‌های داخل همین برنامه
        intent.setPackage(getPackageName());

        intent.putExtra("clock_source", source);

        sendBroadcast(intent);
    }

    // ---------------------------------------------------------
    // دریافت فرمان شروع از پنل شناور
    // ---------------------------------------------------------

    private void handleFloatingStartIntent(Intent intent) {

        if (intent == null) {
            return;
        }

        if (!FloatingService.ACTION_START_FROM_FLOATING.equals(
                intent.getAction()
        )) {
            return;
        }

        boolean floatingTimeConfigured =
                intent.getBooleanExtra(
                        "floating_start_time_configured",
                        false
                );

        int floatingHour =
                intent.getIntExtra("floating_start_hour", 0);

        int floatingMinute =
                intent.getIntExtra("floating_start_minute", 0);

        int floatingSecond =
                intent.getIntExtra("floating_start_second", 0);

        int floatingMillis =
                intent.getIntExtra("floating_start_millis", 0);

        String floatingClockSource =
                intent.getStringExtra("clock_source");

        // جلوگیری از اجرای مجدد فرمان
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

        // هماهنگ کردن مبنای ساعت با پنل شناور
        if (CLOCK_INTERNET.equals(floatingClockSource)) {

            setClockSourceSelection(true);

        } else if (CLOCK_PHONE.equals(floatingClockSource)) {

            setClockSourceSelection(false);
        }

        if (floatingTimeConfigured) {

            startHourInput.setText(String.valueOf(floatingHour));
            startMinuteInput.setText(String.valueOf(floatingMinute));
            startSecondInput.setText(String.valueOf(floatingSecond));
            startMillisInput.setText(String.valueOf(floatingMillis));
        }

        startClicking();
    }

    // ---------------------------------------------------------
    // اتصال Viewها
    // ---------------------------------------------------------

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

        cycleTotalCountInput =
                findViewById(R.id.cycleTotalCountInput);

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

    // ---------------------------------------------------------
    // دکمه‌ها
    // ---------------------------------------------------------

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

        clockSourceGroup.setOnCheckedChangeListener(
                (group, checkedId) -> {

                    if (checkedId == R.id.radioInternetClock) {

                        saveClockSource(CLOCK_INTERNET);

                        notifyFloatingClockSourceChanged(
                                CLOCK_INTERNET
                        );

                    } else if (checkedId != -1) {

                        saveClockSource(CLOCK_PHONE);

                        notifyFloatingClockSourceChanged(
                                CLOCK_PHONE
                        );
                    }
                }
        );
    }

    // ---------------------------------------------------------
    // ساعت گوشی و ساعت اینترنتی
    // ---------------------------------------------------------

    private void updateClocks() {

        if (phoneClock == null) {
            return;
        }

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

            long difference = phoneEpoch - internetEpoch;

            clockDifference.setText(
                    "اختلاف ساعت گوشی با اینترنت: "
                            + String.format(Locale.US, "%+d ms", difference)
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
                syncStatus.setText("در حال دریافت زمان اینترنتی...");
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

        format.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));

        return format.format(new Date(epochMillis));
    }

    private long getTrustedNow() {

        if (!timeSynced) {
            return 0;
        }

        long elapsedNow = SystemClock.elapsedRealtime();

        return trustedEpochAtSync + (elapsedNow - elapsedAtSync);
    }

    // ---------------------------------------------------------
    // همگام‌سازی NTP با محاسبه offset استاندارد
    // ---------------------------------------------------------

    private void syncInternetTime() {

        if (syncInProgress || isFinishing()) {
            return;
        }

        syncInProgress = true;
        handler.post(this::updateClocks);

        networkExecutor.execute(() -> {

            NtpResult bestResult = null;

            for (String server : NTP_SERVERS) {

                try {

                    NtpResult result = queryNtpServer(server);

                    if (result != null
                            && (bestResult == null
                            || result.roundTripMs < bestResult.roundTripMs)) {

                        bestResult = result;
                    }

                } catch (Exception ignored) {
                    // تلاش با سرور بعدی
                }
            }

            final NtpResult finalResult = bestResult;

            runOnUiThread(() -> {

                syncInProgress = false;

                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (finalResult != null) {

                    trustedEpochAtSync = finalResult.estimatedEpochMs;
                    elapsedAtSync = finalResult.receivedElapsedMs;

                    syncUncertaintyMs = Math.max(
                            1,
                            (finalResult.roundTripMs + 1) / 2
                    );

                    lastSyncElapsed = finalResult.receivedElapsedMs;
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

            byte[] request = new byte[NTP_PACKET_SIZE];

            // LI=0, VN=4, Mode=3 (client)
            request[0] = 0x23;

            long sendWallEpochMs = System.currentTimeMillis();
            long sendElapsed = SystemClock.elapsedRealtime();

            writeNtpTimestamp(request, 40, sendWallEpochMs);

            byte[] clientTransmitTimestamp =
                    Arrays.copyOfRange(request, 40, 48);

            DatagramPacket requestPacket = new DatagramPacket(
                    request,
                    request.length,
                    address,
                    NTP_PORT
            );

            socket.send(requestPacket);

            byte[] response = new byte[NTP_PACKET_SIZE];

            DatagramPacket responsePacket = new DatagramPacket(
                    response,
                    response.length
            );

            socket.receive(responsePacket);

            long receiveElapsed = SystemClock.elapsedRealtime();
            long receiveWallEpochMs = System.currentTimeMillis();

            if (responsePacket.getLength() < NTP_PACKET_SIZE) {
                return null;
            }

            int leapIndicator = (response[0] >> 6) & 0x03;
            int version = (response[0] >> 3) & 0x07;
            int mode = response[0] & 0x07;
            int stratum = response[1] & 0xFF;

            if (leapIndicator == 3
                    || (version != 3 && version != 4)
                    || mode != 4
                    || stratum < 1
                    || stratum > 15) {

                return null;
            }

            byte[] originateTimestamp =
                    Arrays.copyOfRange(response, 24, 32);

            if (!Arrays.equals(
                    clientTransmitTimestamp,
                    originateTimestamp
            )) {
                return null;
            }

            long serverReceiveEpochMs =
                    readNtpTimestamp(response, 32);

            long serverTransmitEpochMs =
                    readNtpTimestamp(response, 40);

            if (serverReceiveEpochMs <= 0
                    || serverTransmitEpochMs <= 0
                    || serverTransmitEpochMs < serverReceiveEpochMs) {

                return null;
            }

            long t1 = sendWallEpochMs;
            long t2 = serverReceiveEpochMs;
            long t3 = serverTransmitEpochMs;
            long t4 = receiveWallEpochMs;

            double offset =
                    ((double) (t2 - t1) + (double) (t3 - t4)) / 2.0;

            long estimatedEpochAtReceive =
                    t4 + Math.round(offset);

            long elapsedRoundTrip = Math.max(
                    0,
                    receiveElapsed - sendElapsed
            );

            long serverProcessingMs = Math.max(
                    0,
                    t3 - t2
            );

            long networkRoundTrip = Math.max(
                    0,
                    elapsedRoundTrip - serverProcessingMs
            );

            if (estimatedEpochAtReceive < 1577836800000L
                    || estimatedEpochAtReceive > 4102444800000L) {
                return null;
            }

            return new NtpResult(
                    server,
                    estimatedEpochAtReceive,
                    receiveElapsed,
                    networkRoundTrip
            );

        } finally {
            socket.close();
        }
    }

    private void writeNtpTimestamp(
            byte[] data,
            int offset,
            long unixEpochMillis
    ) {

        long seconds = unixEpochMillis / 1000L + NTP_EPOCH_OFFSET;
        long millis = unixEpochMillis % 1000L;

        long fraction = (millis << 32) / 1000L;

        writeUnsignedInt(data, offset, seconds);
        writeUnsignedInt(data, offset + 4, fraction);
    }

    private void writeUnsignedInt(
            byte[] data,
            int offset,
            long value
    ) {

        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private long readNtpTimestamp(byte[] data, int offset) {

        long seconds = readUnsignedInt(data, offset);
        long fraction = readUnsignedInt(data, offset + 4);

        long unixSeconds = seconds - NTP_EPOCH_OFFSET;
        long fractionMillis = (fraction * 1000L) >>> 32;

        return unixSeconds * 1000L + fractionMillis;
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

        boolean useInternetClock = isInternetClockSelected();

        String selectedClockSource = useInternetClock
                ? CLOCK_INTERNET
                : CLOCK_PHONE;

        saveClockSource(selectedClockSource);

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

            long totalCycleCount =
                    readNumber(cycleTotalCountInput, 1);

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
                    || totalCycleCount <= 0
                    || totalCycleCount > Integer.MAX_VALUE
                    || touchMillis <= 0
                    || pauseSeconds < 0
                    || pauseMillis < 0) {

                showError("یکی از مقادیر تنظیمات معتبر نیست.");
                return;
            }

            long intervalDuration =
                    combineDuration(intervalSeconds, intervalMillis);

            long cycleDuration =
                    combineDuration(cycleSeconds, cycleMillis);

            long pauseDuration =
                    combineDuration(pauseSeconds, pauseMillis);

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

            long startDelay = scheduledStartEpoch - sourceNow;

            if (startDelay <= 0) {

                showError(
                        "زمان شروع باید از زمان فعلی "
                                + "ساعت انتخاب‌شده جلوتر باشد."
                );

                return;
            }

            long nowElapsed = SystemClock.elapsedRealtime();

            if (startDelay > Long.MAX_VALUE - nowElapsed) {
                showError("فاصله زمانی شروع بیش از حد بزرگ است.");
                return;
            }

            scheduledStartElapsed = nowElapsed + startDelay;
            countdownActive = true;

            Intent serviceIntent =
                    new Intent(this, FloatingService.class);

            serviceIntent.setAction(FloatingService.ACTION_START);

            // زمان دیواری فقط برای نمایش و گزارش است.
            // زمان‌بندی واقعی سرویس با start_delay_ms انجام می‌شود.
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
                    "cycle_total_count",
                    totalCycleCount
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

            // انتقال مبنای ساعت به FloatingService
            serviceIntent.putExtra(
                    "clock_source",
                    selectedClockSource
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
                    "زمان شروع ثبت شد. تعداد چرخه‌ها: "
                            + totalCycleCount,
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

        String value = input.getText().toString().trim();

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
                scheduledStartElapsed - SystemClock.elapsedRealtime();

        if (remaining <= 0) {

            countdownHour.setText("00");
            countdownMinute.setText("00");
            countdownSecond.setText("00");
            countdownMillis.setText("000");

            countdownText.setText("زمان شروع فرا رسیده است.");

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

        Intent intent = new Intent(this, FloatingService.class);
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

        Intent intent = new Intent(this, FloatingService.class);
        intent.setAction(FloatingService.SHOW_PANEL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void shutdownApplication() {

        countdownActive = false;

        Intent intent = new Intent(this, FloatingService.class);
        intent.setAction(FloatingService.ACTION_SHUTDOWN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        finish();
    }

    // ---------------------------------------------------------
    // مجوزها
    // ---------------------------------------------------------

    private boolean isAccessibilityEnabled() {

        String enabledServices = Secure.getString(
                getContentResolver(),
                Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabledServices == null) {
            return false;
        }

        ComponentName serviceComponent =
                new ComponentName(this, AutoClickService.class);

        String expected = serviceComponent.flattenToString();

        return enabledServices.toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT));
    }

    private void openOverlaySettings() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );

            startActivity(intent);
        }
    }

    // ---------------------------------------------------------
    // گزارش چرخه‌ها
    // ---------------------------------------------------------

    private void refreshReports() {

        if (reportSummary == null) {
            return;
        }

        reportSummary.setText(
                CycleReportStore.getReportSummary(this)
        );
    }

    @Override
    protected void onResume() {
        super.onResume();

        refreshReports();
        updateClocks();
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(clockRunnable);
        networkExecutor.shutdownNow();

        super.onDestroy();
    }
}
