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

    private static final int MAX_REPORTS = 100;
    private static final int FLOATING_MAX_REPORTS = 10;

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private CycleReportStore() {
    }

    // ثبت گزارش هر کلیک
    public static synchronized void addClickReport(
            Context context,
            long cycleNumber,
            long clickNumber,
            long targetTimeMs,
            long actualTimeMs
    ) {

        if (context == null) return;

        long difference = actualTimeMs - targetTimeMs;

        JSONObject report = new JSONObject();

        try {
            report.put("cycle", cycleNumber);
            report.put("click", clickNumber);
            report.put("target", targetTimeMs);
            report.put("actual", actualTimeMs);
            report.put("difference", difference);

        } catch (JSONException e) {
            return;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        JSONArray oldReports = loadArray(preferences);
        JSONArray updated = new JSONArray();

        updated.put(report);

        int count = Math.min(
                oldReports.length(),
                MAX_REPORTS - 1
        );

        for (int i = 0; i < count; i++) {
            JSONObject old = oldReports.optJSONObject(i);

            if (old != null) {
                updated.put(old);
            }
        }

        preferences.edit()
                .putString(KEY_REPORTS, updated.toString())
                .apply();
    }

    // گزارش آخرین کلیک
    public static synchronized String getReportSummary(
            Context context
    ) {

        List<String> reports = getReports(context);

        if (reports.isEmpty()) {
            return "هنوز گزارشی ثبت نشده است.";
        }

        return reports.get(0);
    }

    // گزارش‌های صفحه اصلی
    public static synchronized List<String> getReports(
            Context context
    ) {
        return getReportsInternal(context, MAX_REPORTS);
    }

    // گزارش‌های پنل شناور
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

        if (context == null) return result;

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                );

        JSONArray reports = loadArray(preferences);

        int count = Math.min(reports.length(), maximum);

        for (int i = 0; i < count; i++) {

            JSONObject report = reports.optJSONObject(i);

            if (report == null) continue;

            long cycle = report.optLong("cycle", 0);
            long click = report.optLong("click", 0);

            long target = report.optLong("target", 0);
            long actual = report.optLong("actual", 0);

            long difference = actual - target;

            String sign = difference > 0 ? "+" : "";

            result.add(
                    "چرخه " + cycle +
                    " | کلیک " + click +
                    "\nهدف: " + formatTime(target) +
                    " | واقعی: " + formatTime(actual) +
                    "\nاختلاف: " + sign + difference + " ms"
            );
        }

        return result;
    }

    // پاک کردن گزارش‌ها
    public static synchronized void clearReports(
            Context context
    ) {

        if (context == null) return;

        context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        ).edit()
                .remove(KEY_REPORTS)
                .apply();
    }

    private static JSONArray loadArray(
            SharedPreferences preferences
    ) {

        String json = preferences.getString(KEY_REPORTS, "[]");

        try {
            return new JSONArray(json);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String formatTime(long timeMs) {

        synchronized (TIME_FORMAT) {
            return TIME_FORMAT.format(new Date(timeMs));
        }
    }
}