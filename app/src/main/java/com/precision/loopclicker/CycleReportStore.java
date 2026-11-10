
package com.precision.loopclicker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class CycleReportStore {

    private static final String PREF_NAME = "cycle_reports";
    private static final String KEY_REPORTS = "reports";
    private static final int MAX_REPORTS = 20;

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US
            );

    private CycleReportStore() {
    }

    public static synchronized void addReport(
            Context context,
            long cycleNumber,
            long startTimeWallMs,
            long actualDurationMs,
            long successfulClicks,
            long failedClicks,
            long averageGestureLatencyNanos,
            long maxGestureLatencyNanos,
            long measuredGestures
    ) {

        long endTimeWallMs =
                startTimeWallMs + actualDurationMs;

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        JSONArray reports = loadArray(preferences);
        JSONObject report = new JSONObject();

        try {
            report.put("cycle", cycleNumber);

            report.put("start_epoch", startTimeWallMs);
            report.put("end_epoch", endTimeWallMs);

            report.put("start", formatTime(startTimeWallMs));
            report.put("end", formatTime(endTimeWallMs));

            report.put("duration", actualDurationMs);

            report.put("success", successfulClicks);
            report.put("failed", failedClicks);

            report.put("pause", 0L);

            report.put("avg_gesture_latency_ns",
                    averageGestureLatencyNanos);

            report.put("max_gesture_latency_ns",
                    maxGestureLatencyNanos);

            report.put("measured_gestures",
                    measuredGestures);

        } catch (JSONException e) {
            return;
        }

        JSONArray updatedReports = new JSONArray();

        updatedReports.put(report);

        int oldCount = Math.min(
                reports.length(),
                MAX_REPORTS - 1
        );

        for (int i = 0; i < oldCount; i++) {

            JSONObject oldReport =
                    reports.optJSONObject(i);

            if (oldReport != null) {
                updatedReports.put(oldReport);
            }
        }

        preferences.edit()
                .putString(
                        KEY_REPORTS,
                        updatedReports.toString()
                )
                .commit();
    }

    public static synchronized String getReportSummary(
            Context context
    ) {

        SharedPreferences preferences =
                context.getSharedPreferences(
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
        long success = latest.optLong("success", 0L);
        long failed = latest.optLong("failed", 0L);
        long duration = latest.optLong("duration", 0L);

        long startEpoch =
                latest.optLong("start_epoch", 0L);

        long endEpoch =
                latest.optLong("end_epoch", 0L);

        String start = latest.optString("start", "-");
        String end = latest.optString("end", "-");

        if (startEpoch > 0) {
            start = formatTime(startEpoch);
        }

        if (endEpoch > 0) {
            end = formatTime(endEpoch);
        }

        StringBuilder summary = new StringBuilder();

        summary.append("تعداد گزارش‌های ذخیره‌شده: ")
                .append(count)
                .append(" از ")
                .append(MAX_REPORTS)
                .append("\n\n");

        summary.append("آخرین چرخه تکمیل‌شده: ")
                .append(cycle)
                .append("\n");

        summary.append("زمان شروع: ")
                .append(start)
                .append("\n");

        summary.append("زمان پایان: ")
                .append(end)
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

    public static synchronized List<String> getReports(
            Context context
    ) {

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        JSONArray reports = loadArray(preferences);

        List<String> result = new ArrayList<>();

        for (int i = 0; i < reports.length(); i++) {

            JSONObject report =
                    reports.optJSONObject(i);

            if (report == null) {
                continue;
            }

            long cycle = report.optLong("cycle", 0L);

            String start = report.optString("start", "-");
            String end = report.optString("end", "-");

            long startEpoch =
                    report.optLong("start_epoch", 0L);

            long endEpoch =
                    report.optLong("end_epoch", 0L);

            if (startEpoch > 0) {
                start = formatTime(startEpoch);
            }

            if (endEpoch > 0) {
                end = formatTime(endEpoch);
            }

            long duration = report.optLong("duration", 0L);
            long success = report.optLong("success", 0L);
            long failed = report.optLong("failed", 0L);

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

            text.append("شروع: ")
                    .append(start)
                    .append("\n");

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
                    .append(failed);

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

    public static synchronized void clearReports(
            Context context
    ) {

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        preferences.edit()
                .remove(KEY_REPORTS)
                .commit();
    }

    private static JSONArray loadArray(
            SharedPreferences preferences
    ) {

        String json = preferences.getString(
                KEY_REPORTS,
                "[]"
        );

        if (json == null || json.trim().isEmpty()) {
            return new JSONArray();
        }

        try {
            return new JSONArray(json);

        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String formatTime(long timeMs) {

        synchronized (DATE_FORMAT) {
            return DATE_FORMAT.format(new Date(timeMs));
        }
    }

    private static String formatNanosAsMs(long nanos) {

        double milliseconds =
                nanos / 1_000_000.0;

        return String.format(
                Locale.US,
                "%.3f",
                milliseconds
        );
    }
}
