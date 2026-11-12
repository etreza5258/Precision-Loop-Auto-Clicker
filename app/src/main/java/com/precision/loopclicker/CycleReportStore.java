
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

    // صفحه اصلی: 20 گزارش آخر
    private static final int MAX_REPORTS = 20;

    // پنل شناور: 10 گزارش آخر
    private static final int FLOATING_MAX_REPORTS = 10;

    public static final String STATUS_GREEN = "GREEN";
    public static final String STATUS_YELLOW = "YELLOW";
    public static final String STATUS_RED = "RED";

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US
            );

    private CycleReportStore() {
    }

    // ---------------------------------------------------------
    // ثبت گزارش جدید با وضعیت مشخص
    // ---------------------------------------------------------

    public static synchronized void addReport(
            Context context,
            long cycleNumber,
            long startTimeWallMs,
            String status,
            long completedClicks
    ) {

        if (context == null) {
            return;
        }

        String finalStatus = normalizeStatus(status);

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        JSONArray oldReports = loadArray(preferences);

        JSONObject report = new JSONObject();

        try {
            report.put("cycle", cycleNumber);
            report.put("start_epoch", startTimeWallMs);
            report.put("start", formatTime(startTimeWallMs));
            report.put("status", finalStatus);
            report.put("clicks", Math.max(0L, completedClicks));

        } catch (JSONException e) {
            return;
        }

        JSONArray updatedReports = new JSONArray();

        // گزارش جدید همیشه در ابتدای فهرست قرار می‌گیرد.
        updatedReports.put(report);

        // فقط 19 گزارش قبلی را نگه می‌داریم.
        int oldCount = Math.min(
                oldReports.length(),
                MAX_REPORTS - 1
        );

        for (int i = 0; i < oldCount; i++) {

            JSONObject oldReport =
                    oldReports.optJSONObject(i);

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

    // ---------------------------------------------------------
    // سازگاری با نسخه قبلی FloatingService
    // ---------------------------------------------------------

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

        String status;

        if (failedClicks > 0) {
            status = STATUS_RED;
        } else {
            status = STATUS_GREEN;
        }

        addReport(
                context,
                cycleNumber,
                startTimeWallMs,
                status,
                successfulClicks
        );
    }

    // ---------------------------------------------------------
    // گزارش خلاصه
    // ---------------------------------------------------------

    public static synchronized String getReportSummary(
            Context context
    ) {

        if (context == null) {
            return "گزارشی ثبت نشده است.";
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        JSONArray reports = loadArray(preferences);

        if (reports.length() == 0) {
            return "هنوز گزارشی ثبت نشده است.";
        }

        JSONObject latest = reports.optJSONObject(0);

        if (latest == null) {
            return "گزارشی ثبت نشده است.";
        }

        String start = getStartTime(latest);
        String status = latest.optString(
                "status",
                STATUS_RED
        );

        long clicks = latest.optLong("clicks", 0L);

        StringBuilder result = new StringBuilder();

        result.append("تعداد گزارش‌ها: ")
                .append(reports.length())
                .append(" از ")
                .append(MAX_REPORTS)
                .append("\n\n");

        result.append(buildReportText(
                start,
                status,
                clicks
        ));

        return result.toString();
    }

    // ---------------------------------------------------------
    // گزارش‌های صفحه اصلی: 20 مورد آخر
    // ---------------------------------------------------------

    public static synchronized List<String> getReports(
            Context context
    ) {

        return getReportsInternal(
                context,
                MAX_REPORTS
        );
    }

    // ---------------------------------------------------------
    // گزارش‌های پنل شناور: 10 مورد آخر
    // ---------------------------------------------------------

    public static synchronized List<String> getFloatingReports(
            Context context
    ) {

        return getReportsInternal(
                context,
                FLOATING_MAX_REPORTS
        );
    }

    private static List<String> getReportsInternal(
            Context context,
            int maximum
    ) {

        List<String> result = new ArrayList<>();

        if (context == null) {
            return result;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        JSONArray reports = loadArray(preferences);

        int count = Math.min(
                reports.length(),
                maximum
        );

        for (int i = 0; i < count; i++) {

            JSONObject report =
                    reports.optJSONObject(i);

            if (report == null) {
                continue;
            }

            String start = getStartTime(report);

            String status = normalizeStatus(
                    report.optString(
                            "status",
                            STATUS_RED
                    )
            );

            long clicks = report.optLong(
                    "clicks",
                    0L
            );

            result.add(
                    buildReportText(
                            start,
                            status,
                            clicks
                    )
            );
        }

        return result;
    }

    // ---------------------------------------------------------
    // ساخت متن ساده گزارش
    // ---------------------------------------------------------

    private static String buildReportText(
            String start,
            String status,
            long clicks
    ) {

        if (STATUS_GREEN.equals(status)) {

            return "🟢  " + start;
        }

        if (STATUS_YELLOW.equals(status)) {

            return "🟡  " + start
                    + "  |  کلیک انجام‌شده: "
                    + clicks;
        }

        return "🔴  " + start
                + "  |  کلیک انجام‌شده: "
                + clicks;
    }

    // ---------------------------------------------------------
    // زمان شروع گزارش
    // ---------------------------------------------------------

    private static String getStartTime(
            JSONObject report
    ) {

        long startEpoch = report.optLong(
                "start_epoch",
                0L
        );

        if (startEpoch > 0) {
            return formatTime(startEpoch);
        }

        return report.optString(
                "start",
                "-"
        );
    }

    // ---------------------------------------------------------
    // پاک کردن تمام گزارش‌ها
    // ---------------------------------------------------------

    public static synchronized void clearReports(
            Context context
    ) {

        if (context == null) {
            return;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        preferences.edit()
                .remove(KEY_REPORTS)
                .commit();
    }

    // ---------------------------------------------------------
    // بارگذاری گزارش‌ها
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // یکسان‌سازی وضعیت گزارش
    // ---------------------------------------------------------

    private static String normalizeStatus(
            String status
    ) {

        if (STATUS_GREEN.equalsIgnoreCase(status)) {
            return STATUS_GREEN;
        }

        if (STATUS_YELLOW.equalsIgnoreCase(status)) {
            return STATUS_YELLOW;
        }

        return STATUS_RED;
    }

    // ---------------------------------------------------------
    // تبدیل زمان
    // ---------------------------------------------------------

    private static String formatTime(long timeMs) {

        synchronized (DATE_FORMAT) {
            return DATE_FORMAT.format(
                    new Date(timeMs)
            );
        }
    }
}
