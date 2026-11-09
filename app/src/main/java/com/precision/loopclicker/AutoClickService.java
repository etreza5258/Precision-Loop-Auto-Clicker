
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

    private int targetX = 500;
    private int targetY = 500;

    private long touchDurationMs = 5;

    private boolean gestureInProgress = false;

    private Runnable activeCompletion;

    private long successfulClicks = 0;
    private long failedClicks = 0;

    // اطلاعات اندازه‌گیری تأخیر Gesture
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
        // نیازی به پردازش رویدادهای صفحه نداریم.
    }

    @Override
    public void onInterrupt() {
        handler.post(() -> finishGesture(false));
    }

    @Override
    public void onDestroy() {

        handler.post(() -> finishGesture(false));

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

        if (gestureInProgress) {

            failedClicks++;

            if (completion != null) {
                handler.post(completion);
            }

            return;
        }

        gestureInProgress = true;
        activeCompletion = completion;

        FloatingService.setOverlaysHidden(true);

        Path path = new Path();
        path.moveTo(targetX, targetY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0,
                        Math.max(1, touchDurationMs)
                );

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        boolean accepted;

        dispatchStartNanos =
                SystemClock.elapsedRealtimeNanos();

        try {

            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {

                            super.onCompleted(description);

                            finishGesture(true);
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {

                            super.onCancelled(description);

                            finishGesture(false);
                        }
                    },
                    handler
            );

        } catch (Exception e) {

            accepted = false;
        }

        if (!accepted) {
            finishGesture(false);
        }
    }

    private void finishGesture(boolean success) {

        if (!gestureInProgress) {
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

        gestureInProgress = false;

        if (success) {
            successfulClicks++;
        } else {
            failedClicks++;
        }

        FloatingService.setOverlaysHidden(false);

        Runnable callback = activeCompletion;
        activeCompletion = null;

        if (callback != null) {
            callback.run();
        }
    }
}
