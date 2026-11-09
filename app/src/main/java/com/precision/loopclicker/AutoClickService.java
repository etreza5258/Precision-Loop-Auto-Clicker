
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickService extends AccessibilityService {

    private static AutoClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int targetX = 500;
    private int targetY = 500;
    private long touchDurationMs = 5;

    private boolean gestureInProgress = false;

    private long successfulClicks = 0;
    private long failedClicks = 0;

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
        // این سرویس فقط برای ارسال Gesture استفاده می‌شود.
    }

    @Override
    public void onInterrupt() {
        gestureInProgress = false;
        FloatingService.setOverlaysHidden(false);
    }

    @Override
    public void onDestroy() {
        gestureInProgress = false;
        FloatingService.setOverlaysHidden(false);

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

    public void resetClickCounters() {
        successfulClicks = 0;
        failedClicks = 0;
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

        // پیش از لمس، پنل و نشانگر موقتاً از روی صفحه کنار می‌روند.
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

        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(GestureDescription description) {
                            super.onCompleted(description);
                            finishGesture(true, completion);
                        }

                        @Override
                        public void onCancelled(GestureDescription description) {
                            super.onCancelled(description);
                            finishGesture(false, completion);
                        }
                    },
                    handler
            );

        } catch (Exception e) {
            accepted = false;
        }

        if (!accepted) {
            // callback در این مسیر ممکن است اجرا نشود؛
            // بنابراین پایان لمس را خودمان مدیریت می‌کنیم.
            handler.post(() -> finishGesture(false, completion));
        }
    }

    private void finishGesture(boolean success, Runnable completion) {

        if (!gestureInProgress) {
            return;
        }

        gestureInProgress = false;

        if (success) {
            successfulClicks++;
        } else {
            failedClicks++;
        }

        // بازگرداندن پنل و نشانگر پس از پایان Gesture
        FloatingService.setOverlaysHidden(false);

        if (completion != null) {
            completion.run();
        }
    }
}
