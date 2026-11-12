
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

    private static final long TAP_DURATION_MS = 1L;
    private static final long MIN_SEQUENTIAL_INTERVAL_MS = 1L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

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

    private int activeBatchClickCount = 0;
    private long activeBatchIntervalMs = 0;

    private long nextAllowedClickNanos = 0;

    private volatile boolean lastBatchRejectedForInterval = false;
    private volatile int lastBatchSkippedByDeadline = 0;
    private volatile int lastBatchRejectedClickCount = 0;

    // شمارنده‌های قابل مشاهده برای داشبورد زنده
    private volatile long successfulClicks = 0;
    private volatile long failedClicks = 0;

    // تعداد Strokeهایی که dispatchGesture پذیرفته است.
    private volatile long dispatchedClicks = 0;

    // آمار زمان پاسخ Gesture
    private volatile long lastGestureLatencyNanos = 0;
    private volatile long totalGestureLatencyNanos = 0;
    private volatile long maxGestureLatencyNanos = 0;
    private volatile long measuredGestures = 0;

    private volatile String lastError =
            "هنوز کلیکی ارسال نشده";

    // ---------------------------------------------------------
    // اتصال سرویس
    // ---------------------------------------------------------

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
    // شمارنده‌ها و آمار زنده
    // ---------------------------------------------------------

    public long getSuccessfulClicks() {
        return successfulClicks;
    }

    public long getFailedClicks() {
        return failedClicks;
    }

    public long getDispatchedClicks() {
        return dispatchedClicks;
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

    public boolean wasLastBatchRejectedForInterval() {
        return lastBatchRejectedForInterval;
    }

    public int getLastBatchSkippedByDeadline() {
        return lastBatchSkippedByDeadline;
    }

    public int getLastBatchRejectedClickCount() {
        return lastBatchRejectedClickCount;
    }

    public void resetClickCounters() {

        // این متد باید بین Gestureها فراخوانی شود.
        successfulClicks = 0;
        failedClicks = 0;
        dispatchedClicks = 0;

        lastGestureLatencyNanos = 0;
        totalGestureLatencyNanos = 0;
        maxGestureLatencyNanos = 0;
        measuredGestures = 0;

        nextAllowedClickNanos = 0;

        lastBatchRejectedForInterval = false;
        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedClickCount = 0;

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
    // تبدیل امن زمان
    // ---------------------------------------------------------

    private long elapsedMillisToNanos(long elapsedMillis) {

        if (elapsedMillis >= Long.MAX_VALUE / NANOS_PER_MILLI) {
            return Long.MAX_VALUE;
        }

        if (elapsedMillis <= 0) {
            return 0;
        }

        return elapsedMillis * NANOS_PER_MILLI;
    }

    private long safeAdd(long first, long second) {

        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }

        if (second < 0 && first < Long.MIN_VALUE - second) {
            return Long.MIN_VALUE;
        }

        return first + second;
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

        return performClickBatch(
                requestedCount,
                requestedIntervalMs,
                Long.MAX_VALUE,
                completion
        );
    }

    /**
     * deadlineElapsedRealtimeMs زمان مطلق پایان چرخه است.
     * مبنای آن SystemClock.elapsedRealtime() است.
     *
     * مقدار Long.MAX_VALUE یعنی بدون محدودیت زمانی.
     *
     * خروجی:
     * تعداد کلیک‌های پذیرفته‌شده برای ارسال.
     * اگر dispatchGesture درخواست را نپذیرد، خروجی صفر است.
     */

    public int performClickBatch(
            int requestedCount,
            long requestedIntervalMs,
            long deadlineElapsedRealtimeMs,
            Runnable completion
    ) {

        if (Looper.myLooper() != Looper.getMainLooper()) {

            handler.post(() ->
                    performClickBatch(
                            requestedCount,
                            requestedIntervalMs,
                            deadlineElapsedRealtimeMs,
                            completion
                    )
            );

            return 0;
        }

        lastBatchRejectedForInterval = false;
        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedClickCount = 0;

        // -----------------------------------------------------
        // بررسی آمادگی سرویس
        // -----------------------------------------------------

        if (!serviceReady) {

            lastError =
                    "سرویس دسترسی‌پذیری آماده نیست";

            lastBatchRejectedClickCount =
                    Math.max(0, requestedCount);

            Log.e(TAG, lastError);

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // جلوگیری از هم‌پوشانی Gesture
        // -----------------------------------------------------

        if (gestureInProgress) {

            lastError =
                    "Gesture قبلی هنوز تمام نشده است";

            lastBatchRejectedClickCount =
                    Math.max(0, requestedCount);

            Log.w(TAG, lastError);

            runCompletion(completion);

            return 0;
        }

        if (requestedCount <= 0
                || requestedIntervalMs < 0) {

            lastError =
                    "تعداد یا فاصله کلیک معتبر نیست";

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // فاصله مؤثر
        // -----------------------------------------------------

        final long effectiveInterval =
                getEffectiveClickIntervalMs(requestedIntervalMs);

        if (effectiveInterval <= 0
                || effectiveInterval >
                Long.MAX_VALUE / NANOS_PER_MILLI) {

            lastError =
                    "فاصله مؤثر کلیک معتبر نیست";

            runCompletion(completion);

            return 0;
        }

        final long intervalNanos =
                effectiveInterval * NANOS_PER_MILLI;

        final long tapNanos =
                TAP_DURATION_MS * NANOS_PER_MILLI;

        final long nowNanos =
                SystemClock.elapsedRealtimeNanos();

        // -----------------------------------------------------
        // کنترل فاصله واقعی بین دسته‌ها
        // -----------------------------------------------------

        if (nextAllowedClickNanos > 0
                && nowNanos < nextAllowedClickNanos) {

            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = 1;

            lastError =
                    "کلیک به علت کمتر بودن فاصله واقعی از مقدار تنظیم‌شده لغو شد";

            Log.w(
                    TAG,
                    lastError
                            + " | remainingNanos="
                            + (nextAllowedClickNanos - nowNanos)
            );

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // ظرفیت Gesture
        // -----------------------------------------------------

        final int maxBatchCount =
                getMaxBatchClickCount(requestedIntervalMs);

        if (maxBatchCount <= 0) {

            lastError =
                    "ظرفیت اجرای Gesture معتبر نیست";

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // محاسبه تعداد کلیک‌های مجاز تا پایان چرخه
        // -----------------------------------------------------

        int deadlineAllowedCount = requestedCount;

        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE) {

            long deadlineNanos =
                    elapsedMillisToNanos(
                            deadlineElapsedRealtimeMs
                    );

            long remainingNanos =
                    deadlineNanos - nowNanos;

            if (remainingNanos < tapNanos) {

                deadlineAllowedCount = 0;

            } else {

                long countByDeadline =
                        ((remainingNanos - tapNanos)
                                / intervalNanos) + 1L;

                deadlineAllowedCount = (int) Math.min(
                        (long) requestedCount,
                        Math.max(0L, countByDeadline)
                );
            }
        }

        if (deadlineAllowedCount < requestedCount) {

            lastBatchSkippedByDeadline =
                    requestedCount - deadlineAllowedCount;
        }

        final int batchCount = Math.min(
                Math.min(requestedCount, maxBatchCount),
                deadlineAllowedCount
        );

        if (batchCount <= 0) {

            lastError =
                    "کلیک به دلیل نرسیدن زمان کافی تا پایان چرخه ارسال نشد";

            Log.w(
                    TAG,
                    lastError
                            + " | skipped="
                            + lastBatchSkippedByDeadline
            );

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // ساخت Gesture
        // -----------------------------------------------------

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

            lastBatchRejectedClickCount = batchCount;
            failedClicks += batchCount;

            Log.e(TAG, lastError, e);

            runCompletion(completion);

            return 0;
        }

        final GestureDescription gesture;

        try {

            gesture = builder.build();

        } catch (Exception e) {

            lastError =
                    "خطا در آماده‌سازی Gesture: "
                            + e.getMessage();

            lastBatchRejectedClickCount = batchCount;
            failedClicks += batchCount;

            Log.e(TAG, lastError, e);

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // کنترل مهلت بعد از ساخت Gesture
        // -----------------------------------------------------

        final long dispatchCheckNanos =
                SystemClock.elapsedRealtimeNanos();

        final long deadlineNanos =
                deadlineElapsedRealtimeMs == Long.MAX_VALUE
                        ? Long.MAX_VALUE
                        : elapsedMillisToNanos(
                                deadlineElapsedRealtimeMs
                        );

        long lastStrokeOffsetNanos =
                (long) (batchCount - 1) * intervalNanos;

        long requiredDurationNanos =
                safeAdd(lastStrokeOffsetNanos, tapNanos);

        long estimatedLastEndNanos =
                safeAdd(
                        dispatchCheckNanos,
                        requiredDurationNanos
                );

        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE
                && estimatedLastEndNanos > deadlineNanos) {

            lastBatchSkippedByDeadline =
                    Math.max(
                            lastBatchSkippedByDeadline,
                            requestedCount
                    );

            lastBatchRejectedClickCount = requestedCount;

            lastError =
                    "دسته کلیک به دلیل عبور از مهلت چرخه ارسال نشد";

            Log.w(TAG, lastError);

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // آماده‌سازی ارسال
        // -----------------------------------------------------

        final long gestureId = ++activeGestureId;

        gestureInProgress = true;

        activeCompletion = completion;
        activeBatchClickCount = batchCount;
        activeBatchIntervalMs = effectiveInterval;

        dispatchStartNanos =
                SystemClock.elapsedRealtimeNanos();

        // کنترل نهایی درست پیش از dispatchGesture
        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE) {

            long finalLastEndNanos =
                    safeAdd(
                            dispatchStartNanos,
                            requiredDurationNanos
                    );

            if (finalLastEndNanos > deadlineNanos) {

                gestureInProgress = false;
                activeCompletion = null;
                activeBatchClickCount = 0;
                activeBatchIntervalMs = 0;

                lastBatchSkippedByDeadline =
                        Math.max(
                                lastBatchSkippedByDeadline,
                                requestedCount
                        );

                lastBatchRejectedClickCount = requestedCount;

                lastError =
                        "دسته کلیک به دلیل عبور از مهلت چرخه ارسال نشد";

                Log.w(TAG, lastError);

                runCompletion(completion);

                return 0;
            }
        }

        // زمان مجاز کلیک بعدی پس از آخرین Stroke
        // فقط پس از پذیرش dispatchGesture نهایی می‌شود.
        final long previousNextAllowed =
                nextAllowedClickNanos;

        final long proposedNextAllowed =
                safeAdd(
                        dispatchStartNanos,
                        (long) batchCount * intervalNanos
                );

        Log.d(
                TAG,
                "Dispatch batch"
                        + " | count=" + batchCount
                        + " | requestedInterval="
                        + requestedIntervalMs
                        + " | effectiveInterval="
                        + effectiveInterval
                        + " | deadline="
                        + deadlineElapsedRealtimeMs
                        + " | x=" + clickX
                        + " | y=" + clickY
        );

        // -----------------------------------------------------
        // ارسال Gesture
        // -----------------------------------------------------

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

            // درخواست پذیرفته نشده است؛
            // زمان مجاز کلیک بعدی نباید جلو برود.
            nextAllowedClickNanos = previousNextAllowed;

            lastBatchRejectedClickCount = batchCount;

            finishGesture(
                    gestureId,
                    false,
                    lastError != null
                            && lastError.startsWith("خطای ارسال")
                            ? lastError
                            : "dispatchGesture درخواست را نپذیرفت"
            );

            return 0;
        }

        // درخواست واقعاً توسط Android پذیرفته شده است.
        nextAllowedClickNanos = proposedNextAllowed;
        dispatchedClicks += batchCount;

        lastError =
                "دسته کلیک در حال اجرا است";

        return batchCount;
    }

    // ---------------------------------------------------------
    // اجرای Callback
    // ---------------------------------------------------------

    private void runCompletion(Runnable completion) {

        if (completion == null) {
            return;
        }

        try {
            completion.run();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Completion callback error",
                    e
            );
        }
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

        runCompletion(callback);
    }
}
