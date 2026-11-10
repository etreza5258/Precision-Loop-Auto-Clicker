
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickService extends AccessibilityService {

    private static final String TAG = "PrecisionAutoClick";

    // کوتاه‌ترین مدت درخواستی برای لمس
    private static final long TAP_DURATION_MS = 1L;

    private static volatile AutoClickService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private volatile int targetX = 500;
    private volatile int targetY = 500;

    private volatile boolean gestureInProgress = false;
    private volatile boolean serviceReady = false;

    private Runnable activeCompletion;

    private long activeGestureId = 0;
    private long dispatchStartNanos = 0;

    private volatile long successfulClicks = 0;
    private volatile long failedClicks = 0;

    private volatile long lastGestureLatencyNanos = 0;
    private volatile long totalGestureLatencyNanos = 0;
    private volatile long maxGestureLatencyNanos = 0;
    private volatile long measuredGestures = 0;

    private volatile String lastError =
            "هنوز کلیکی ارسال نشده";

    public static AutoClickService getInstance() {
        AutoClickService service = instance;

        if (service == null || !service.serviceReady) {
            return null;
        }

        return service;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        instance = this;
        serviceReady = true;

        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // رویدادهای صفحه موردنیاز نیستند.
    }

    @Override
    public void onInterrupt() {
        handler.post(() -> {
            if (gestureInProgress) {
                finishGesture(
                        activeGestureId,
                        false,
                        "Gesture توسط اندروید متوقف شد"
                );
            }

            // سرویس ممکن است همچنان متصل باشد.
            // بنابراین serviceReady را false نمی‌کنیم.
        });
    }

    @Override
    public void onDestroy() {
        serviceReady = false;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (gestureInProgress) {
                finishGesture(
                        activeGestureId,
                        false,
                        "سرویس بسته شد"
                );
            }
        } else {
            handler.post(() -> {
                if (gestureInProgress) {
                    finishGesture(
                            activeGestureId,
                            false,
                            "سرویس بسته شد"
                    );
                }
            });
        }

        if (instance == this) {
            instance = null;
        }

        Log.i(TAG, "Accessibility service destroyed");

        super.onDestroy();
    }

    public boolean isServiceReady() {
        return serviceReady;
    }

    public void setTarget(int x, int y) {
        targetX = Math.max(0, x);
        targetY = Math.max(0, y);
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetY() {
        return targetY;
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

    public String getLastError() {
        return lastError;
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

        lastError = "آماده";
    }

    public void performClick(Runnable completion) {

        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> performClick(completion));
            return;
        }

        if (!serviceReady) {
            lastError = "سرویس دسترسی‌پذیری آماده نیست";
            Log.e(TAG, lastError);

            if (completion != null) {
                completion.run();
            }

            return;
        }

        if (gestureInProgress) {
            lastError = "لمس قبلی هنوز تمام نشده است";
            Log.w(TAG, lastError);

            // کلیک جدید ارسال نمی‌شود و callback هم اجرا نمی‌شود.
            // FloatingService باید منتظر پایان Gesture قبلی بماند.
            return;
        }

        final int clickX = targetX;
        final int clickY = targetY;

        gestureInProgress = true;
        activeCompletion = completion;

        final long gestureId = ++activeGestureId;

        Path path = new Path();
        path.moveTo(clickX, clickY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0,
                        TAP_DURATION_MS
                );

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        dispatchStartNanos =
                SystemClock.elapsedRealtimeNanos();

        Log.d(
                TAG,
                "Dispatch click: x=" + clickX
                        + " y=" + clickY
                        + " duration=" + TAP_DURATION_MS
        );

        boolean accepted = false;

        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description) {

                            super.onCompleted(description);

                            finishGesture(
                                    gestureId,
                                    true,
                                    "کلیک با موفقیت ارسال شد"
                            );
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description) {

                            super.onCancelled(description);

                            finishGesture(
                                    gestureId,
                                    false,
                                    "اندروید Gesture را لغو کرد"
                            );
                        }
                    },
                    handler
            );

        } catch (Exception e) {
            lastError = "خطای ارسال لمس: " + e.getMessage();

            Log.e(TAG, lastError, e);
        }

        if (!accepted) {
            finishGesture(
                    gestureId,
                    false,
                    "dispatchGesture درخواست را نپذیرفت"
            );
        }
    }

    private void finishGesture(
            long gestureId,
            boolean success,
            String message
    ) {

        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() ->
                    finishGesture(gestureId, success, message)
            );
            return;
        }

        if (!gestureInProgress
                || gestureId != activeGestureId) {
            return;
        }

        long latency = Math.max(
                0,
                SystemClock.elapsedRealtimeNanos()
                        - dispatchStartNanos
        );

        lastGestureLatencyNanos = latency;
        measuredGestures++;

        if (totalGestureLatencyNanos
                <= Long.MAX_VALUE - latency) {
            totalGestureLatencyNanos += latency;
        }

        maxGestureLatencyNanos =
                Math.max(maxGestureLatencyNanos, latency);

        if (success) {
            successfulClicks++;
            lastError = message;

            Log.d(
                    TAG,
                    message + " | latency=" + latency
            );

        } else {
            failedClicks++;
            lastError = message;

            Log.e(TAG, message);
        }

        gestureInProgress = false;

        Runnable callback = activeCompletion;
        activeCompletion = null;

        if (callback != null) {
            callback.run();
        }
    }
}
