
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

    private static final ZoneId IRAN_ZONE = ZoneId.of("Asia/Tehran");

    private static final String PREF_NAME = "precision_loop_settings";
    private static final String PREF_CLOCK_SOURCE = "clock_source";

    private static final String PREF_INTERNET_EPOCH =
            "internet_epoch_at_sync_ms";

    private static final String PREF_INTERNET_ELAPSED =
            "internet_elapsed_at_sync_ms";

    private static final String PREF_INTERNET_SAVED_WALL =
            "internet_anchor_saved_wall_ms";

    private static final String CLOCK_PHONE = "phone";
    private static final String CLOCK_INTERNET = "internet";

    private static final String ACTION_CLOCK_SOURCE_CHANGED =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";

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

    // مرجع ساعت اینترنتی در لحظه دریافت پاسخ NTP
    private volatile long trustedEpochAtSync = 0;
    private volatile long elapsedAtSync = 0;

    private volatile boolean timeSynced = false;
    private volatile boolean syncInProgress = false;

    // اطلاعات کیفیت ساعت فقط برای گزارش و نمایش هستند.
    // این مقادیر هیچ نقشی در اجازه یا جلوگیری از کلیک ندارند.
    private volatile long syncUncertaintyMs = 0;
    private volatile long syncRoundTripMs = 0;
    private volatile long syncSpreadMs = 0;
    private volatile int syncServerCount = 0;
    private volatile int syncSampleCount = 0;
    private volatile String syncServerName = "";
    private volatile boolean syncCrossChecked = false;
    private volatile boolean syncWithinTarget50ms = false;

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

        String source = preferences.getString(
                PREF_CLOCK_SOURCE,
                CLOCK_PHONE
        );

        if (!CLOCK_INTERNET.equals(source)
                && !CLOCK_PHONE.equals(source)) {
            return CLOCK_PHONE;
        }

        return source;
    }

    private void saveClockSource(String source) {

        if (!CLOCK_INTERNET.equals(source)
                && !CLOCK_PHONE.equals(source)) {
            source = CLOCK_PHONE;
        }

        preferences.edit()
                .putString(PREF_CLOCK_SOURCE, source)
                .apply();
    }

    private void saveInternetTimeAnchor(long epochMs, long elapsedMs) {

        if (epochMs <= 0 || elapsedMs <= 0) {
            return;
        }

        long savedWallTime = System.currentTimeMillis();

        preferences.edit()
                .putLong(PREF_INTERNET_EPOCH, epochMs)
                .putLong(PREF_INTERNET_ELAPSED, elapsedMs)
                .putLong(PREF_INTERNET_SAVED_WALL, savedWallTime)
                .apply();
    }

    /**
     * بررسی و بازیابی مرجع ذخیره‌شده ساعت اینترنتی.
     *
     * در صورت معتبر بودن، علاوه بر بررسی، متغیرهای اصلی
     * trustedEpochAtSync و elapsedAtSync نیز مقداردهی می‌شوند.
     */
    private boolean loadSavedInternetTimeAnchor() {

        if (preferences == null) {
            return false;
        }

        long epoch = preferences.getLong(
                PREF_INTERNET_EPOCH,
                0
        );

        long elapsed = preferences.getLong(
                PREF_INTERNET_ELAPSED,
                0
        );

        long savedWall = preferences.getLong(
                PREF_INTERNET_SAVED_WALL,
                0
        );

        if (epoch <= 0 || elapsed <= 0 || savedWall <= 0) {
            return false;
        }

        long currentElapsed = SystemClock.elapsedRealtime();
        long currentWall = System.currentTimeMillis();

        // اگر elapsedRealtime از مقدار ذخیره‌شده کوچک‌تر باشد،
        // احتمالاً دستگاه مجدداً راه‌اندازی شده است.
        if (currentElapsed < elapsed || currentWall < savedWall) {
            return false;
        }

        long elapsedDifference = currentElapsed - elapsed;
        long wallDifference = currentWall - savedWall;

        // تشخیص احتمال راه‌اندازی مجدد دستگاه یا نامعتبر شدن
        // مرجع monotonic ذخیره‌شده.
        long differenceBetweenClocks =
                Math.abs(wallDifference - elapsedDifference);

        if (differenceBetweenClocks > 120000L) {
            return false;
        }

        // بررسی سرریز هنگام محاسبه زمان اینترنتی فعلی
        if (epoch > Long.MAX_VALUE - elapsedDifference) {
            return false;
        }

        // بازیابی واقعی مرجع، نه فقط بررسی اعتبار آن
        trustedEpochAtSync = epoch;
        elapsedAtSync = elapsed;

        timeSynced = true;

        // زمان آخرین مرجع برای مدیریت همگام‌سازی دوره‌ای
        lastSyncElapsed = elapsed;

        // اطلاعات کیفیت نمونه قبلی ذخیره نشده‌اند.
        // بنابراین فقط مشخص می‌کنیم که مرجع از حافظه بازیابی شده است.
        syncServerName = "مرجع ذخیره‌شده";
        syncUncertaintyMs = 0;
        syncRoundTripMs = 0;
        syncSpreadMs = 0;
        syncServerCount = 0;
        syncSampleCount = 0;
        syncCrossChecked = false;
        syncWithinTarget50ms = false;

        return true;
    }

    private void restoreClockSourceSelection() {

        // ابتدا مرجع ساعت اینترنتی را بازیابی می‌کنیم.
        // اگر معتبر باشد، حتی در صورت شکست NTP نیز قابل استفاده است.
        loadSavedInternetTimeAnchor();

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

        intent.setPackage(getPackageName());

        intent.putExtra("clock_source", source);

        if (CLOCK_INTERNET.equals(source)) {

            long epochAnchor = trustedEpochAtSync;
            long elapsedAnchor = elapsedAtSync;

            boolean anchorAvailable =
                    timeSynced
                            && epochAnchor > 0
                            && elapsedAnchor > 0
                            && SystemClock.elapsedRealtime() >= elapsedAnchor;

            // اگر هنوز در حافظه مرجع نداریم، مرجع ذخیره‌شده
            // را فقط در صورت معتبر بودن بازیابی می‌کنیم.
            if (!anchorAvailable && loadSavedInternetTimeAnchor()) {

                epochAnchor = trustedEpochAtSync;
                elapsedAnchor = elapsedAtSync;

                anchorAvailable =
                        epochAnchor > 0 && elapsedAnchor > 0;
            }

            intent.putExtra(
                    "internet_anchor_available",
                    anchorAvailable
            );

            if (anchorAvailable) {

                intent.putExtra(
                        "internet_epoch_at_sync_ms",
                        epochAnchor
                );

                intent.putExtra(
                        "internet_elapsed_at_sync_ms",
                        elapsedAnchor
                );
            }
        }

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

            if (internetEpoch <= 0) {

                internetClock.setText(
                        "ساعت اینترنتی ایران: مرجع نامعتبر"
                );

                clockDifference.setText(
                        "اختلاف ساعت گوشی با اینترنت: -- ms"
                );

                syncStatus.setText(
                        "مرجع ساعت اینترنتی قابل استفاده نیست؛ "
                                + "در انتظار همگام‌سازی مجدد"
                );

                return;
            }

            internetClock.setText(
                    "ساعت اینترنتی ایران: "
                            + formatIranTime(internetEpoch)
            );

            long difference = phoneEpoch - internetEpoch;

            clockDifference.setText(
                    "اختلاف ساعت گوشی با اینترنت: "
                            + String.format(Locale.US, "%+d ms", difference)
            );

            String qualityText;

            if (syncServerCount == 0
                    && "مرجع ذخیره‌شده".equals(syncServerName)) {

                qualityText =
                        "مرجع قبلی از حافظه بازیابی شده است";

            } else if (syncWithinTarget50ms) {

                qualityText = "کیفیت اندازه‌گیری: در محدوده ۵۰ ms";

            } else {

                qualityText = "کیفیت اندازه‌گیری: خارج از محدوده ۵۰ ms";
            }

            syncStatus.setText(
                    "همگام‌سازی موفق از "
                            + syncServerName
                            + "\nعدم‌قطعیت تخمینی: ±"
                            + syncUncertaintyMs
                            + " ms"
                            + "\nرفت‌وبرگشت شبکه: "
                            + syncRoundTripMs
                            + " ms"
                            + "\nاختلاف نمونه‌ها: "
                            + syncSpreadMs
                            + " ms"
                            + "\nسرورهای مستقل: "
                            + syncServerCount
                            + " | نمونه‌ها: "
                            + syncSampleCount
                            + "\n"
                            + qualityText
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

        long epochAnchor = trustedEpochAtSync;
        long elapsedAnchor = elapsedAtSync;
        long elapsedNow = SystemClock.elapsedRealtime();

        long delta = elapsedNow - elapsedAnchor;

        if (delta < 0 || epochAnchor <= 0
                || epochAnchor > Long.MAX_VALUE - delta) {
            return 0;
        }

        return epochAnchor + delta;
    }

    // ---------------------------------------------------------
    // همگام‌سازی با موتور دقیق‌تر NTP
    // ---------------------------------------------------------

    private void syncInternetTime() {

        if (syncInProgress || isFinishing()) {
            return;
        }

        syncInProgress = true;
        handler.post(this::updateClocks);

        networkExecutor.execute(() -> {

            NtpTimeSync.Result result = null;

            try {
                result = NtpTimeSync.synchronize();
            } catch (Exception ignored) {
                // در صورت خطا، زمان معتبر قبلی حفظ می‌شود.
            }

            final NtpTimeSync.Result finalResult = result;

            runOnUiThread(() -> {

                syncInProgress = false;

                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (finalResult != null) {

                    trustedEpochAtSync =
                            finalResult.estimatedEpochAtReceiveMs;

                    elapsedAtSync =
                            finalResult.receivedElapsedRealtimeMs;

                    syncUncertaintyMs =
                            finalResult.uncertaintyEstimateMs;

                    syncRoundTripMs =
                            finalResult.networkRoundTripMs;

                    syncSpreadMs =
                            finalResult.maxOffsetSpreadMs;

                    syncServerCount =
                            finalResult.independentServerCount;

                    syncSampleCount =
                            finalResult.sampleCount;

                    syncServerName =
                            finalResult.server;

                    syncCrossChecked =
                            finalResult.isCrossChecked;

                    syncWithinTarget50ms =
                            finalResult.withinTarget50ms;

                    lastSyncElapsed =
                            finalResult.receivedElapsedRealtimeMs;

                    timeSynced = true;

                    // ذخیره مرجع جدید برای استفاده پنل شناور
                    // و بازیابی در صورت بازسازی سرویس.
                    saveInternetTimeAnchor(
                            trustedEpochAtSync,
                            elapsedAtSync
                    );

                    // اگر ساعت اینترنتی انتخاب شده باشد،
                    // مرجع تازه فوراً به FloatingService می‌رسد.
                    if (CLOCK_INTERNET.equals(getSavedClockSource())) {

                        notifyFloatingClockSourceChanged(
                                CLOCK_INTERNET
                        );
                    }

                } else {

                    // اگر همگام‌سازی جدید ناموفق بود،
                    // آخرین زمان معتبر قبلی را نگه می‌داریم.
                    if (!timeSynced) {

                        syncStatus.setText(
                                "دریافت زمان اینترنتی ناموفق بود؛ "
                                        + "اتصال اینترنت و دسترسی NTP را بررسی کن."
                        );

                    } else {

                        syncStatus.setText(
                                "اتصال مجدد ناموفق؛ "
                                        + "آخرین زمان معتبر استفاده می‌شود."
                        );
                    }
                }

                updateClocks();
            });
        });
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

        // فقط دریافت حداقل یک زمان معتبر اینترنتی لازم است.
        // عدم‌قطعیت بیشتر از ۵۰ میلی‌ثانیه مانع کلیک نمی‌شود.
        if (useInternetClock
                && (!timeSynced || getTrustedNow() <= 0)) {

            Toast.makeText(
                    this,
                    "هنوز زمان اینترنتی معتبری دریافت نشده است. "
                            + "در حال تلاش برای همگام‌سازی...",
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

            // -------------------------------------------------
            // ثبت مرجع ساعت و تبدیل زمان هدف به elapsedRealtime
            // -------------------------------------------------

            final long sourceNow;
            final long scheduledStartElapsed;
            final long internetEpochAnchor;
            final long internetElapsedAnchor;

            if (useInternetClock) {

                // هر دو مقدار از یک نمونه NTP گرفته می‌شوند.
                internetEpochAnchor = trustedEpochAtSync;
                internetElapsedAnchor = elapsedAtSync;

                if (internetEpochAnchor <= 0
                        || internetElapsedAnchor <= 0) {

                    showError("مرجع ساعت اینترنتی معتبر نیست.");
                    return;
                }

                long elapsedNow = SystemClock.elapsedRealtime();
                long elapsedDelta = elapsedNow - internetElapsedAnchor;

                if (elapsedDelta < 0
                        || internetEpochAnchor > Long.MAX_VALUE - elapsedDelta) {

                    showError("مرجع زمانی اینترنتی معتبر نیست.");
                    return;
                }

                sourceNow = internetEpochAnchor + elapsedDelta;

            } else {

                // برای ساعت گوشی نیز زمان دیواری و monotonic
                // در یک نقطه زمانی ثبت می‌شوند.
                internetEpochAnchor = 0;
                internetElapsedAnchor = 0;

                sourceNow = System.currentTimeMillis();
            }

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

            if (useInternetClock) {

                // تبدیل مستقیم زمان هدف اینترنتی به زمان monotonic.
                long targetDelta =
                        scheduledStartEpoch - internetEpochAnchor;

                if (targetDelta < 0
                        || internetElapsedAnchor > Long.MAX_VALUE - targetDelta) {

                    showError("زمان هدف خارج از محدوده مجاز است.");
                    return;
                }

                scheduledStartElapsed =
                        internetElapsedAnchor + targetDelta;

            } else {

                // جفت‌کردن زمان گوشی و elapsedRealtime در یک لحظه.
                long phoneEpochAtAnchor = System.currentTimeMillis();
                long elapsedAtAnchor = SystemClock.elapsedRealtime();

                long targetDelta =
                        scheduledStartEpoch - phoneEpochAtAnchor;

                if (targetDelta <= 0
                        || elapsedAtAnchor > Long.MAX_VALUE - targetDelta) {

                    showError("زمان هدف معتبر نیست یا بیش از حد بزرگ است.");
                    return;
                }

                scheduledStartElapsed =
                        elapsedAtAnchor + targetDelta;
            }

            long actualStartDelay =
                    scheduledStartElapsed - nowElapsed;

            if (actualStartDelay <= 0) {

                showError(
                        "زمان هدف هنگام ثبت فرمان گذشته است. "
                                + "لطفاً زمان شروع را کمی جلوتر تنظیم کن."
                );

                return;
            }

            countdownActive = true;
            this.scheduledStartElapsed = scheduledStartElapsed;

            Intent serviceIntent =
                    new Intent(this, FloatingService.class);

            serviceIntent.setAction(FloatingService.ACTION_START);

            // زمان هدف واقعی؛ برای نمایش و گزارش.
            serviceIntent.putExtra(
                    "start_time_epoch_ms",
                    scheduledStartEpoch
            );

            // زمان مطلق monotonic؛ مرجع اصلی اجرای شروع.
            serviceIntent.putExtra(
                    "start_elapsed_realtime_ms",
                    scheduledStartElapsed
            );

            // برای سازگاری با نسخه قبلی سرویس حفظ شده است.
            serviceIntent.putExtra(
                    "start_delay_ms",
                    actualStartDelay
            );

            // مرجع ساعت اینترنتی برای نمایش ساعت صحیح در پنل شناور.
            if (useInternetClock) {

                serviceIntent.putExtra(
                        "internet_epoch_at_sync_ms",
                        internetEpochAnchor
                );

                serviceIntent.putExtra(
                        "internet_elapsed_at_sync_ms",
                        internetElapsedAnchor
                );
            }

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
