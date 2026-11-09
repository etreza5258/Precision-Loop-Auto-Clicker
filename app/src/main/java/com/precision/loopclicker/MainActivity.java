
package com.precision.loopclicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

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
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private static final String ACTION_START =
            "com.precision.loopclicker.START";

    private static final String ACTION_STOP =
            "com.precision.loopclicker.STOP";

    private static final String ACTION_SHUTDOWN =
            "com.precision.loopclicker.SHUTDOWN";

    private final Runnable clockUpdater = new Runnable() {
        @Override
        public void run() {
            phoneClock.setText(
                    "ساعت گوشی: " + clockFormat.format(new Date())
            );

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

        handler.post(clockUpdater);
    }

    private void initializeViews() {

        cycleSecondsInput = findViewById(R.id.cycleSecondsInput);
        cycleMillisInput = findViewById(R.id.cycleMillisInput);
        clickCountInput = findViewById(R.id.clickCountInput);
        touchMillisInput = findViewById(R.id.touchMillisInput);
        pauseSecondsInput = findViewById(R.id.pauseSecondsInput);
        pauseMillisInput = findViewById(R.id.pauseMillisInput);

        phoneClock = findViewById(R.id.phoneClock);
        countdownText = findViewById(R.id.countdownText);
        reportSummary = findViewById(R.id.reportSummary);

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

    private void startClicking() {

        if (!isAccessibilityEnabled()) {
            Toast.makeText(
                    this,
                    "ابتدا دسترسی Accessibility را فعال کنید",
                    Toast.LENGTH_LONG
            ).show();

            openAccessibilitySettings();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                    this,
                    "ابتدا مجوز پنل شناور را فعال کنید",
                    Toast.LENGTH_LONG
            ).show();

            requestOverlayPermission();
            return;
        }

        try {

            long cycleSeconds = readValue(cycleSecondsInput);
            long cycleMillis = readValue(cycleMillisInput);

            int clickCount = (int) readValue(clickCountInput);

            long touchMillis = readValue(touchMillisInput);

            long pauseSeconds = readValue(pauseSecondsInput);
            long pauseMillis = readValue(pauseMillisInput);

            long cycleDuration =
                    cycleSeconds * 1000L + cycleMillis;

            long pauseDuration =
                    pauseSeconds * 1000L + pauseMillis;

            if (cycleDuration <= 0) {
                showError("زمان چرخه باید بیشتر از صفر باشد");
                return;
            }

            if (clickCount <= 0 || clickCount > 100000) {
                showError("تعداد لمس معتبر نیست");
                return;
            }

            if (touchMillis < 1 || touchMillis > 60000) {
                showError("مدت لمس باید بین ۱ تا ۶۰۰۰۰ میلی‌ثانیه باشد");
                return;
            }

            if (cycleDuration < clickCount) {
                showError("زمان چرخه برای تعداد لمس انتخابی کافی نیست");
                return;
            }

            if (pauseDuration < 0) {
                showError("مقدار مکث معتبر نیست");
                return;
            }

            Intent intent = new Intent(this, FloatingService.class);

            intent.setAction(ACTION_START);

            intent.putExtra("cycle_duration", cycleDuration);
            intent.putExtra("click_count", clickCount);
            intent.putExtra("touch_duration", touchMillis);
            intent.putExtra("pause_duration", pauseDuration);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            countdownText.setText("درخواست شروع ارسال شد");

        } catch (NumberFormatException e) {
            showError("لطفاً تمام مقادیر را صحیح وارد کنید");
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

        if (!Settings.canDrawOverlays(this)) {
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

        stopService(new Intent(this, FloatingService.class));

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
