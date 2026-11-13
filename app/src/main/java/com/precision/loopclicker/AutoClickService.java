
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
    private static final long NANOS_PER_MS = 1_000_000L;

    private static final long MAX_ADAPTIVE_LEAD_NANOS = 5_000_000L;
    private static final long MIN_ADAPTIVE_LEAD_NANOS = 0L;
    private static final long ADAPTIVE_SAFETY_NANOS = 500_000L;

    private static volatile AutoClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

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

    private Runnable pendingScheduledClick;
    private long scheduledClickGeneration = 0;

    private long activeScheduledTargetNanos = 0;
    private boolean activeIsScheduledSingleClick = false;

    private volatile long averageCompletionOverheadNanos = 0;
    private volatile long adaptiveDispatchLeadNanos = 0;
    private volatile long adaptiveSamples = 0;

    private volatile boolean lastBatchRejectedForInterval = false;
    private volatile int lastBatchSkippedByDeadline = 0;
    private volatile int lastBatchRejectedClickCount = 0;

    private volatile long successfulClicks = 0;
    private volatile long failedClicks = 0;

    private volatile long lastGestureLatencyNanos = 0;
    private volatile long totalGestureLatencyNanos = 0;
    private volatile long maxGestureLatencyNanos = 0;
    private volatile long measuredGestures = 0;

    private volatile String lastError = "هنوز کلیکی ارسال نشده";

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
        // No page events are required.
    }

    @Override
    public void onInterrupt() {
        cancelPendingScheduledClick();

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
        cancelPendingScheduledClick();
        serviceReady = false;

        Runnable cleanup = () -> {
            if (gestureInProgress) {
                finishGesture(
                        activeGestureId,
                        false,
                        "سرویس بسته شد"
                );
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run();
        } else {
            handler.post(cleanup);
        }

        if (instance == this) {
            instance = null;
        }

        Log.i(TAG, "Accessibility service destroyed");

        super.onDestroy();
    }

    // ---------------------------------------------------------
    // Service state and target
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
    // Statistics
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

    public long getAdaptiveDispatchLeadNanos() {
        return adaptiveDispatchLeadNanos;
    }

    public long getAdaptiveSamples() {
        return adaptiveSamples;
    }

    public void cancelPendingScheduledClick() {
        scheduledClickGeneration++;

        if (pendingScheduledClick != null) {
            handler.removeCallbacks(pendingScheduledClick);
            pendingScheduledClick = null;
        }
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

        lastGestureLatencyNanos = 0;
        totalGestureLatencyNanos = 0;
        maxGestureLatencyNanos = 0;
        measuredGestures = 0;

        nextAllowedClickNanos = 0;

        averageCompletionOverheadNanos = 0;
        adaptiveDispatchLeadNanos = 0;
        adaptiveSamples = 0;

        lastBatchRejectedForInterval = false;
        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedClickCount = 0;

        lastError = "آماده";
    }

    // ---------------------------------------------------------
    // Batch capacity
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

        int maxStrokes = GestureDescription.getMaxStrokeCount();
        long maxDuration = GestureDescription.getMaxGestureDuration();

        if (maxStrokes <= 0 || maxDuration < TAP_DURATION_MS) {
            return 0;
        }

        long maxByDuration =
                ((maxDuration - TAP_DURATION_MS) / effectiveInterval) + 1L;

        long result = Math.min((long) maxStrokes, maxByDuration);

        return (int) Math.max(
                1L,
                Math.min(result, Integer.MAX_VALUE)
        );
    }

    // ---------------------------------------------------------
    // Single click
    // ---------------------------------------------------------

    public void performClick(Runnable completion) {
        performClickBatch(1, 0, completion);
    }

    // ---------------------------------------------------------
    // Batch click
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

        if (!serviceReady) {
            lastError = "سرویس دسترسی‌پذیری آماده نیست";
            completeSafely(completion);
            return 0;
        }

        if (gestureInProgress || pendingScheduledClick != null) {
            lastError = "کلیک قبلی هنوز در حال اجرا یا زمان‌بندی است";
            lastBatchRejectedClickCount = Math.max(0, requestedCount);
            completeSafely(completion);
            return 0;
        }

        if (requestedCount <= 0 || requestedIntervalMs < 0) {
            lastError = "تعداد یا فاصله کلیک معتبر نیست";
            completeSafely(completion);
            return 0;
        }

        final long effectiveInterval =
                getEffectiveClickIntervalMs(requestedIntervalMs);

        if (effectiveInterval <= 0
                || effectiveInterval > Long.MAX_VALUE / NANOS_PER_MS) {
            lastError = "فاصله مؤثر کلیک معتبر نیست";
            completeSafely(completion);
            return 0;
        }

        final long intervalNanos = effectiveInterval * NANOS_PER_MS;
        final long nowNanos = SystemClock.elapsedRealtimeNanos();

        if (nextAllowedClickNanos > 0
                && nowNanos < nextAllowedClickNanos) {

            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = requestedCount;
            lastError = "کلیک به علت رعایت فاصله زمانی ارسال نشد";

            completeSafely(completion);
            return 0;
        }

        final int maxBatchCount =
                getMaxBatchClickCount(requestedIntervalMs);

        if (maxBatchCount <= 0) {
            lastError = "ظرفیت اجرای Gesture معتبر نیست";
            completeSafely(completion);
            return 0;
        }

        int deadlineAllowedCount = requestedCount;

        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE) {
            long deadlineNanos =
                    elapsedMsToNanos(deadlineElapsedRealtimeMs);

            long remainingNanos = deadlineNanos - nowNanos;
            long tapNanos = TAP_DURATION_MS * NANOS_PER_MS;

            if (remainingNanos < tapNanos) {
                deadlineAllowedCount = 0;
            } else {
                long countByDeadline =
                        ((remainingNanos - tapNanos) / intervalNanos) + 1L;

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
            lastError = "زمان کافی تا پایان چرخه برای ارسال کلیک وجود ندارد";
            completeSafely(completion);
            return 0;
        }

        final int clickX = targetX;
        final int clickY = targetY;

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        try {
            for (int i = 0; i < batchCount; i++) {
                long startOffset = safeMultiply(i, effectiveInterval);

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
            lastError = "خطا در ساخت Gesture: " + e.getMessage();
            Log.e(TAG, lastError, e);

            lastBatchRejectedClickCount = batchCount;
            completeSafely(completion);
            return 0;
        }

        final GestureDescription gesture;

        try {
            gesture = builder.build();
        } catch (Exception e) {
            lastError = "خطا در آماده‌سازی Gesture: " + e.getMessage();
            Log.e(TAG, lastError, e);

            lastBatchRejectedClickCount = batchCount;
            completeSafely(completion);
            return 0;
        }

        final long dispatchCheckNanos =
                SystemClock.elapsedRealtimeNanos();

        if (!fitsDeadline(
                dispatchCheckNanos,
                batchCount,
                intervalNanos,
                deadlineElapsedRealtimeMs
        )) {
            lastBatchSkippedByDeadline = requestedCount;
            lastError = "دسته کلیک به دلیل عبور از مهلت چرخه ارسال نشد";

            completeSafely(completion);
            return 0;
        }

        gestureInProgress = true;
        activeCompletion = completion;
        activeBatchClickCount = batchCount;
        activeBatchIntervalMs = effectiveInterval;
        activeIsScheduledSingleClick = false;
        activeScheduledTargetNanos = 0L;

        final long gestureId = ++activeGestureId;

        dispatchStartNanos = SystemClock.elapsedRealtimeNanos();

        if (!fitsDeadline(
                dispatchStartNanos,
                batchCount,
                intervalNanos,
                deadlineElapsedRealtimeMs
        )) {
            clearActiveGestureState();

            lastBatchSkippedByDeadline = requestedCount;
            lastError = "دسته کلیک به دلیل عبور از مهلت چرخه ارسال نشد";

            completeSafely(completion);
            return 0;
        }

        nextAllowedClickNanos = safeAdd(
                dispatchStartNanos,
                safeMultiply(batchCount, intervalNanos)
        );

        Log.d(
                TAG,
                "Dispatch batch"
                        + " | count=" + batchCount
                        + " | interval=" + effectiveInterval
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
            lastError = "خطای ارسال Gesture: " + e.getMessage();
            Log.e(TAG, lastError, e);
        }

        if (!accepted) {
            finishGesture(
                    gestureId,
                    false,
                    "dispatchGesture درخواست را نپذیرفت"
            );

            return 0;
        }

        return batchCount;
    }

    // ---------------------------------------------------------
    // Absolute-time single click scheduling
    // ---------------------------------------------------------

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            Runnable completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> performClickAtTarget(
                    targetElapsedRealtimeNanos,
                    cycleStartElapsedRealtimeNanos,
                    cycleDeadlineElapsedRealtimeNanos,
                    completion
            ));

            return true;
        }

        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedForInterval = false;
        lastBatchRejectedClickCount = 0;

        if (!serviceReady) {
            lastError = "سرویس دسترسی‌پذیری آماده نیست";
            completeSafely(completion);
            return false;
        }

        if (gestureInProgress || pendingScheduledClick != null) {
            lastError = "کلیک قبلی هنوز در حال اجرا یا زمان‌بندی است";
            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = 1;

            completeSafely(completion);
            return false;
        }

        if (targetElapsedRealtimeNanos < cycleStartElapsedRealtimeNanos
                || cycleDeadlineElapsedRealtimeNanos <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos >= cycleDeadlineElapsedRealtimeNanos) {

            lastError = "زمان هدف کلیک خارج از محدوده چرخه است";
            lastBatchSkippedByDeadline = 1;

            completeSafely(completion);
            return false;
        }

        final long generation = ++scheduledClickGeneration;

        final boolean isFirstCycleClick =
                targetElapsedRealtimeNanos == cycleStartElapsedRealtimeNanos;

        final long leadNanos = isFirstCycleClick
                ? 0L
                : Math.max(
                        MIN_ADAPTIVE_LEAD_NANOS,
                        Math.min(
                                MAX_ADAPTIVE_LEAD_NANOS,
                                adaptiveDispatchLeadNanos
                        )
                );

        final long desiredDispatchNanos = Math.max(
                cycleStartElapsedRealtimeNanos,
                targetElapsedRealtimeNanos - leadNanos
        );

        pendingScheduledClick = new Runnable() {
            @Override
            public void run() {
                if (generation != scheduledClickGeneration) {
                    return;
                }

                long now = SystemClock.elapsedRealtimeNanos();

                if (now < cycleStartElapsedRealtimeNanos) {
                    postAgain(remainingDelayMs(
                            cycleStartElapsedRealtimeNanos,
                            now
                    ));
                    return;
                }

                if (now < desiredDispatchNanos) {
                    postAgain(remainingDelayMs(
                            desiredDispatchNanos,
                            now
                    ));
                    return;
                }

                /*
                 * تغییر مهم:
                 * اگر Handler دیر بیدار شود، کلیک حذف نمی‌شود.
                 * کلیک در اولین فرصت ارسال می‌شود، مشروط بر اینکه
                 * مهلت چرخه هنوز اجازه اجرای آن را بدهد.
                 */

                long dispatchNow = SystemClock.elapsedRealtimeNanos();

                updateAdaptiveTimingEstimate(
                        Math.max(0L, dispatchNow - desiredDispatchNanos)
                );

                long tapEnd = safeAdd(
                        dispatchNow,
                        TAP_DURATION_MS * NANOS_PER_MS
                );

                if (dispatchNow >= cycleDeadlineElapsedRealtimeNanos
                        || tapEnd > cycleDeadlineElapsedRealtimeNanos) {

                    pendingScheduledClick = null;
                    lastBatchSkippedByDeadline = 1;
                    lastError = "مهلت چرخه برای اجرای کلیک تمام شده است";

                    completeSafely(completion);
                    return;
                }

                pendingScheduledClick = null;

                dispatchScheduledSingleClick(
                        targetElapsedRealtimeNanos,
                        completion
                );
            }

            private void postAgain(long delayMs) {
                handler.postDelayed(this, delayMs);
            }
        };

        long now = SystemClock.elapsedRealtimeNanos();

        long delayMs = desiredDispatchNanos <= now
                ? 0L
                : remainingDelayMs(desiredDispatchNanos, now);

        if (delayMs == 0L) {
            pendingScheduledClick.run();
        } else {
            handler.postDelayed(pendingScheduledClick, delayMs);
        }

        return true;
    }

    private void dispatchScheduledSingleClick(
            long targetElapsedRealtimeNanos,
            Runnable completion
    ) {
        if (!serviceReady || gestureInProgress) {
            lastError = "سرویس برای ارسال کلیک آماده نیست";
            completeSafely(completion);
            return;
        }

        final long now = SystemClock.elapsedRealtimeNanos();
        final int clickX = targetX;
        final int clickY = targetY;

        Path path = new Path();
        path.moveTo(clickX, clickY);

        final GestureDescription gesture;

        try {
            GestureDescription.Builder builder =
                    new GestureDescription.Builder();

            builder.addStroke(
                    new GestureDescription.StrokeDescription(
                            path,
                            0L,
                            TAP_DURATION_MS
                    )
            );

            gesture = builder.build();

        } catch (Exception e) {
            lastError = "خطا در ساخت تک‌کلیک: " + e.getMessage();
            failedClicks++;

            Log.e(TAG, lastError, e);

            completeSafely(completion);
            return;
        }

        gestureInProgress = true;
        activeCompletion = completion;
        activeBatchClickCount = 1;
        activeBatchIntervalMs = 0L;

        activeScheduledTargetNanos = targetElapsedRealtimeNanos;
        activeIsScheduledSingleClick = true;

        dispatchStartNanos = now;
        nextAllowedClickNanos = safeAdd(
                now,
                TAP_DURATION_MS * NANOS_PER_MS
        );

        final long gestureId = ++activeGestureId;

        Log.d(
                TAG,
                "Dispatch scheduled single click"
                        + " | targetNanos=" + targetElapsedRealtimeNanos
                        + " | dispatchNanos=" + now
                        + " | latenessNanos="
                        + Math.max(0L, now - targetElapsedRealtimeNanos)
                        + " | adaptiveLeadNanos=" + adaptiveDispatchLeadNanos
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
                                    "تک‌کلیک زمان‌بندی‌شده تکمیل شد"
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
                                    "اندروید تک‌کلیک را لغو کرد"
                            );
                        }
                    },
                    handler
            );
        } catch (Exception e) {
            lastError = "خطای ارسال تک‌کلیک: " + e.getMessage();
            Log.e(TAG, lastError, e);
        }

        if (!accepted) {
            finishGesture(
                    gestureId,
                    false,
                    "اندروید درخواست تک‌کلیک را نپذیرفت"
            );
        }
    }

    // ---------------------------------------------------------
    // Adaptive timing
    // ---------------------------------------------------------

    private void updateAdaptiveTimingEstimate(long wakeupLatenessNanos) {
        long lateness = Math.max(0L, wakeupLatenessNanos);

        if (adaptiveSamples == 0) {
            averageCompletionOverheadNanos = lateness;
        } else {
            averageCompletionOverheadNanos =
                    (averageCompletionOverheadNanos * 3L + lateness) / 4L;
        }

        adaptiveSamples++;

        long recommendedLead = safeAdd(
                safeMultiply(averageCompletionOverheadNanos, 2L),
                ADAPTIVE_SAFETY_NANOS
        );

        adaptiveDispatchLeadNanos = Math.max(
                MIN_ADAPTIVE_LEAD_NANOS,
                Math.min(
                        MAX_ADAPTIVE_LEAD_NANOS,
                        recommendedLead
                )
        );
    }

    // ---------------------------------------------------------
    // Gesture completion
    // ---------------------------------------------------------

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

        if (!gestureInProgress || gestureId != activeGestureId) {
            return;
        }

        long latency = Math.max(
                0L,
                SystemClock.elapsedRealtimeNanos() - dispatchStartNanos
        );

        lastGestureLatencyNanos = latency;
        measuredGestures++;

        if (totalGestureLatencyNanos <= Long.MAX_VALUE - latency) {
            totalGestureLatencyNanos += latency;
        }

        maxGestureLatencyNanos = Math.max(
                maxGestureLatencyNanos,
                latency
        );

        int completedBatchCount = activeBatchClickCount;

        if (success) {
            successfulClicks += completedBatchCount;
            lastError = message;

            Log.d(
                    TAG,
                    message
                            + " | batchClicks=" + completedBatchCount
                            + " | interval=" + activeBatchIntervalMs
                            + " | latencyNanos=" + latency
            );
        } else {
            failedClicks += completedBatchCount;
            lastError = message;

            Log.e(
                    TAG,
                    message
                            + " | batchClicks=" + completedBatchCount
                            + " | latencyNanos=" + latency
            );
        }

        gestureInProgress = false;

        activeBatchClickCount = 0;
        activeBatchIntervalMs = 0L;
        activeIsScheduledSingleClick = false;
        activeScheduledTargetNanos = 0L;

        Runnable callback = activeCompletion;
        activeCompletion = null;

        completeSafely(callback);
    }

    private void clearActiveGestureState() {
        gestureInProgress = false;
        activeCompletion = null;
        activeBatchClickCount = 0;
        activeBatchIntervalMs = 0L;
        activeIsScheduledSingleClick = false;
        activeScheduledTargetNanos = 0L;
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private void completeSafely(Runnable completion) {
        if (completion == null) {
            return;
        }

        try {
            completion.run();
        } catch (Exception e) {
            Log.e(TAG, "Completion callback failed", e);
        }
    }

    private long elapsedMsToNanos(long elapsedMs) {
        if (elapsedMs == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }

        if (elapsedMs <= 0L) {
            return 0L;
        }

        if (elapsedMs > Long.MAX_VALUE / NANOS_PER_MS) {
            return Long.MAX_VALUE;
        }

        return elapsedMs * NANOS_PER_MS;
    }

    private long safeAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }

        if (second < 0L && first < Long.MIN_VALUE - second) {
            return Long.MIN_VALUE;
        }

        return first + second;
    }

    private long safeMultiply(long first, long second) {
        if (first == 0L || second == 0L) {
            return 0L;
        }

        if (first > 0L && second > 0L
                && first > Long.MAX_VALUE / second) {
            return Long.MAX_VALUE;
        }

        if (first < 0L && second < 0L
                && first < Long.MAX_VALUE / second) {
            return Long.MAX_VALUE;
        }

        if (first > 0L && second < 0L
                && second < Long.MIN_VALUE / first) {
            return Long.MIN_VALUE;
        }

        if (first < 0L && second > 0L
                && first < Long.MIN_VALUE / second) {
            return Long.MIN_VALUE;
        }

        return first * second;
    }

    private long remainingDelayMs(long targetNanos, long nowNanos) {
        if (targetNanos <= nowNanos) {
            return 0L;
        }

        long difference = targetNanos - nowNanos;

        if (difference <= 0L) {
            return 0L;
        }

        long rounded = safeAdd(difference, NANOS_PER_MS - 1L);

        return Math.max(1L, rounded / NANOS_PER_MS);
    }

    private boolean fitsDeadline(
            long dispatchNanos,
            int count,
            long intervalNanos,
            long deadlineElapsedRealtimeMs
    ) {
        if (deadlineElapsedRealtimeMs == Long.MAX_VALUE) {
            return true;
        }

        long deadlineNanos =
                elapsedMsToNanos(deadlineElapsedRealtimeMs);

        long lastStrokeOffset = safeMultiply(
                count - 1L,
                intervalNanos
        );

        long lastStrokeEndNanos = safeAdd(
                safeAdd(
                        dispatchNanos,
                        lastStrokeOffset
                ),
                TAP_DURATION_MS * NANOS_PER_MS
        );

        return lastStrokeEndNanos <= deadlineNanos;
    }
}
