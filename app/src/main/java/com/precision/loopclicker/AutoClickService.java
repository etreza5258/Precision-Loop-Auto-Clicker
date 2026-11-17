
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
import java.util.Iterator;
import java.util.Map;

public class AutoClickService extends AccessibilityService {

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private static volatile AutoClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ArrayList<ClickRequest> pending = new ArrayList<>();
    private final HashMap<Long, ClickRequest> active = new HashMap<>();

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
        // رویداد اضافی لازم نیست.
    }

    @Override
    public void onInterrupt() {
        runOnMain(this::cancelPendingInternal);
    }

    @Override
    public void onDestroy() {
        runOnMain(() -> {
            cancelPendingInternal();
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
     * هر درخواست مستقل زمان‌بندی می‌شود.
     * ارسال درخواست جدید به پایان Gesture قبلی وابسته نیست.
     *
     * success=true:
     * ژست با موفقیت کامل شده است.
     *
     * expired=true:
     * فرصت اجرای ژست تا پایان چرخه از دست رفته است.
     *
     * false بودن خروجی متد:
     * درخواست از ابتدا پذیرفته نشده و callback اجرا نمی‌شود.
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
        request.deadlineNanos = cycleDeadlineElapsedRealtimeNanos;
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
     * زمان‌بندی مستقل هر کلیک.
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

        long delayMs =
                (remaining + 999_999L) / 1_000_000L;

        handler.postDelayed(request.runnable, delayMs);
    }

    /**
     * اجرای درخواست بدون انتظار برای Gestureهای فعال دیگر.
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

        // اگر زمان هدف گذشته ولی هنوز در محدوده چرخه هستیم،
        // درخواست بلافاصله ارسال می‌شود.
        if (now < request.targetNanos) {
            pending.add(request);
            scheduleRequest(request);
            return;
        }

        dispatchRequest(request);
    }

    /**
     * عمداً بررسی نمی‌کنیم که Gesture دیگری فعال است یا نه.
     */
    private void dispatchRequest(ClickRequest request) {
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

        boolean accepted;

        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {
                            finishActive(
                                    request,
                                    true,
                                    false
                            );
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {
                            finishActive(
                                    request,
                                    false,
                                    false
                            );
                        }
                    },
                    handler
            );

        } catch (Exception e) {
            accepted = false;
        }

        if (!accepted) {
            finishActive(request, false, false);
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
        if (active.remove(request.id) == null) {
            return;
        }

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
     * فقط درخواست‌های زمان‌بندی‌شده و هنوز ارسال‌نشده را لغو می‌کند.
     * Gestureهای ارسال‌شده عمداً قطع نمی‌شوند.
     */
    public void cancelPendingScheduledClick() {
        runOnMain(this::cancelPendingInternal);
    }

    private void cancelPendingInternal() {
        if (pending.isEmpty()) {
            return;
        }

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
