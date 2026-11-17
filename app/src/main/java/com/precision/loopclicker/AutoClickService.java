
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.HashMap;

public class AutoClickService extends AccessibilityService {

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private static volatile AutoClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // درخواست‌هایی که هنوز زمان ارسالشان نرسیده است.
    private final ArrayList<ClickRequest> pending = new ArrayList<>();

    // تمام ژست‌های ارسال‌شده که هنوز Callback نگرفته‌اند.
    private final HashMap<Long, ClickRequest> active =
            new HashMap<>();

    private int targetX = 350;
    private int targetY = 700;

    private boolean serviceReady;
    private long nextRequestId;

    private static class ClickRequest {
        long id;
        long targetNanos;
        long deadlineNanos;
        long touchDurationMs;

        int x;
        int y;

        Runnable runnable;
        GestureCompletion completion;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        SharedPreferences prefs = getSharedPreferences(
                "auto_click_target",
                MODE_PRIVATE
        );

        targetX = prefs.getInt("target_x", 350);
        targetY = prefs.getInt("target_y", 700);

        instance = this;
        serviceReady = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        runOnMain(this::cancelPendingInternal);
    }

    @Override
    public void onDestroy() {
        runOnMain(() -> {
            cancelPendingInternal();

            for (ClickRequest request :
                    new ArrayList<>(active.values())) {
                notifyCompletion(request, false, false);
            }

            active.clear();
            serviceReady = false;

            if (instance == this) {
                instance = null;
            }
        });

        super.onDestroy();
    }

    public static AutoClickService getInstance() {
        return instance;
    }

    public boolean isServiceReady() {
        return serviceReady;
    }

    public boolean isGestureInProgress() {
        return !active.isEmpty();
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetY() {
        return targetY;
    }

    public void setTarget(int x, int y) {
        runOnMain(() -> {
            targetX = Math.max(0, x);
            targetY = Math.max(0, y);

            getSharedPreferences(
                    "auto_click_target",
                    MODE_PRIVATE
            ).edit()
                    .putInt("target_x", targetX)
                    .putInt("target_y", targetY)
                    .apply();
        });
    }

    /**
     * ثبت مستقل هر درخواست کلیک.
     * هیچ بررسی برای فعال بودن ژست قبلی انجام نمی‌شود.
     */
    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            long touchDurationMs,
            GestureCompletion completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }

        if (!serviceReady || completion == null) {
            return false;
        }

        if (touchDurationMs < 1 ||
                touchDurationMs >
                        GestureDescription.getMaxGestureDuration()) {
            return false;
        }

        if (cycleDeadlineElapsedRealtimeNanos <=
                cycleStartElapsedRealtimeNanos) {
            return false;
        }

        if (targetElapsedRealtimeNanos <
                cycleStartElapsedRealtimeNanos ||
                targetElapsedRealtimeNanos >=
                        cycleDeadlineElapsedRealtimeNanos) {
            return false;
        }

        long touchNanos = touchDurationMs * 1_000_000L;

        if (touchNanos >
                cycleDeadlineElapsedRealtimeNanos -
                        targetElapsedRealtimeNanos) {
            return false;
        }

        ClickRequest request = new ClickRequest();

        request.id = ++nextRequestId;
        request.targetNanos = targetElapsedRealtimeNanos;
        request.deadlineNanos =
                cycleDeadlineElapsedRealtimeNanos;
        request.touchDurationMs = touchDurationMs;

        request.x = targetX;
        request.y = targetY;

        request.completion = completion;
        request.runnable = () -> executeRequest(request);

        pending.add(request);
        scheduleRequest(request);

        return true;
    }

    /**
     * زمان‌بندی مستقل هر درخواست.
     */
    private void scheduleRequest(ClickRequest request) {
        if (!pending.contains(request)) {
            return;
        }

        long now = SystemClock.elapsedRealtimeNanos();
        long touchNanos =
                request.touchDurationMs * 1_000_000L;

        if (request.deadlineNanos - now < touchNanos) {
            finishPending(request, false, true);
            return;
        }

        long remaining = request.targetNanos - now;

        if (remaining <= 0) {
            handler.post(request.runnable);
            return;
        }

        long delayMs = remaining / 1_000_000L;

        if (remaining % 1_000_000L != 0) {
            delayMs++;
        }

        handler.postDelayed(request.runnable, delayMs);
    }

    /**
     * در زمان هدف، ژست مستقیماً ارسال می‌شود.
     * فعال بودن ژست‌های دیگر مانع ارسال نیست.
     */
    private void executeRequest(ClickRequest request) {
        if (!pending.remove(request)) {
            return;
        }

        long now = SystemClock.elapsedRealtimeNanos();
        long touchNanos =
                request.touchDurationMs * 1_000_000L;

        if (request.deadlineNanos - now < touchNanos) {
            notifyCompletion(request, false, true);
            return;
        }

        if (now < request.targetNanos) {
            pending.add(request);
            scheduleRequest(request);
            return;
        }

        // بدون انتظار برای پایان ژست قبلی
        // مستقیماً درخواست جدید ارسال می‌شود.
        boolean accepted = dispatchRequest(request);

        if (!accepted) {
            notifyCompletion(request, false, false);
        }
    }

    /**
     * ارسال مستقیم ژست؛ بدون شرط هم‌پوشانی.
     */
    private boolean dispatchRequest(ClickRequest request) {
        Path path = new Path();
        path.moveTo(request.x, request.y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0,
                        request.touchDurationMs
                );

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        active.put(request.id, request);

        try {
            boolean accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {
                            finishActive(request, true, false);
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {
                            finishActive(request, false, false);
                        }
                    },
                    handler
            );

            if (!accepted) {
                active.remove(request.id);
                return false;
            }

            return true;

        } catch (Exception e) {
            active.remove(request.id);
            return false;
        }
    }

    private void finishPending(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
        if (!pending.remove(request)) {
            return;
        }

        handler.removeCallbacks(request.runnable);
        notifyCompletion(request, success, expired);
    }

    private void finishActive(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
        ClickRequest current = active.get(request.id);

        if (current != request) {
            return;
        }

        active.remove(request.id);

        notifyCompletion(request, success, expired);
    }

    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
        GestureCompletion callback = request.completion;
        request.completion = null;

        if (callback != null) {
            try {
                callback.onFinished(success, expired);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * فقط درخواست‌های ارسال‌نشده لغو می‌شوند.
     */
    public void cancelPendingScheduledClick() {
        runOnMain(this::cancelPendingInternal);
    }

    private void cancelPendingInternal() {
        ArrayList<ClickRequest> copy =
                new ArrayList<>(pending);

        for (ClickRequest request : copy) {
            finishPending(request, false, true);
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
