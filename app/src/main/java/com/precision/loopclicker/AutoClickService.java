
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickService extends AccessibilityService {

    private static AutoClickService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private volatile int targetX = 500;
    private volatile int targetY = 500;

    private volatile long touchDurationMs = 5;

    private volatile boolean gestureInProgress = false;

    private Runnable activeCompletion;

    private long activeGestureId = 0;

    private long successfulClicks = 0;
    private long failedClicks = 0;

    private long dispatchStartNanos = 0;

    private long lastGestureLatencyNanos = 0;
    private long totalGestureLatencyNanos = 0;
    private long maxGestureLatencyNanos = 0;
    private long measuredGestures = 0;

    public static AutoClickService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // نیازی به پردازش رویدادهای صفحه نیست.
    }

    @Override
    public void onInterrupt() {
        handler.post(() -> {
            if (gestureInProgress) {
                finishGesture(activeGestureId, false);
            }
        });
    }

    @Override
    public void onDestroy() {

        if (Looper.myLooper() == Looper.getMainLooper()) {
            finishGesture(activeGestureId, false);
        } else {
            handler.post(() ->
                    finishGesture(activeGestureId, false)
            );
        }

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }

    public void setTarget(int x, int y) {
        targetX = Math.max(0, x);
        targetY = Math.max(0, y);
    }

    public void setTouchDuration(long durationMs) {
        touchDurationMs = Math.max(1, durationMs);
    }

    public boolean isGestureInProgress() {
        return gestureInProgress;
    }

    public long getSuccessfulClicks() {
        return successfulClicks;
    }

    public long getFailedClicks() {
        return failedClicks;
    }

    public long getLastGestureLatencyNanos() {
        return lastGestureLatencyNanos;
    }

    public long getMaxGestureLatencyNanos() {
        return maxGestureLatencyNanos;
    }

    public long getAverageGestureLatencyNanos() {

        if (measuredGestures == 0) {
            return 0;
        }

        return totalGestureLatencyNanos / measuredGestures;
    }

    public long getMeasuredGestures() {
        return measuredGestures;
    }

    public void resetClickCounters() {

        successfulClicks = 0;
        failedClicks = 0;

        lastGestureLatencyNanos = 0;
        totalGestureLatencyNanos = 0;
        maxGestureLatencyNanos = 0;
        measuredGestures = 0;
    }

    public void performClick(Runnable completion) {

        if (Looper.myLooper() != Looper.getMainLooper()) {

            handler.post(() -> performClick(completion));

            return;
        }

        if (gestureInProgress) {

            // کلیک هم‌زمان ارسال نمی‌شود.
            // این درخواست در شمارنده خطا ثبت نمی‌شود؛
            // سرویس زمان‌بندی باید پس از پایان Gesture دوباره تلاش کند.

            if (completion != null) {
                handler.post(completion);
            }

            return;
        }

        final int clickX = targetX;
        final int clickY = targetY;
        final long duration = touchDurationMs;

        gestureInProgress = true;

        activeCompletion = completion;

        final long gestureId = ++activeGestureId;

        Path path = new Path();

        path.moveTo(clickX, clickY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0,
                        Math.max(1, duration)
                );

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        dispatchStartNanos =
                SystemClock.elapsedRealtimeNanos();

        boolean accepted;

        try {

            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {

                            super.onCompleted(description);

                            finishGesture(gestureId, true);
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {

                            super.onCancelled(description);

                            finishGesture(gestureId, false);
                        }
                    },
                    handler
            );

        } catch (Exception e) {

            accepted = false;
        }

        if (!accepted) {

            finishGesture(gestureId, false);
        }
    }

    private void finishGesture(
            long gestureId,
            boolean success
    ) {

        if (Looper.myLooper() != Looper.getMainLooper()) {

            handler.post(() ->
                    finishGesture(gestureId, success)
            );

            return;
        }

        // جلوگیری از callback تکراری یا قدیمی
        if (!gestureInProgress
                || gestureId != activeGestureId) {
            return;
        }

        long completionNanos =
                SystemClock.elapsedRealtimeNanos();

        long latencyNanos = Math.max(
                0,
                completionNanos - dispatchStartNanos
        );

        lastGestureLatencyNanos = latencyNanos;

        if (measuredGestures < Long.MAX_VALUE) {
            measuredGestures++;
        }

        if (totalGestureLatencyNanos
                <= Long.MAX_VALUE - latencyNanos) {

            totalGestureLatencyNanos += latencyNanos;
        }

        maxGestureLatencyNanos = Math.max(
                maxGestureLatencyNanos,
                latencyNanos
        );

        if (success) {

            if (successfulClicks < Long.MAX_VALUE) {
                successfulClicks++;
            }

        } else {

            if (failedClicks < Long.MAX_VALUE) {
                failedClicks++;
            }
        }

        gestureInProgress = false;

        Runnable callback = activeCompletion;

        activeCompletion = null;

        // پنل و نشانگر دیگر مخفی یا دوباره نمایان نمی‌شوند.

        if (callback != null) {
            callback.run();
        }
    }
}
