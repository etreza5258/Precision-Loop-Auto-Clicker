
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
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
    private static final String PREF_INTERNET_EPOCH = "internet_epoch_at_sync_ms";
    private static final String PREF_INTERNET_ELAPSED = "internet_elapsed_at_sync_ms";
    private static final String PREF_INTERNET_SAVED_WALL = "internet_anchor_saved_wall_ms";

    private static final String CLOCK_PHONE = "phone";
    private static final String CLOCK_INTERNET = "internet";

    private static final String ACTION_CLOCK_SOURCE_CHANGED =
            "com.precision.loopclicker.ACTION_CLOCK_SOURCE_CHANGED";

    private static final long SYNC_INTERVAL_MS = 60000L;
    private static final long SYNC_RETRY_INTERVAL_MS = 10000L;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor();

    private EditText startHourInput, startMinuteInput;
    private EditText startSecondInput, startMillisInput;

    private EditText intervalSecondsInput, intervalMillisInput;
    private EditText clickCountInput, touchMillisInput;

    private EditText cycleDurationInput;
    private EditText cycleCountInput;
    private EditText compensationInput;

    private TextView phoneClock, internetClock;
    private TextView clockDifference, syncStatus;

    private RadioGroup clockSourceGroup;

    private Button startButton, stopButton;
    private Button accessibilityButton, overlayButton;
    private Button showFloatingButton, shutdownButton;

    private SharedPreferences preferences;

    private volatile long trustedEpochAtSync = 0;
    private volatile long elapsedAtSync = 0;
    private volatile boolean timeSynced = false;
    private volatile boolean syncInProgress = false;

    private volatile long syncUncertaintyMs = 0;
    private volatile long syncRoundTripMs = 0;
    private volatile long syncSpreadMs = 0;
    private volatile int syncServerCount = 0;
    private volatile int syncSampleCount = 0;
    private volatile String syncServerName = "";
    private volatile boolean syncCrossChecked = false;
    private volatile boolean syncWithinTarget50ms = false;

    private volatile long lastSyncElapsed = 0;
    private volatile long lastSyncAttemptElapsed = 0;

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClocks();

            long now = SystemClock.elapsedRealtime();

            boolean retryPassed = lastSyncAttemptElapsed <= 0
                    || now - lastSyncAttemptElapsed >= SYNC_RETRY_INTERVAL_MS;

            boolean syncDue = !timeSynced
                    || now - lastSyncElapsed >= SYNC_INTERVAL_MS;

            if (!syncInProgress && syncDue && retryPassed) {
                syncInternetTime();
            }

            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        bindViews();
        addCycleInputs();

        restoreClockSourceSelection();
        setupButtons();

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
    // ایجاد ورودی‌های جدید داخل LinearLayout
    // ---------------------------------------------------------

    private void addCycleInputs() {
        cycleDurationInput = addInputAfter(
                clickCountInput,
                "مدت هر چرخه (میلی‌ثانیه)",
                "1000"
        );

        cycleCountInput = addInputAfter(
                cycleDurationInput,
                "تعداد چرخه‌ها",
                "1"
        );

        compensationInput = addInputAfter(
                cycleCountInput,
                "جبران دستی تأخیر ارسال (میلی‌ثانیه)",
                "50"
        );
    }

    private EditText addInputAfter(
            EditText anchor,
            String label,
            String defaultValue
    ) {
        if (anchor == null) return null;

        LinearLayout container = null;
        View current = anchor;

        while (current.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) current.getParent();

            if (parent instanceof ScrollView) {
                if (current instanceof LinearLayout) {
                    container = (LinearLayout) current;
                }
                break;
            }

            current = parent;
        }

        if (container == null) return null;

        View directChild = anchor;

        while (directChild.getParent() != container) {
            if (!(directChild.getParent() instanceof View)) {
                return null;
            }

            directChild = (View) directChild.getParent();
        }

        int index = container.indexOfChild(directChild);
        if (index < 0) return null;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 8, 0, 8);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(14);
        row.addView(title);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(defaultValue);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFFAAAAAA);
        input.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF78909C)
        );

        row.addView(input);
        container.addView(row, index + 1);

        return input;
    }

    // ---------------------------------------------------------
    // ساعت و تنظیمات ذخیره‌شده
    // ---------------------------------------------------------

    private String getSavedClockSource() {
        String source = preferences.getString(
                PREF_CLOCK_SOURCE, CLOCK_PHONE
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
        if (epochMs <= 0 || elapsedMs <= 0) return;

        preferences.edit()
                .putLong(PREF_INTERNET_EPOCH, epochMs)
                .putLong(PREF_INTERNET_ELAPSED, elapsedMs)
                .putLong(PREF_INTERNET_SAVED_WALL, System.currentTimeMillis())
                .apply();
    }

    private boolean loadSavedInternetTimeAnchor() {
        long epoch = preferences.getLong(PREF_INTERNET_EPOCH, 0);
        long elapsed = preferences.getLong(PREF_INTERNET_ELAPSED, 0);
        long savedWall = preferences.getLong(PREF_INTERNET_SAVED_WALL, 0);

        if (epoch <= 0 || elapsed <= 0 || savedWall <= 0) return false;

        long currentElapsed = SystemClock.elapsedRealtime();
        long currentWall = System.currentTimeMillis();

        if (currentElapsed < elapsed || currentWall < savedWall) return false;

        long elapsedDifference = currentElapsed - elapsed;
        long wallDifference = currentWall - savedWall;

        if (Math.abs(wallDifference - elapsedDifference) > 120000L) {
            return false;
        }

        if (epoch > Long.MAX_VALUE - elapsedDifference) return false;

        trustedEpochAtSync = epoch;
        elapsedAtSync = elapsed;
        timeSynced = true;
        lastSyncElapsed = elapsed;

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
        loadSavedInternetTimeAnchor();

        if (clockSourceGroup == null) return;

        setClockSourceSelection(
                CLOCK_INTERNET.equals(getSavedClockSource())
        );
    }

    private void setClockSourceSelection(boolean internet) {
        if (clockSourceGroup == null) return;

        if (internet) {
            clockSourceGroup.check(R.id.radioInternetClock);
        } else {
            int id = findPhoneClockRadioId();
            if (id != -1) clockSourceGroup.check(id);
        }

        saveClockSource(internet ? CLOCK_INTERNET : CLOCK_PHONE);
    }

    private int findPhoneClockRadioId() {
        if (clockSourceGroup == null) return -1;

        for (int i = 0; i < clockSourceGroup.getChildCount(); i++) {
            View child = clockSourceGroup.getChildAt(i);

            if (child instanceof RadioButton
                    && child.getId() != R.id.radioInternetClock) {
                return child.getId();
            }
        }

        return -1;
    }

    private boolean isInternetClockSelected() {
        return clockSourceGroup.getCheckedRadioButtonId()
                == R.id.radioInternetClock;
    }

    // ---------------------------------------------------------
    // اطلاع‌رسانی به پنل شناور
    // ---------------------------------------------------------

    private void notifyFloatingClockSourceChanged(String source) {
        Intent intent = new Intent(ACTION_CLOCK_SOURCE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra("clock_source", source);

        if (CLOCK_INTERNET.equals(source)) {
            long epoch = trustedEpochAtSync;
            long elapsed = elapsedAtSync;

            boolean available = timeSynced
                    && epoch > 0
                    && elapsed > 0
                    && SystemClock.elapsedRealtime() >= elapsed;

            if (!available && loadSavedInternetTimeAnchor()) {
                epoch = trustedEpochAtSync;
                elapsed = elapsedAtSync;
                available = epoch > 0 && elapsed > 0;
            }

            intent.putExtra("internet_anchor_available", available);

            if (available) {
                intent.putExtra("internet_epoch_at_sync_ms", epoch);
                intent.putExtra("internet_elapsed_at_sync_ms", elapsed);
            }
        }

        sendBroadcast(intent);
    }

    // ---------------------------------------------------------
    // فرمان شروع از پنل شناور
    // ---------------------------------------------------------

    private void handleFloatingStartIntent(Intent intent) {
        if (intent == null
                || !FloatingService.ACTION_START_FROM_FLOATING.equals(
                intent.getAction())) {
            return;
        }

        boolean configured = intent.getBooleanExtra(
                "floating_start_time_configured", false
        );

        int hour = intent.getIntExtra("floating_start_hour", 0);
        int minute = intent.getIntExtra("floating_start_minute", 0);
        int second = intent.getIntExtra("floating_start_second", 0);
        int millis = intent.getIntExtra("floating_start_millis", 0);

        String source = intent.getStringExtra("clock_source");

        intent.setAction(null);

        if (startButton == null) {
            showError("صفحه اصلی هنوز آماده نیست.");
            return;
        }

        if (CLOCK_INTERNET.equals(source)) {
            setClockSourceSelection(true);
        } else if (CLOCK_PHONE.equals(source)) {
            setClockSourceSelection(false);
        }

        if (configured) {
            startHourInput.setText(String.valueOf(hour));
            startMinuteInput.setText(String.valueOf(minute));
            startSecondInput.setText(String.valueOf(second));
            startMillisInput.setText(String.valueOf(millis));
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

        intervalSecondsInput = findViewById(R.id.intervalSecondsInput);
        intervalMillisInput = findViewById(R.id.intervalMillisInput);
        clickCountInput = findViewById(R.id.clickCountInput);
        touchMillisInput = findViewById(R.id.touchMillisInput);

        phoneClock = findViewById(R.id.phoneClock);
        internetClock = findViewById(R.id.internetClock);
        clockDifference = findViewById(R.id.clockDifference);
        syncStatus = findViewById(R.id.syncStatus);

        clockSourceGroup = findViewById(R.id.clockSourceGroup);

        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        overlayButton = findViewById(R.id.overlayButton);
        showFloatingButton = findViewById(R.id.showFloatingButton);
        shutdownButton = findViewById(R.id.shutdownButton);
    }

    // ---------------------------------------------------------
    // دکمه‌ها
    // ---------------------------------------------------------

    private void setupButtons() {
        startButton.setOnClickListener(v -> startClicking());
        stopButton.setOnClickListener(v -> stopClicking());

        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );

        overlayButton.setOnClickListener(v -> openOverlaySettings());
        showFloatingButton.setOnClickListener(v -> showFloatingPanel());
        shutdownButton.setOnClickListener(v -> shutdownApplication());

        clockSourceGroup.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.radioInternetClock) {
                saveClockSource(CLOCK_INTERNET);
                notifyFloatingClockSourceChanged(CLOCK_INTERNET);
            } else if (id != -1) {
                saveClockSource(CLOCK_PHONE);
                notifyFloatingClockSourceChanged(CLOCK_PHONE);
            }
        });
    }

    // ---------------------------------------------------------
    // ساعت‌ها
    // ---------------------------------------------------------

    private void updateClocks() {
        if (phoneClock == null) return;

        long phoneEpoch = System.currentTimeMillis();

        phoneClock.setText("ساعت گوشی: " + formatIranTime(phoneEpoch));

        if (!timeSynced) {
            internetClock.setText("ساعت اینترنتی ایران: در انتظار همگام‌سازی");
            clockDifference.setText("اختلاف ساعت گوشی با اینترنت: -- ms");
            syncStatus.setText(syncInProgress
                    ? "در حال دریافت زمان اینترنتی..."
                    : "زمان اینترنتی هنوز دریافت نشده است");
            return;
        }

        long internetEpoch = getTrustedNow();

        if (internetEpoch <= 0) {
            internetClock.setText("ساعت اینترنتی ایران: مرجع نامعتبر");
            clockDifference.setText("اختلاف ساعت گوشی با اینترنت: -- ms");
            syncStatus.setText("در انتظار همگام‌سازی مجدد");
            return;
        }

        internetClock.setText(
                "ساعت اینترنتی ایران: " + formatIranTime(internetEpoch)
        );

        clockDifference.setText(
                "اختلاف ساعت گوشی با اینترنت: "
                        + String.format(Locale.US, "%+d ms",
                        phoneEpoch - internetEpoch)
        );

        String quality = syncServerCount == 0
                && "مرجع ذخیره‌شده".equals(syncServerName)
                ? "مرجع قبلی از حافظه بازیابی شده است"
                : syncWithinTarget50ms
                ? "کیفیت اندازه‌گیری: در محدوده ۵۰ ms"
                : "کیفیت اندازه‌گیری: خارج از محدوده ۵۰ ms";

        syncStatus.setText(
                "همگام‌سازی موفق از " + syncServerName
                        + "\nعدم‌قطعیت تخمینی: ±" + syncUncertaintyMs + " ms"
                        + "\nرفت‌وبرگشت شبکه: " + syncRoundTripMs + " ms"
                        + "\nاختلاف نمونه‌ها: " + syncSpreadMs + " ms"
                        + "\nسرورهای مستقل: " + syncServerCount
                        + " | نمونه‌ها: " + syncSampleCount
                        + "\n" + quality
        );
    }

    private String formatIranTime(long epochMillis) {
        SimpleDateFormat format =
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

        format.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));
        return format.format(new Date(epochMillis));
    }

    private long getTrustedNow() {
        if (!timeSynced) return 0;

        long delta = SystemClock.elapsedRealtime() - elapsedAtSync;

        if (delta < 0 || trustedEpochAtSync <= 0
                || trustedEpochAtSync > Long.MAX_VALUE - delta) {
            return 0;
        }

        return trustedEpochAtSync + delta;
    }

    // ---------------------------------------------------------
    // همگام‌سازی اینترنتی
    // ---------------------------------------------------------

    private void syncInternetTime() {
        if (syncInProgress || isFinishing()) return;

        lastSyncAttemptElapsed = SystemClock.elapsedRealtime();
        syncInProgress = true;
        handler.post(this::updateClocks);

        networkExecutor.execute(() -> {
            NtpTimeSync.Result result = null;

            try {
                result = NtpTimeSync.synchronize();
            } catch (Exception ignored) {
            }

            NtpTimeSync.Result finalResult = result;

            runOnUiThread(() -> {
                syncInProgress = false;

                if (isFinishing() || isDestroyed()) return;

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

                    syncServerName = finalResult.server;
                    syncCrossChecked = finalResult.isCrossChecked;
                    syncWithinTarget50ms = finalResult.withinTarget50ms;

                    lastSyncElapsed =
                            finalResult.receivedElapsedRealtimeMs;

                    timeSynced = true;

                    saveInternetTimeAnchor(
                            trustedEpochAtSync, elapsedAtSync
                    );

                    if (CLOCK_INTERNET.equals(getSavedClockSource())) {
                        notifyFloatingClockSourceChanged(CLOCK_INTERNET);
                    }
                }

                updateClocks();
            });
        });
    }

    // ---------------------------------------------------------
    // شروع و اعتبارسنجی چرخه‌ها
    // ---------------------------------------------------------

    private void startClicking() {
        if (!isAccessibilityEnabled()) {
            showError("ابتدا دسترسی لمس را فعال کن.");
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            showError("ابتدا مجوز پنل شناور را فعال کن.");
            openOverlaySettings();
            return;
        }

        boolean useInternet = isInternetClockSelected();

        String source = useInternet ? CLOCK_INTERNET : CLOCK_PHONE;
        saveClockSource(source);

        if (useInternet && (!timeSynced || getTrustedNow() <= 0)) {
            showError("زمان اینترنتی معتبر نیست؛ در حال همگام‌سازی...");
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

            long intervalSeconds = readNumber(intervalSecondsInput, 0);
            long intervalMillis = readNumber(intervalMillisInput, 20);
            long clicksPerCycle = readNumber(clickCountInput, 2);
            long touchMillis = readNumber(touchMillisInput, 5);

            long cycleDuration = readNumber(cycleDurationInput, 1000);
            long cycleCount = readNumber(cycleCountInput, 1);
            long compensation = readNumber(compensationInput, 50);

            if (intervalSeconds < 0 || intervalMillis < 0
                    || clicksPerCycle <= 0
                    || clicksPerCycle > Integer.MAX_VALUE
                    || touchMillis <= 0
                    || cycleDuration <= 0
                    || cycleCount <= 0
                    || compensation < 0) {
                showError("یکی از مقادیر تنظیمات معتبر نیست.");
                return;
            }

            long interval = combineDuration(intervalSeconds, intervalMillis);

            if (interval <= 0) {
                showError("فاصله کلیک باید بیشتر از صفر باشد.");
                return;
            }

            long lastClickOffset = safeMultiply(
                    clicksPerCycle - 1, interval
            );

            if (lastClickOffset > Long.MAX_VALUE - touchMillis) {
                showError("مدت لمس خارج از محدوده مجاز است.");
                return;
            }

            long lastTouchEndOffset = lastClickOffset + touchMillis;

            if (lastTouchEndOffset > cycleDuration) {
                showError(
                        "تعداد کلیک‌ها، فاصله و مدت لمس در چرخه جا نمی‌شوند."
                                + "\nپایان آخرین لمس باید قبل از پایان چرخه باشد."
                );
                return;
            }

            long totalDuration = safeMultiply(cycleCount, cycleDuration);

            if (totalDuration <= 0) {
                showError("مدت کل اجرا معتبر نیست.");
                return;
            }

            final long sourceNow;
            final long internetEpochAnchor;
            final long internetElapsedAnchor;

            if (useInternet) {
                internetEpochAnchor = trustedEpochAtSync;
                internetElapsedAnchor = elapsedAtSync;

                long delta = SystemClock.elapsedRealtime()
                        - internetElapsedAnchor;

                if (delta < 0
                        || internetEpochAnchor > Long.MAX_VALUE - delta) {
                    showError("مرجع زمانی اینترنتی معتبر نیست.");
                    return;
                }

                sourceNow = internetEpochAnchor + delta;
            } else {
                internetEpochAnchor = 0;
                internetElapsedAnchor = 0;
                sourceNow = System.currentTimeMillis();
            }

            LocalDateTime nowIran = Instant.ofEpochMilli(sourceNow)
                    .atZone(IRAN_ZONE)
                    .toLocalDateTime();

            LocalDateTime requested = nowIran
                    .withHour(hour)
                    .withMinute(minute)
                    .withSecond(second)
                    .withNano(millis * 1000000);

            long targetEpoch = requested.atZone(IRAN_ZONE)
                    .toInstant().toEpochMilli();

            long startDelay = targetEpoch - sourceNow;

            if (startDelay <= 0) {
                showError("زمان شروع باید از زمان فعلی جلوتر باشد.");
                return;
            }

            final long targetElapsed;

            if (useInternet) {
                long delta = targetEpoch - internetEpochAnchor;

                if (delta < 0
                        || internetElapsedAnchor > Long.MAX_VALUE - delta) {
                    showError("زمان هدف خارج از محدوده مجاز است.");
                    return;
                }

                targetElapsed = internetElapsedAnchor + delta;
            } else {
                long wallAnchor = System.currentTimeMillis();
                long elapsedAnchor = SystemClock.elapsedRealtime();
                long delta = targetEpoch - wallAnchor;

                if (delta <= 0 || elapsedAnchor > Long.MAX_VALUE - delta) {
                    showError("زمان هدف معتبر نیست.");
                    return;
                }

                targetElapsed = elapsedAnchor + delta;
            }

            long actualDelay = targetElapsed - SystemClock.elapsedRealtime();

            if (actualDelay <= 0) {
                showError("زمان هدف هنگام ثبت فرمان گذشته است.");
                return;
            }

            Intent service = new Intent(this, FloatingService.class);
            service.setAction(FloatingService.ACTION_START);

            service.putExtra("start_time_epoch_ms", targetEpoch);
            service.putExtra("start_elapsed_realtime_ms", targetElapsed);
            service.putExtra("start_delay_ms", actualDelay);

            if (useInternet) {
                service.putExtra(
                        "internet_epoch_at_sync_ms", internetEpochAnchor
                );
                service.putExtra(
                        "internet_elapsed_at_sync_ms", internetElapsedAnchor
                );
            }

            service.putExtra("click_count", clicksPerCycle);
            service.putExtra("click_interval_ms", interval);
            service.putExtra("touch_duration_ms", touchMillis);

            service.putExtra("clicks_per_cycle", clicksPerCycle);
            service.putExtra("cycle_count", cycleCount);
            service.putExtra("cycle_duration_ms", cycleDuration);
            service.putExtra("total_duration_ms", totalDuration);

            service.putExtra("compensation_ms", compensation);
            service.putExtra("dispatch_lead_ms", compensation);

            service.putExtra("clock_source", source);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }

            Toast.makeText(
                    this,
                    "زمان‌بندی " + cycleCount + " چرخه ثبت شد.",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (NumberFormatException e) {
            showError("لطفاً مقادیر عددی را بررسی کن.");
        } catch (ArithmeticException e) {
            showError("یکی از محاسبات زمانی خارج از محدوده مجاز است.");
        } catch (Exception e) {
            showError("خطا در ثبت زمان شروع: " + e.getMessage());
        }
    }

    private long readNumber(EditText input, long defaultValue) {
        if (input == null) return defaultValue;

        String value = input.getText().toString().trim();
        return value.isEmpty() ? defaultValue : Long.parseLong(value);
    }

    private long combineDuration(long seconds, long millis) {
        if (seconds < 0 || millis < 0
                || seconds > (Long.MAX_VALUE - millis) / 1000L) {
            throw new ArithmeticException();
        }

        return seconds * 1000L + millis;
    }

    private long safeMultiply(long a, long b) {
        if (a < 0 || b < 0
                || (a != 0 && b > Long.MAX_VALUE / a)) {
            throw new ArithmeticException();
        }

        return a * b;
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------
    // توقف و پنل شناور
    // ---------------------------------------------------------

    private void stopClicking() {
        Intent intent = new Intent(this, FloatingService.class);
        intent.setAction(FloatingService.ACTION_STOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, "درخواست توقف ارسال شد.", Toast.LENGTH_SHORT).show();
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
        String enabled = Secure.getString(
                getContentResolver(),
                Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabled == null) return false;

        ComponentName component = new ComponentName(
                this, AutoClickService.class
        );

        String expected = component.flattenToString();

        return enabled.toLowerCase(Locale.ROOT)
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

    @Override
    protected void onResume() {
        super.onResume();
        updateClocks();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(clockRunnable);
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
