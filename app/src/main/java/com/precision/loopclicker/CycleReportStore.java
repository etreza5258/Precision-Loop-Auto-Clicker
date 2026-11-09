
package com.precision.loopclicker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CycleReportStore {

    private static final String PREFS = "cycle_reports";
    private static final String KEY_REPORTS = "reports";
    private static final int MAX_REPORTS = 20;

    public static synchronized boolean addReport(
            Context context,
            long cycleNumber,
            String startTime,
            String endTime,
            long actualDurationMs,
            int successfulClicks,
            int failedClicks,
            long pauseMs
    ) {

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        JSONArray reports = loadArray(prefs);

        JSONObject report = new JSONObject();

        try {

            report.put("cycle", cycleNumber);
            report.put("start", startTime);
            report.put("end", endTime);
            report.put("duration", actualDurationMs);
            report.put("success", successfulClicks);
            report.put("failed", failedClicks);
            report.put("pause", pauseMs);

            JSONArray updated = new JSONArray();

            updated.put(report);

            int startIndex = Math.max(
                    0,
                    reports.length() - (MAX_REPORTS - 1)
            );

            for (int i = startIndex; i < reports.length(); i++) {

                JSONObject oldReport = reports.optJSONObject(i);

                if (oldReport != null) {
                    updated.put(oldReport);
                }
            }

            return prefs.edit()
                    .putString(KEY_REPORTS, updated.toString())
                    .commit();

        } catch (JSONException e) {

            e.printStackTrace();
            return false;
        }
    }

    public static synchronized List<String> getReports(Context context) {

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        JSONArray reports = loadArray(prefs);

        List<String> result = new ArrayList<>();

        for (int i = reports.length() - 1; i >= 0; i--) {

            JSONObject item = reports.optJSONObject(i);

            if (item == null) {
                continue;
            }

            String line =
                    "چرخه شماره: " + item.optLong("cycle") + "\n" +
                    "شروع: " + item.optString("start") + "\n" +
                    "پایان: " + item.optString("end") + "\n" +
                    "مدت واقعی: " + item.optLong("duration") + " ms\n" +
                    "لمس موفق: " + item.optInt("success") + "\n" +
                    "لمس ناموفق: " + item.optInt("failed") + "\n" +
                    "مکث بعدی: " + item.optLong("pause") + " ms";

            result.add(line);
        }

        return result;
    }

    public static synchronized boolean clearReports(Context context) {

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        return prefs.edit()
                .remove(KEY_REPORTS)
                .commit();
    }

    private static JSONArray loadArray(SharedPreferences prefs) {

        String saved = prefs.getString(KEY_REPORTS, "[]");

        try {
            return new JSONArray(saved);

        } catch (JSONException e) {

            return new JSONArray();
        }
    }
}
