
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

    // مدت هر لمس؛ اندروید برای Stroke مدت مثبت می‌خواهد.
    private static final long TAP_DURATION_MS = 1L;

    // فاصله صفر به معنی سریع‌ترین فاصله متوالی قابل زمان‌بندی است.
    private static final long MIN_SEQUENTIAL_INTERVAL_MS = 1L;

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

    // تعداد لمس‌های موجود در Gesture فعال
    private int activeBatchClickCount = 0;

    private long activeBatchIntervalMs = 0;

    private volatile long successfulClicks = 0;
    private volatile long failedClicks = 0;

    // این مقادیر زمان پاسخ کل Gesture هستند، نه زمان تماس انگشت.
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

    // ---------------------------------------------------------
    // وضعیت سرویس و مختصات
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // شمارنده‌ها و گزارش‌ها
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // محاسبه ظرفیت اجرای دسته‌ای
    // ---------------------------------------------------------

    public long getEffectiveClickIntervalMs(long requestedIntervalMs) {

        if (requestedIntervalMs < 0) {
            return -1;
        }

        return Math.max(
                MIN_SEQUENTIAL_INTERVAL_MS,
                requestedIntervalMs
        );
    }

    public int getMaxBatchClickCount(long intervalMs) {

        long effectiveInterval =
                getEffectiveClickIntervalMs(intervalMs);

        if (effectiveInterval <= 0) {
            return 0;
        }

        int maxStrokes =
                GestureDescription.getMaxStrokeCount();

        long maxDuration =
                GestureDescription.getMaxGestureDuration();

        if (maxStrokes <= 0
                || maxDuration < TAP_DURATION_MS) {
            return 0;
        }

        long maxByDuration =
                ((maxDuration - TAP_DURATION_MS)
                        / effectiveInterval) + 1L;

        long result = Math.min(
                (long) maxStrokes,
                maxByDuration
        );

        return (int) Math.max(
                1L,
                Math.min(result, Integer.MAX_VALUE)
        );
    }

    // ---------------------------------------------------------
    // اجرای یک کلیک
    // ---------------------------------------------------------

    public void performClick(Runnable completion) {

        performClickBatch(
                1,
                0,
                completion
        );
    }

    // ---------------------------------------------------------
    // اجرای چند کلیک در یک Gesture
    // ---------------------------------------------------------

    public int performClickBatch(
            int requestedCount,
            long requestedIntervalMs,
            Runnable completion
    ) {

        if (Looper.myLooper() != Looper.getMainLooper()) {

            handler.post(() ->
                    performClickBatch(
                            requestedCount,
                            requestedIntervalMs,
                            completion
                    )
            );

            return 0;
        }

        if (!serviceReady) {

            lastError =
                    "سرویس دسترسی‌پذیری آماده نیست";

            Log.e(TAG, lastError);

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        if (gestureInProgress) {

            lastError =
                    "Gesture قبلی هنوز تمام نشده است";

            return 0;
        }

        if (requestedCount <= 0
                || requestedIntervalMs < 0) {

            lastError =
                    "تعداد یا فاصله کلیک معتبر نیست";

            return 0;
        }

        final long effectiveInterval =
                getEffectiveClickIntervalMs(
                        requestedIntervalMs
                );

        final int batchCount = Math.min(
                requestedCount,
                getMaxBatchClickCount(
                        requestedIntervalMs
                )
        );

        if (batchCount <= 0) {

            lastError =
                    "ظرفیت اجرای Gesture معتبر نیست";

            return 0;
        }

        final int clickX = targetX;
        final int clickY = targetY;

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        try {

            for (int i = 0; i < batchCount; i++) {

                long startOffset =
                        (long) i * effectiveInterval;

                Path path = new Path();
                path.moveTo(clickX, clickY);

                GestureDescription.StrokeDescription stroke =
                        new GestureDescription.StrokeDescription(
                                path,
                                startOffset,
                                TAP_DURATION_MS
                        );

                builder.addStroke(stroke);
            }

        } catch (Exception e) {

            lastError =
                    "خطا در ساخت Gesture: " + e.getMessage();

            Log.e(TAG, lastError, e);

            failedClicks += batchCount;

            if (completion != null) {
                completion.run();
            }

            return batchCount;
        }

        final GestureDescription gesture;

        try {

            gesture = builder.build();

        } catch (Exception e) {

            lastError =
                    "خطا در آماده‌سازی Gesture: "
                            + e.getMessage();

            Log.e(TAG, lastError, e);

            failedClicks += batchCount;

            if (completion != null) {
                completion.run();
            }

            return batchCount;
        }

        gestureInProgress = true;

        activeCompletion = completion;
        activeBatchClickCount = batchCount;
        activeBatchIntervalMs = effectiveInterval;

        final long gestureId = ++activeGestureId;

        dispatchStartNanos =
                SystemClock.elapsedRealtimeNanos();

        Log.d(
                TAG,
                "Dispatch batch"
                        + " | count=" + batchCount
                        + " | requestedInterval="
                        + requestedIntervalMs
                        + " | effectiveInterval="
                        + effectiveInterval
                        + " | x=" + clickX
                        + " | y=" + clickY
        );

        boolean accepted = false;

        try {

            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {

                            super.onCompleted(description);

                            finishGesture(
                                    gestureId,
                                    true,
                                    "دسته کلیک با موفقیت تکمیل شد"
                            );
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {

                            super.onCancelled(description);

                            finishGesture(
                                    gestureId,
                                    false,
                                    "اندروید دسته کلیک را لغو کرد"
                            );
                        }
                    },
                    handler
            );

        } catch (Exception e) {

            lastError =
                    "خطای ارسال Gesture: " + e.getMessage();

            Log.e(TAG, lastError, e);
        }

        if (!accepted) {

            finishGesture(
                    gestureId,
                    false,
                    "dispatchGesture درخواست را نپذیرفت"
            );
        }

        // تعداد Strokeهای ارسال‌شده یا تلاش‌شده در این دسته
        return batchCount;
    }

    // ---------------------------------------------------------
    // پایان Gesture
    // ---------------------------------------------------------

    private void finishGesture(
            long gestureId,
            boolean success,
            String message
    ) {

        if (Looper.myLooper() != Looper.getMainLooper()) {

            handler.post(() ->
                    finishGesture(
                            gestureId,
                            success,
                            message
                    )
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
                Math.max(
                        maxGestureLatencyNanos,
                        latency
                );

        int completedBatchCount =
                activeBatchClickCount;

        if (success) {

            successfulClicks += completedBatchCount;
            lastError = message;

            Log.d(
                    TAG,
                    message
                            + " | batchClicks="
                            + completedBatchCount
                            + " | interval="
                            + activeBatchIntervalMs
                            + " | latencyNanos="
                            + latency
            );

        } else {

            failedClicks += completedBatchCount;
            lastError = message;

            Log.e(
                    TAG,
                    message
                            + " | batchClicks="
                            + completedBatchCount
                            + " | latencyNanos="
                            + latency
            );
        }

        gestureInProgress = false;

        activeBatchClickCount = 0;
        activeBatchIntervalMs = 0;

        Runnable callback = activeCompletion;
        activeCompletion = null;

        if (callback != null) {
            callback.run();
        }
    }
}
