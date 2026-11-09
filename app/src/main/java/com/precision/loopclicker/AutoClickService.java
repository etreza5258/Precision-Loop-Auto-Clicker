
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

    private boolean gestureInProgress = false;

    private float targetX = 500f;
    private float targetY = 500f;

    private long touchDurationMs = 5L;

    private int successfulClicks = 0;
    private int failedClicks = 0;

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
        // No window content retrieval is required.
    }

    @Override
    public void onInterrupt() {
        gestureInProgress = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        gestureInProgress = false;

        if (instance == this) {
            instance = null;
        }
    }

    public void setTarget(float x, float y) {
        targetX = x;
        targetY = y;
    }

    public void setTouchDuration(long durationMs) {
        touchDurationMs = Math.max(1L, durationMs);
    }

    public int getSuccessfulClicks() {
        return successfulClicks;
    }

    public int getFailedClicks() {
        return failedClicks;
    }

    public void resetCounters() {
        successfulClicks = 0;
        failedClicks = 0;
    }

    public boolean isGestureInProgress() {
        return gestureInProgress;
    }

    public boolean performClick(Runnable completion) {

        if (gestureInProgress) {
            return false;
        }

        gestureInProgress = true;

        Path path = new Path();
        path.moveTo(targetX, targetY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0,
                        touchDurationMs
                );

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(stroke);

        boolean dispatched;

        try {
            dispatched = dispatchGesture(
                    builder.build(),
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription gestureDescription
                        ) {
                            super.onCompleted(gestureDescription);

                            gestureInProgress = false;
                            successfulClicks++;

                            if (completion != null) {
                                completion.run();
                            }
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription gestureDescription
                        ) {
                            super.onCancelled(gestureDescription);

                            gestureInProgress = false;
                            failedClicks++;

                            if (completion != null) {
                                completion.run();
                            }
                        }

                    },
                    handler
            );

        } catch (Exception e) {
            dispatched = false;
        }

        if (!dispatched) {
            gestureInProgress = false;
            failedClicks++;

            if (completion != null) {
                completion.run();
            }
        }

        return dispatched;
    }
}
