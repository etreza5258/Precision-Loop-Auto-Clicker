package com.precision.loopclicker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CycleReportStore {

    private static final String PREF_NAME = "cycle_reports";
    private static final String KEY_REPORTS = "reports";
    private static final int MAX_REPORTS = 20;

    private CycleReportStore() {
    }

    // نسخه سازگار با گزارش‌های قدیمی
    public static synchronized void addReport(
            Context context,
            long cycleNumber,
            String startTime,
            String endTime,
            long actualDurationMs,
            int successfulClicks,
            int failedClicks,
            long pauseMs
    ) {
        addReport(
                context,
                cycleNumber,
                startTime,
                endTime,
                actualDurationMs,
                successfulClicks,
                failedClicks,
                pauseMs,
                "",
                0L,
                0L,
                0L,
                0L
        );
    }

    // نسخه شامل زمان برنامه‌ریزی‌شده و خطای شروع
    public static synchronized void addReport(
            Context context,
            long cycleNumber,
            String startTime,
            String endTime,
            long actualDurationMs,
            int successfulClicks,
            int failedClicks,
            long pauseMs,
            String scheduledStartTime,
            long startErrorMs
    ) {
        addReport(
                context,
                cycleNumber,
                startTime,
                endTime,
                actualDurationMs,
                successfulClicks,
                failedClicks,
                pauseMs,
                scheduledStartTime,
                startErrorMs,
                0L,
                0L,
                0L
        );
    }

    // نسخه کامل شامل آمار تأخیر Gesture
    public static synchronized void addReport(
            Context context,
            long cycleNumber,
            String startTime,
            String endTime,
            long actualDurationMs,
            int successfulClicks,
            int failedClicks,
            long pauseMs,
            String scheduledStartTime,
            long startErrorMs,
            long averageGestureLatencyNanos,
            long maxGestureLatencyNanos,
            long measuredGestures
    ) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        );

        JSONArray reports = loadArray(preferences);
        JSONObject report = new JSONObject();

        try {
            report.put("cycle", cycleNumber);
            report.put("start", startTime);
            report.put("end", endTime);
            report.put("duration", actualDurationMs);
            report.put("success", successfulClicks);
            report.put("failed", failedClicks);
            report.put("pause", pauseMs);

            report.put("scheduled_start", scheduledStartTime);
            report.put("start_error", startErrorMs);

            report.put(
                    "avg_gesture_latency_ns",
                    averageGestureLatencyNanos
            );

            report.put(
                    "max_gesture_latency_ns",
                    maxGestureLatencyNanos
            );

            report.put("measured_gestures", measuredGestures);

        } catch (JSONException e) {
            return;
        }

        JSONArray updatedReports = new JSONArray();

        // گزارش جدید در ابتدای فهرست
        updatedReports.put(report);

        // نگهداری حداکثر ۱۹ گزارش قبلی
        int oldCount = Math.min(
                reports.length(),
                MAX_REPORTS - 1
        );

        for (int i = 0; i < oldCount; i++) {
            JSONObject oldReport = reports.optJSONObject(i);

            if (oldReport != null) {
                updatedReports.put(oldReport);
            }
        }

        preferences.edit()
                .putString(KEY_REPORTS, updatedReports.toString())
                .commit();
    }

    // دریافت خلاصه گزارش‌ها برای صفحه اصلی
    public static synchronized String getReportSummary(Context context) {

        SharedPreferences preferences = context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        );

        JSONArray reports = loadArray(preferences);

        int count = reports.length();

        if (count == 0) {
            return "هنوز هیچ چرخه‌ای تکمیل نشده است.";
        }

        JSONObject latest = reports.optJSONObject(0);

        if (latest == null) {
            return "تعداد گزارش‌های ثبت‌شده: " + count;
        }

        long cycle = latest.optLong("cycle", 0L);
        int success = latest.optInt("success", 0);
        int failed = latest.optInt("failed", 0);
        long duration = latest.optLong("duration", 0L);

        StringBuilder summary = new StringBuilder();

        summary.append("تعداد گزارش‌های ذخیره‌شده: ")
                .append(count)
                .append(" از ")
                .append(MAX_REPORTS)
                .append("\n\n");

        summary.append("آخرین چرخه تکمیل‌شده: ")
                .append(cycle)
                .append("\n");

        summary.append("کلیک موفق: ")
                .append(success)
                .append("\n");

        summary.append("کلیک ناموفق: ")
                .append(failed)
                .append("\n");

        summary.append("مدت واقعی چرخه: ")
                .append(duration)
                .append(" ms");

        return summary.toString();
    }

    public static synchronized List<String> getReports(Context context) {

        SharedPreferences preferences = context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        );

        JSONArray reports = loadArray(preferences);
        List<String> result = new ArrayList<>();

        for (int i = 0; i < reports.length(); i++) {

            JSONObject report = reports.optJSONObject(i);

            if (report == null) {
                continue;
            }

            long cycle = report.optLong("cycle", 0L);
            String start = report.optString("start", "-");
            String end = report.optString("end", "-");

            long duration = report.optLong("duration", 0L);
            int success = report.optInt("success", 0);
            int failed = report.optInt("failed", 0);
            long pause = report.optLong("pause", 0L);

            String scheduledStart = report.optString(
                    "scheduled_start",
                    ""
            );

            long startError = report.optLong("start_error", 0L);

            long averageLatency = report.optLong(
                    "avg_gesture_latency_ns",
                    0L
            );

            long maxLatency = report.optLong(
                    "max_gesture_latency_ns",
                    0L
            );

            long measuredGestures = report.optLong(
                    "measured_gestures",
                    0L
            );

            StringBuilder text = new StringBuilder();

            text.append("چرخه شماره ")
                    .append(cycle)
                    .append("\n");

            if (!scheduledStart.isEmpty()) {

                text.append("زمان هدف شروع: ")
                        .append(scheduledStart)
                        .append("\n");

                text.append("زمان شروع واقعی: ")
                        .append(start)
                        .append("\n");

                text.append("خطای شروع: ")
                        .append(formatSignedMs(startError))
                        .append("\n");

            } else {

                text.append("شروع: ")
                        .append(start)
                        .append("\n");
            }

            text.append("پایان: ")
                    .append(end)
                    .append("\n");

            text.append("مدت واقعی چرخه: ")
                    .append(duration)
                    .append(" ms\n");

            text.append("کلیک موفق: ")
                    .append(success)
                    .append("\n");

            text.append("کلیک ناموفق: ")
                    .append(failed)
                    .append("\n");

            text.append("مکث تنظیم‌شده: ")
                    .append(pause)
                    .append(" ms");

            if (measuredGestures > 0) {

                text.append("\nمیانگین زمان Gesture: ")
                        .append(formatNanosAsMs(averageLatency))
                        .append(" ms");

                text.append("\nبیشترین زمان Gesture: ")
                        .append(formatNanosAsMs(maxLatency))
                        .append(" ms");

                text.append("\nتعداد Gesture اندازه‌گیری‌شده: ")
                        .append(measuredGestures);

            } else {

                text.append("\nآمار زمان Gesture: موجود نیست");
            }

            result.add(text.toString());
        }

        return result;
    }

    public static synchronized void clearReports(Context context) {

        SharedPreferences preferences = context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        );

        preferences.edit()
                .remove(KEY_REPORTS)
                .commit();
    }

    private static JSONArray loadArray(SharedPreferences preferences) {

        String json = preferences.getString(KEY_REPORTS, "[]");

        if (json == null || json.trim().isEmpty()) {
            return new JSONArray();
        }

        try {
            return new JSONArray(json);

        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String formatSignedMs(long value) {

        if (value > 0) {
            return "+" + value + " ms (دیرتر)";
        }

        if (value < 0) {
            return value + " ms (زودتر)";
        }

        return "0 ms (دقیق)";
    }

    private static String formatNanosAsMs(long nanos) {

        double milliseconds = nanos / 1_000_000.0;

        return String.format(
                Locale.US,
                "%.3f",
                milliseconds
        );
    }
}
