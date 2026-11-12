
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

    // حاشیه ایمنی پایه برای تأخیر احتمالی سیستم اندروید
    private static final long BASE_DEADLINE_GUARD_MS = 50L;

    // حاشیه اضافه برای نوسان زمان اجرای Gesture
    private static final long EXTRA_DEADLINE_GUARD_MS = 20L;

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

    private volatile long successfulClicks = 0;
    private volatile long failedClicks = 0;
    private volatile long dispatchedClicks = 0;

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
    // شمارنده‌ها و آمار
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
    // محاسبه فاصله کلیک
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
    // تبدیل و محاسبات امن زمان
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

    /**
     * حاشیه ایمنی بر اساس بیشترین زمان پاسخ مشاهده‌شده.
     *
     * این مقدار برای جلوگیری از ارسال کلیک در نزدیکی
     * پایان چرخه استفاده می‌شود.
     */
    private long getDeadlineSafetyMarginNanos() {

        long measuredGuard = safeAdd(
                maxGestureLatencyNanos,
                EXTRA_DEADLINE_GUARD_MS * NANOS_PER_MILLI
        );

        long baseGuard =
                BASE_DEADLINE_GUARD_MS * NANOS_PER_MILLI;

        return Math.max(baseGuard, measuredGuard);
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
    // اجرای دسته کلیک بدون مهلت
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

    // ---------------------------------------------------------
    // اجرای کلیک با کنترل مهلت چرخه
    // ---------------------------------------------------------

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
        // بررسی سرویس
        // -----------------------------------------------------

        if (!serviceReady) {

            lastError = "سرویس دسترسی‌پذیری آماده نیست";

            lastBatchRejectedClickCount =
                    Math.max(0, requestedCount);

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // جلوگیری از هم‌پوشانی Gesture
        // -----------------------------------------------------

        if (gestureInProgress) {

            lastError = "Gesture قبلی هنوز تمام نشده است";

            lastBatchRejectedClickCount =
                    Math.max(0, requestedCount);

            runCompletion(completion);

            return 0;
        }

        if (requestedCount <= 0
                || requestedIntervalMs < 0) {

            lastError = "تعداد یا فاصله کلیک معتبر نیست";

            runCompletion(completion);

            return 0;
        }

        final long effectiveInterval =
                getEffectiveClickIntervalMs(requestedIntervalMs);

        if (effectiveInterval <= 0
                || effectiveInterval >
                Long.MAX_VALUE / NANOS_PER_MILLI) {

            lastError = "فاصله مؤثر کلیک معتبر نیست";

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
        // کنترل فاصله واقعی بین کلیک‌ها
        // -----------------------------------------------------

        if (nextAllowedClickNanos > 0
                && nowNanos < nextAllowedClickNanos) {

            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = 1;

            lastError =
                    "کلیک به علت رعایت نشدن فاصله زمانی لغو شد";

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
        // محاسبه ظرفیت
        // -----------------------------------------------------

        final int maxBatchCount =
                getMaxBatchClickCount(requestedIntervalMs);

        if (maxBatchCount <= 0) {

            lastError = "ظرفیت اجرای Gesture معتبر نیست";

            runCompletion(completion);

            return 0;
        }

        final boolean hasDeadline =
                deadlineElapsedRealtimeMs != Long.MAX_VALUE;

        final long deadlineNanos =
                hasDeadline
                        ? elapsedMillisToNanos(
                                deadlineElapsedRealtimeMs
                        )
                        : Long.MAX_VALUE;

        // -----------------------------------------------------
        // قانون اصلی:
        // در چرخه محدود، فقط یک کلیک در هر Gesture
        // -----------------------------------------------------

        int batchCount;

        if (hasDeadline) {

            long safetyMargin =
                    getDeadlineSafetyMarginNanos();

            long requiredNanos =
                    safeAdd(tapNanos, safetyMargin);

            long remainingNanos =
                    deadlineNanos - nowNanos;

            if (remainingNanos <= requiredNanos) {

                lastBatchSkippedByDeadline = requestedCount;
                lastBatchRejectedClickCount = requestedCount;

                lastError =
                        "زمان امن کافی برای کلیک بعدی تا پایان چرخه وجود ندارد";

                Log.w(
                        TAG,
                        lastError
                                + " | remainingMs="
                                + (remainingNanos / NANOS_PER_MILLI)
                                + " | safetyMs="
                                + (safetyMargin / NANOS_PER_MILLI)
                );

                runCompletion(completion);

                return 0;
            }

            // در چرخه محدود، کلیک‌ها یکی‌یکی ارسال می‌شوند.
            batchCount = 1;

            if (requestedCount > 1) {
                lastBatchSkippedByDeadline = 0;
            }

        } else {

            // بدون مهلت چرخه، رفتار دسته‌ای قبلی حفظ می‌شود.
            batchCount = Math.min(
                    requestedCount,
                    maxBatchCount
            );
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
                    "خطا در آماده‌سازی Gesture: " + e.getMessage();

            lastBatchRejectedClickCount = batchCount;
            failedClicks += batchCount;

            Log.e(TAG, lastError, e);

            runCompletion(completion);

            return 0;
        }

        // -----------------------------------------------------
        // کنترل مجدد مهلت پس از ساخت Gesture
        // -----------------------------------------------------

        final long dispatchCheckNanos =
                SystemClock.elapsedRealtimeNanos();

        if (hasDeadline) {

            long safetyMargin =
                    getDeadlineSafetyMarginNanos();

            long requiredNanos =
                    safeAdd(tapNanos, safetyMargin);

            long predictedEnd =
                    safeAdd(dispatchCheckNanos, requiredNanos);

            if (predictedEnd >= deadlineNanos) {

                lastBatchSkippedByDeadline = requestedCount;
                lastBatchRejectedClickCount = requestedCount;

                lastError =
                        "کلیک به دلیل نزدیک بودن به پایان چرخه ارسال نشد";

                Log.w(TAG, lastError);

                runCompletion(completion);

                return 0;
            }
        }

        // -----------------------------------------------------
        // آماده‌سازی ارسال
        // -----------------------------------------------------

        final long gestureId = ++activeGestureId;

        final long previousNextAllowed =
                nextAllowedClickNanos;

        gestureInProgress = true;

        activeCompletion = completion;
        activeBatchClickCount = batchCount;
        activeBatchIntervalMs = effectiveInterval;

        dispatchStartNanos =
                SystemClock.elapsedRealtimeNanos();

        // آخرین بررسی درست پیش از dispatchGesture
        if (hasDeadline) {

            long safetyMargin =
                    getDeadlineSafetyMarginNanos();

            long requiredNanos =
                    safeAdd(tapNanos, safetyMargin);

            long predictedEnd =
                    safeAdd(dispatchStartNanos, requiredNanos);

            if (predictedEnd >= deadlineNanos) {

                gestureInProgress = false;
                activeCompletion = null;
                activeBatchClickCount = 0;
                activeBatchIntervalMs = 0;

                lastBatchSkippedByDeadline = requestedCount;
                lastBatchRejectedClickCount = requestedCount;

                lastError =
                        "کلیک در کنترل نهایی مهلت چرخه رد شد";

                runCompletion(completion);

                return 0;
            }
        }

        final long proposedNextAllowed =
                safeAdd(
                        dispatchStartNanos,
                        intervalNanos
                );

        Log.d(
                TAG,
                "Dispatch click"
                        + " | count=" + batchCount
                        + " | interval=" + effectiveInterval
                        + " | deadline=" + deadlineElapsedRealtimeMs
                        + " | guardMs="
                        + (getDeadlineSafetyMarginNanos()
                        / NANOS_PER_MILLI)
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
                                    "کلیک با موفقیت تکمیل شد"
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
                                    "اندروید Gesture را لغو کرد"
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

        // درخواست توسط Android پذیرفته شده است.
        nextAllowedClickNanos = proposedNextAllowed;
        dispatchedClicks += batchCount;

        lastError = "کلیک در حال اجرا است";

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
                            + " | clicks="
                            + completedBatchCount
                            + " | latencyMs="
                            + (latency / NANOS_PER_MILLI)
            );

        } else {

            failedClicks += completedBatchCount;
            lastError = message;

            Log.e(
                    TAG,
                    message
                            + " | clicks="
                            + completedBatchCount
                            + " | latencyMs="
                            + (latency / NANOS_PER_MILLI)
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
