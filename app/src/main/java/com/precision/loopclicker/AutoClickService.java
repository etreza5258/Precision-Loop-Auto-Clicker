package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AutoClickService extends AccessibilityService {

    private static final String TAG = "PrecisionAutoClick";
    private static final long TAP_DURATION_MS = 1L;
    private static final long NANOS_PER_MS = 1_000_000L;

    private static volatile AutoClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile int targetX = 500;
    private volatile int targetY = 500;
    private volatile boolean gestureInProgress;
    private volatile boolean serviceReady;

    private Runnable activeCompletion;
    private long activeGestureId;
    private long dispatchStartNanos;
    private int activeBatchClickCount;
    private long activeBatchIntervalMs;
    private long nextAllowedClickNanos;

    private Runnable pendingScheduledClick;
    private long scheduledClickGeneration;

    private volatile long successfulClicks;
    private volatile long failedClicks;
    private volatile long lastGestureLatencyNanos;
    private volatile long totalGestureLatencyNanos;
    private volatile long maxGestureLatencyNanos;
    private volatile long measuredGestures;

    private volatile boolean lastBatchRejectedForInterval;
    private volatile int lastBatchRejectedClickCount;
    private volatile String lastError = "هنوز کلیکی ارسال نشده";

    // -------------------------------------------------
    // Callback benchmark
    // -------------------------------------------------

    public interface CallbackBenchmarkListener {
        void onBenchmarkFinished(CallbackBenchmarkResult result);
    }

    public static final class CallbackBenchmarkResult {
        public final int requestedAttempts;
        public final int attemptedAttempts;
        public final int callbacks;
        public final int successfulCallbacks;
        public final int cancelledCallbacks;
        public final int rejectedAttempts;
        public final long minCallbackMs;
        public final double averageCallbackMs;
        public final long medianCallbackMs;
        public final long p95CallbackMs;
        public final long maxCallbackMs;
        public final List<Long> samplesMs;
        public final boolean cancelled;

        private CallbackBenchmarkResult(
                int requested,
                int attempted,
                int callbacks,
                int successful,
                int cancelledCallbacks,
                int rejected,
                long min,
                double average,
                long median,
                long p95,
                long max,
                List<Long> samples,
                boolean cancelled
        ) {
            requestedAttempts = requested;
            attemptedAttempts = attempted;
            this.callbacks = callbacks;
            successfulCallbacks = successful;
            this.cancelledCallbacks = cancelledCallbacks;
            rejectedAttempts = rejected;
            minCallbackMs = min;
            averageCallbackMs = average;
            medianCallbackMs = median;
            p95CallbackMs = p95;
            maxCallbackMs = max;
            samplesMs = Collections.unmodifiableList(
                    new ArrayList<>(samples)
            );
            this.cancelled = cancelled;
        }
    }

    private volatile boolean callbackBenchmarkRunning;
    private volatile boolean callbackBenchmarkCancelRequested;

    private boolean activeBenchmarkAttempt;
    private boolean benchmarkDispatchAccepted;

    private int benchmarkRequested;
    private int benchmarkAttempted;
    private int benchmarkCallbacks;
    private int benchmarkSuccessful;
    private int benchmarkCancelled;
    private int benchmarkRejected;

    private long benchmarkIntervalAfterCallbackMs = 50L;
    private long benchmarkAttemptStartNanos;
    private long benchmarkGeneration;

    private Runnable pendingBenchmarkStep;
    private CallbackBenchmarkListener benchmarkListener;

    private final ArrayList<Long> benchmarkSamplesMs = new ArrayList<>();

    private volatile CallbackBenchmarkResult lastCallbackBenchmarkResult;

    // -------------------------------------------------
    // Dispatch timing benchmark
    // -------------------------------------------------

    public interface DispatchTimingBenchmarkListener {
        void onBenchmarkFinished(DispatchTimingBenchmarkResult result);
    }

    public static final class DispatchTimingBenchmarkResult {
        public final int requestedAttempts;
        public final int attemptedAttempts;
        public final int acceptedDispatches;
        public final int callbacks;
        public final int successfulCallbacks;
        public final int cancelledCallbacks;
        public final int rejectedAttempts;

        public final double requestedIntervalMs;
        public final double minActualIntervalMs;
        public final double averageActualIntervalMs;
        public final double medianActualIntervalMs;
        public final double p95ActualIntervalMs;
        public final double maxActualIntervalMs;
        public final double averageIntervalErrorMs;
        public final double p95AbsoluteIntervalErrorMs;
        public final double averageDispatchLatenessMs;
        public final double p95DispatchLatenessMs;

        public final boolean cancelled;
        public final String cancellationReason;
        public final int interruptions;

        private DispatchTimingBenchmarkResult(
                int requested,
                int attempted,
                int accepted,
                int callbacks,
                int successful,
                int cancelledCallbacks,
                int rejected,
                double requestedInterval,
                double min,
                double average,
                double median,
                double p95,
                double max,
                double averageError,
                double p95Error,
                double averageLateness,
                double p95Lateness,
                boolean cancelled,
                String reason,
                int interruptions
        ) {
            requestedAttempts = requested;
            attemptedAttempts = attempted;
            acceptedDispatches = accepted;
            this.callbacks = callbacks;
            successfulCallbacks = successful;
            this.cancelledCallbacks = cancelledCallbacks;
            rejectedAttempts = rejected;

            requestedIntervalMs = requestedInterval;
            minActualIntervalMs = min;
            averageActualIntervalMs = average;
            medianActualIntervalMs = median;
            p95ActualIntervalMs = p95;
            maxActualIntervalMs = max;
            averageIntervalErrorMs = averageError;
            p95AbsoluteIntervalErrorMs = p95Error;
            averageDispatchLatenessMs = averageLateness;
            p95DispatchLatenessMs = p95Lateness;

            this.cancelled = cancelled;
            cancellationReason = reason;
            this.interruptions = interruptions;
        }
    }

    private volatile boolean dispatchTimingBenchmarkRunning;
    private volatile boolean dispatchTimingBenchmarkCancelRequested;

    private boolean activeTimingBenchmarkAttempt;
    private boolean timingDispatchAccepted;

    private int timingBenchmarkRequested;
    private int timingBenchmarkAttempted;
    private int timingBenchmarkAccepted;
    private int timingBenchmarkCallbacks;
    private int timingBenchmarkSuccessful;
    private int timingBenchmarkCancelled;
    private int timingBenchmarkRejected;
    private int timingBenchmarkInterruptions;

    private String timingBenchmarkCancellationReason = "";

    private long timingBenchmarkIntervalNanos;
    private long timingBenchmarkStartNanos;
    private long timingBenchmarkTargetNanos;
    private long timingBenchmarkPreviousDispatchNanos;
    private long timingBenchmarkGeneration;

    private Runnable pendingTimingBenchmarkStep;
    private DispatchTimingBenchmarkListener timingBenchmarkListener;

    private final ArrayList<Double> timingActualIntervalsMs = new ArrayList<>();
    private final ArrayList<Double> timingIntervalErrorsMs = new ArrayList<>();
    private final ArrayList<Double> timingAbsoluteIntervalErrorsMs = new ArrayList<>();
    private final ArrayList<Double> timingDispatchLatenessMs = new ArrayList<>();

    private volatile DispatchTimingBenchmarkResult lastDispatchTimingBenchmarkResult;

    // -------------------------------------------------
    // Lifecycle
    // -------------------------------------------------

    public static AutoClickService getInstance() {
        AutoClickService service = instance;
        return service != null && service.serviceReady ? service : null;
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
    }

    @Override
    public void onInterrupt() {
        cancelPendingScheduledClick();
        cancelCallbackBenchmark();

        handler.post(() -> {
            if (dispatchTimingBenchmarkRunning) {
                timingBenchmarkInterruptions++;
            }

            if (gestureInProgress
                    && !activeTimingBenchmarkAttempt
                    && !activeBenchmarkAttempt) {
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
        cancelCallbackBenchmark();
        cancelDispatchTimingBenchmark("سرویس دسترس‌پذیری بسته شد");

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

        if (instance == this) instance = null;

        super.onDestroy();
    }

    // -------------------------------------------------
    // State and target
    // -------------------------------------------------

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
        return measuredGestures == 0
                ? 0L
                : totalGestureLatencyNanos / measuredGestures;
    }

    public long getMeasuredGestures() {
        return measuredGestures;
    }

    // برای سازگاری با کدهای قبلی؛ زمان تطبیقی حذف شده است.
    public long getAdaptiveDispatchLeadNanos() {
        return 0L;
    }

    public long getAdaptiveSamples() {
        return 0L;
    }

    public boolean wasLastBatchRejectedForInterval() {
        return lastBatchRejectedForInterval;
    }

    public int getLastBatchRejectedClickCount() {
        return lastBatchRejectedClickCount;
    }

    public boolean isCallbackBenchmarkRunning() {
        return callbackBenchmarkRunning;
    }

    public CallbackBenchmarkResult getLastCallbackBenchmarkResult() {
        return lastCallbackBenchmarkResult;
    }

    public boolean isDispatchTimingBenchmarkRunning() {
        return dispatchTimingBenchmarkRunning;
    }

    public DispatchTimingBenchmarkResult getLastDispatchTimingBenchmarkResult() {
        return lastDispatchTimingBenchmarkResult;
    }

    // -------------------------------------------------
    // Counters
    // -------------------------------------------------

    public void resetClickCounters() {
        successfulClicks = 0;
        failedClicks = 0;
        lastGestureLatencyNanos = 0;
        totalGestureLatencyNanos = 0;
        maxGestureLatencyNanos = 0;
        measuredGestures = 0;
        nextAllowedClickNanos = 0;
        lastBatchRejectedForInterval = false;
        lastBatchRejectedClickCount = 0;
        lastError = "آماده";
    }

    // -------------------------------------------------
    // Batch capacity
    // -------------------------------------------------

    public long getEffectiveClickIntervalMs(long requestedIntervalMs) {
        if (requestedIntervalMs < 0) return -1L;
        return Math.max(1L, requestedIntervalMs);
    }

    public int getMaxBatchClickCount(long intervalMs) {
        long interval = getEffectiveClickIntervalMs(intervalMs);
        if (interval <= 0) return 0;

        int strokes = GestureDescription.getMaxStrokeCount();
        long duration = GestureDescription.getMaxGestureDuration();

        if (strokes <= 0 || duration < TAP_DURATION_MS) return 0;

        long maxByDuration =
                ((duration - TAP_DURATION_MS) / interval) + 1L;

        return (int) Math.max(
                1L,
                Math.min(strokes, maxByDuration)
        );
    }

    // -------------------------------------------------
    // Single and batch click
    // -------------------------------------------------

    public void performClick(Runnable completion) {
        performClickBatch(1, 0L, completion);
    }

    public int performClickBatch(
            int count,
            long intervalMs,
            Runnable completion
    ) {
        return performClickBatchInternal(
                count, intervalMs, 0L, completion
        );
    }

    public int performClickBatchAtTarget(
            int count,
            long intervalMs,
            long targetNanos,
            Runnable completion
    ) {
        return performClickBatchInternal(
                count, intervalMs, targetNanos, completion
        );
    }

    private int performClickBatchInternal(
            int requestedCount,
            long requestedIntervalMs,
            long firstTargetNanos,
            Runnable completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> performClickBatchInternal(
                    requestedCount,
                    requestedIntervalMs,
                    firstTargetNanos,
                    completion
            ));
            return 0;
        }

        lastBatchRejectedForInterval = false;
        lastBatchRejectedClickCount = 0;

        if (!serviceReady) {
            lastError = "سرویس دسترسی‌پذیری آماده نیست";
            completeSafely(completion);
            return 0;
        }

        if ((callbackBenchmarkRunning && !activeBenchmarkAttempt)
                || (dispatchTimingBenchmarkRunning
                && !activeTimingBenchmarkAttempt)) {
            lastError = "آزمایش زمان‌بندی در حال اجراست";
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

        long interval = getEffectiveClickIntervalMs(requestedIntervalMs);

        if (interval <= 0 || interval > Long.MAX_VALUE / NANOS_PER_MS) {
            lastError = "فاصله مؤثر کلیک معتبر نیست";
            completeSafely(completion);
            return 0;
        }

        long now = SystemClock.elapsedRealtimeNanos();
        long offsetNanos = 0L;

        if (firstTargetNanos > now) {
            long remaining = firstTargetNanos - now;
            long guard = 18L * NANOS_PER_MS;
            offsetNanos = Math.max(0L, remaining - guard);
        }

        long offsetMs = offsetNanos == 0
                ? 0L
                : (offsetNanos + NANOS_PER_MS - 1L) / NANOS_PER_MS;

        if (nextAllowedClickNanos > 0 && now < nextAllowedClickNanos) {
            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = requestedCount;
            lastError = "کلیک به علت رعایت فاصله زمانی ارسال نشد";
            completeSafely(completion);
            return 0;
        }

        int maxCount = getMaxBatchClickCount(interval);
        long maxDuration = GestureDescription.getMaxGestureDuration();

        int maxByDuration = (int) Math.max(
                0L,
                Math.min(
                        Integer.MAX_VALUE,
                        (maxDuration - offsetMs - TAP_DURATION_MS) / interval + 1L
                )
        );

        int count = Math.min(
                requestedCount,
                Math.min(maxCount, maxByDuration)
        );

        if (count <= 0) {
            lastError = "ظرفیت زمانی Gesture کافی نیست";
            completeSafely(completion);
            return 0;
        }

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        try {
            for (int i = 0; i < count; i++) {
                long start = offsetMs + (long) i * interval;

                Path path = new Path();
                path.moveTo(targetX, targetY);

                builder.addStroke(
                        new GestureDescription.StrokeDescription(
                                path, start, TAP_DURATION_MS
                        )
                );
            }
        } catch (Exception e) {
            lastError = "خطا در ساخت Gesture: " + e.getMessage();
            lastBatchRejectedClickCount = count;
            completeSafely(completion);
            return 0;
        }

        GestureDescription gesture;

        try {
            gesture = builder.build();
        } catch (Exception e) {
            lastError = "خطا در آماده‌سازی Gesture: " + e.getMessage();
            lastBatchRejectedClickCount = count;
            completeSafely(completion);
            return 0;
        }

        gestureInProgress = true;
        activeCompletion = completion;
        activeBatchClickCount = count;
        activeBatchIntervalMs = interval;

        long id = ++activeGestureId;
        dispatchStartNanos = SystemClock.elapsedRealtimeNanos();

        nextAllowedClickNanos = dispatchStartNanos
                + offsetMs * NANOS_PER_MS
                + (long) count * interval * NANOS_PER_MS;

        boolean accepted = false;

        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription description) {
                            finishGesture(id, true, "دسته کلیک تکمیل شد");
                        }

                        @Override
                        public void onCancelled(GestureDescription description) {
                            finishGesture(id, false, "اندروید دسته کلیک را لغو کرد");
                        }
                    },
                    handler
            );
        } catch (Exception e) {
            lastError = "خطای ارسال Gesture: " + e.getMessage();
            Log.e(TAG, lastError, e);
        }

        if (activeTimingBenchmarkAttempt) {
            timingDispatchAccepted = accepted;
        }

        if (!accepted) {
            finishGesture(id, false, "dispatchGesture درخواست را نپذیرفت");
            return 0;
        }

        return count;
    }

    // -------------------------------------------------
    // Absolute-time single click
    // -------------------------------------------------

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            Runnable completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> performClickAtTarget(
                    targetElapsedRealtimeNanos,
                    completion
            ));
            return true;
        }

        lastBatchRejectedForInterval = false;
        lastBatchRejectedClickCount = 0;

        if (!serviceReady) {
            lastError = "سرویس دسترسی‌پذیری آماده نیست";
            completeSafely(completion);
            return false;
        }

        if (callbackBenchmarkRunning || dispatchTimingBenchmarkRunning) {
            lastError = "آزمایش زمان‌بندی در حال اجراست";
            completeSafely(completion);
            return false;
        }

        if (gestureInProgress || pendingScheduledClick != null) {
            lastError = "کلیک قبلی هنوز در حال اجراست";
            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = 1;
            completeSafely(completion);
            return false;
        }

        final long generation = ++scheduledClickGeneration;

        pendingScheduledClick = new Runnable() {
            @Override
            public void run() {
                if (generation != scheduledClickGeneration) return;

                long now = SystemClock.elapsedRealtimeNanos();

                if (now < targetElapsedRealtimeNanos) {
                    handler.postDelayed(
                            this,
                            remainingDelayMs(targetElapsedRealtimeNanos, now)
                    );
                    return;
                }

                pendingScheduledClick = null;

                dispatchScheduledSingleClick(
                        targetElapsedRealtimeNanos,
                        completion
                );
            }
        };

        long now = SystemClock.elapsedRealtimeNanos();

        if (targetElapsedRealtimeNanos <= now) {
            pendingScheduledClick.run();
        } else {
            handler.postDelayed(
                    pendingScheduledClick,
                    remainingDelayMs(targetElapsedRealtimeNanos, now)
            );
        }

        return true;
    }

    private void dispatchScheduledSingleClick(
            long targetNanos,
            Runnable completion
    ) {
        if (!serviceReady || gestureInProgress) {
            lastError = "سرویس برای ارسال کلیک آماده نیست";
            completeSafely(completion);
            return;
        }

        Path path = new Path();
        path.moveTo(targetX, targetY);

        GestureDescription gesture;

        try {
            GestureDescription.Builder builder =
                    new GestureDescription.Builder();

            builder.addStroke(
                    new GestureDescription.StrokeDescription(
                            path, 0L, TAP_DURATION_MS
                    )
            );

            gesture = builder.build();
        } catch (Exception e) {
            lastError = "خطا در ساخت تک‌کلیک: " + e.getMessage();
            failedClicks++;
            completeSafely(completion);
            return;
        }

        gestureInProgress = true;
        activeCompletion = completion;
        activeBatchClickCount = 1;
        activeBatchIntervalMs = 0L;

        dispatchStartNanos = SystemClock.elapsedRealtimeNanos();
        nextAllowedClickNanos = dispatchStartNanos + NANOS_PER_MS;

        long id = ++activeGestureId;

        Log.d(TAG,
                "Dispatch click | target=" + targetNanos
                        + " | actual=" + dispatchStartNanos
                        + " | difference="
                        + (dispatchStartNanos - targetNanos)
        );

        boolean accepted = false;

        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription description) {
                            finishGesture(id, true, "تک‌کلیک تکمیل شد");
                        }

                        @Override
                        public void onCancelled(GestureDescription description) {
                            finishGesture(id, false, "اندروید تک‌کلیک را لغو کرد");
                        }
                    },
                    handler
            );
        } catch (Exception e) {
            lastError = "خطای ارسال تک‌کلیک: " + e.getMessage();
            Log.e(TAG, lastError, e);
        }

        if (!accepted) {
            finishGesture(id, false, "اندروید درخواست تک‌کلیک را نپذیرفت");
        }
    }

    // -------------------------------------------------
    // Gesture completion
    // -------------------------------------------------

    private void finishGesture(
            long id,
            boolean success,
            String message
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> finishGesture(id, success, message));
            return;
        }

        if (!gestureInProgress || id != activeGestureId) return;

        long latency = Math.max(
                0L,
                SystemClock.elapsedRealtimeNanos() - dispatchStartNanos
        );

        boolean benchmarkGesture =
                activeBenchmarkAttempt || activeTimingBenchmarkAttempt;

        if (!benchmarkGesture) {
            lastGestureLatencyNanos = latency;
            measuredGestures++;
            totalGestureLatencyNanos += latency;
            maxGestureLatencyNanos = Math.max(maxGestureLatencyNanos, latency);
        }

        int count = activeBatchClickCount;

        gestureInProgress = false;
        activeBatchClickCount = 0;
        activeBatchIntervalMs = 0L;

        Runnable callback = activeCompletion;
        activeCompletion = null;

        if (activeBenchmarkAttempt) {
            if (benchmarkDispatchAccepted) {
                onBenchmarkGestureFinished(success, latency);
            } else {
                activeBenchmarkAttempt = false;
                benchmarkRejected++;
                scheduleNextBenchmarkAttempt(benchmarkGeneration);
            }

            completeSafely(callback);
            return;
        }

        if (activeTimingBenchmarkAttempt) {
            onDispatchTimingGestureFinished(success);
            completeSafely(callback);
            return;
        }

        if (success) {
            successfulClicks += count;
            lastError = message;
        } else {
            failedClicks += count;
            lastError = message;
        }

        Log.d(TAG,
                message + " | clicks=" + count
                        + " | latencyMs=" + latency / NANOS_PER_MS
        );

        completeSafely(callback);
    }

    // -------------------------------------------------
    // Scheduled click cancellation
    // -------------------------------------------------

    public void cancelPendingScheduledClick() {
        scheduledClickGeneration++;

        if (pendingScheduledClick != null) {
            handler.removeCallbacks(pendingScheduledClick);
            pendingScheduledClick = null;
        }
    }

    // -------------------------------------------------
    // Callback benchmark
    // -------------------------------------------------

    public boolean startCallbackBenchmark(
            int attempts,
            long intervalAfterCallbackMs,
            CallbackBenchmarkListener listener
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> startCallbackBenchmark(
                    attempts, intervalAfterCallbackMs, listener
            ));
            return true;
        }

        if (!serviceReady
                || callbackBenchmarkRunning
                || dispatchTimingBenchmarkRunning
                || gestureInProgress
                || pendingScheduledClick != null
                || attempts < 1
                || attempts > 5000
                || intervalAfterCallbackMs < 50) {
            return false;
        }

        callbackBenchmarkRunning = true;
        callbackBenchmarkCancelRequested = false;

        benchmarkRequested = attempts;
        benchmarkAttempted = 0;
        benchmarkCallbacks = 0;
        benchmarkSuccessful = 0;
        benchmarkCancelled = 0;
        benchmarkRejected = 0;
        benchmarkIntervalAfterCallbackMs = intervalAfterCallbackMs;
        benchmarkListener = listener;

        benchmarkSamplesMs.clear();
        lastCallbackBenchmarkResult = null;

        long generation = ++benchmarkGeneration;

        pendingBenchmarkStep = () ->
                runCallbackBenchmarkAttempt(generation);

        handler.post(pendingBenchmarkStep);
        return true;
    }

    public void cancelCallbackBenchmark() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::cancelCallbackBenchmark);
            return;
        }

        if (!callbackBenchmarkRunning) return;

        callbackBenchmarkCancelRequested = true;
        benchmarkGeneration++;

        if (pendingBenchmarkStep != null) {
            handler.removeCallbacks(pendingBenchmarkStep);
            pendingBenchmarkStep = null;
        }

        if (!activeBenchmarkAttempt) {
            finishCallbackBenchmark(true);
        }
    }

    private void runCallbackBenchmarkAttempt(long generation) {
        pendingBenchmarkStep = null;

        if (!callbackBenchmarkRunning
                || callbackBenchmarkCancelRequested
                || generation != benchmarkGeneration) {
            if (callbackBenchmarkRunning
                    && callbackBenchmarkCancelRequested
                    && !activeBenchmarkAttempt) {
                finishCallbackBenchmark(true);
            }
            return;
        }

        if (!serviceReady || gestureInProgress
                || pendingScheduledClick != null) {
            benchmarkRejected++;
            finishCallbackBenchmark(false);
            return;
        }

        if (benchmarkAttempted >= benchmarkRequested) {
            finishCallbackBenchmark(false);
            return;
        }

        benchmarkAttempted++;
        benchmarkAttemptStartNanos = SystemClock.elapsedRealtimeNanos();
        activeBenchmarkAttempt = true;
        benchmarkDispatchAccepted = false;

        int attempt = benchmarkAttempted;

        int dispatched = performClickBatch(1, 0L, () -> {
            if (!benchmarkDispatchAccepted && activeBenchmarkAttempt) {
                activeBenchmarkAttempt = false;
                benchmarkRejected++;
                scheduleNextBenchmarkAttempt(generation);
            }
        });

        benchmarkDispatchAccepted = dispatched > 0;

        if (dispatched <= 0
                && activeBenchmarkAttempt
                && !gestureInProgress) {
            activeBenchmarkAttempt = false;
            benchmarkRejected++;
            scheduleNextBenchmarkAttempt(generation);
        }

        Log.d(TAG, "Callback benchmark attempt " + attempt
                + " dispatched=" + dispatched);
    }

    private void onBenchmarkGestureFinished(
            boolean success,
            long latencyNanos
    ) {
        if (!activeBenchmarkAttempt) return;

        activeBenchmarkAttempt = false;
        benchmarkCallbacks++;

        benchmarkSamplesMs.add(
                Math.max(0L, latencyNanos / NANOS_PER_MS)
        );

        if (success) benchmarkSuccessful++;
        else benchmarkCancelled++;

        scheduleNextBenchmarkAttempt(benchmarkGeneration);
    }

    private void scheduleNextBenchmarkAttempt(long generation) {
        if (!callbackBenchmarkRunning) return;

        if (callbackBenchmarkCancelRequested
                || benchmarkAttempted >= benchmarkRequested) {
            finishCallbackBenchmark(callbackBenchmarkCancelRequested);
            return;
        }

        pendingBenchmarkStep = () ->
                runCallbackBenchmarkAttempt(generation);

        handler.postDelayed(
                pendingBenchmarkStep,
                benchmarkIntervalAfterCallbackMs
        );
    }

    private void finishCallbackBenchmark(boolean cancelled) {
        if (!callbackBenchmarkRunning) return;

        callbackBenchmarkRunning = false;
        callbackBenchmarkCancelRequested = false;
        activeBenchmarkAttempt = false;

        if (pendingBenchmarkStep != null) {
            handler.removeCallbacks(pendingBenchmarkStep);
            pendingBenchmarkStep = null;
        }

        ArrayList<Long> sorted = new ArrayList<>(benchmarkSamplesMs);
        Collections.sort(sorted);

        long min = 0, median = 0, p95 = 0, max = 0;
        double average = 0;

        if (!sorted.isEmpty()) {
            int size = sorted.size();

            min = sorted.get(0);
            max = sorted.get(size - 1);

            long sum = 0;
            for (long value : sorted) sum += value;
            average = (double) sum / size;

            median = size % 2 == 1
                    ? sorted.get(size / 2)
                    : (sorted.get(size / 2 - 1)
                    + sorted.get(size / 2)) / 2L;

            p95 = sorted.get(Math.max(
                    0,
                    (int) Math.ceil(size * 0.95) - 1
            ));
        }

        CallbackBenchmarkResult result =
                new CallbackBenchmarkResult(
                        benchmarkRequested,
                        benchmarkAttempted,
                        benchmarkCallbacks,
                        benchmarkSuccessful,
                        benchmarkCancelled,
                        benchmarkRejected,
                        min,
                        average,
                        median,
                        p95,
                        max,
                        benchmarkSamplesMs,
                        cancelled
                );

        lastCallbackBenchmarkResult = result;

        CallbackBenchmarkListener listener = benchmarkListener;
        benchmarkListener = null;

        if (listener != null) {
            try {
                listener.onBenchmarkFinished(result);
            } catch (Exception e) {
                Log.e(TAG, "Benchmark listener failed", e);
            }
        }
    }

    // -------------------------------------------------
    // Dispatch timing benchmark
    // -------------------------------------------------

    public boolean startDispatchTimingBenchmark(
            int attempts,
            long intervalMs,
            DispatchTimingBenchmarkListener listener
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> startDispatchTimingBenchmark(
                    attempts, intervalMs, listener
            ));
            return true;
        }

        if (!serviceReady
                || dispatchTimingBenchmarkRunning
                || callbackBenchmarkRunning
                || gestureInProgress
                || pendingScheduledClick != null
                || attempts < 2
                || attempts > 2000
                || intervalMs < 10
                || intervalMs > 60000) {
            return false;
        }

        dispatchTimingBenchmarkRunning = true;
        dispatchTimingBenchmarkCancelRequested = false;
        activeTimingBenchmarkAttempt = false;
        timingDispatchAccepted = false;

        timingBenchmarkRequested = attempts;
        timingBenchmarkAttempted = 0;
        timingBenchmarkAccepted = 0;
        timingBenchmarkCallbacks = 0;
        timingBenchmarkSuccessful = 0;
        timingBenchmarkCancelled = 0;
        timingBenchmarkRejected = 0;
        timingBenchmarkInterruptions = 0;
        timingBenchmarkCancellationReason = "";

        timingBenchmarkIntervalNanos = intervalMs * NANOS_PER_MS;
        timingBenchmarkStartNanos =
                SystemClock.elapsedRealtimeNanos() + 300L * NANOS_PER_MS;
        timingBenchmarkPreviousDispatchNanos = 0;
        timingBenchmarkListener = listener;

        timingActualIntervalsMs.clear();
        timingIntervalErrorsMs.clear();
        timingAbsoluteIntervalErrorsMs.clear();
        timingDispatchLatenessMs.clear();

        lastDispatchTimingBenchmarkResult = null;

        long generation = ++timingBenchmarkGeneration;
        scheduleDispatchTimingAttempt(generation);
        return true;
    }

    public void cancelDispatchTimingBenchmark() {
        cancelDispatchTimingBenchmark("لغو به درخواست کاربر");
    }

    private void cancelDispatchTimingBenchmark(String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> cancelDispatchTimingBenchmark(reason));
            return;
        }

        if (!dispatchTimingBenchmarkRunning) return;

        if (timingBenchmarkCancellationReason.isEmpty()) {
            timingBenchmarkCancellationReason = reason;
        }

        dispatchTimingBenchmarkCancelRequested = true;
        timingBenchmarkGeneration++;

        if (pendingTimingBenchmarkStep != null) {
            handler.removeCallbacks(pendingTimingBenchmarkStep);
            pendingTimingBenchmarkStep = null;
        }

        if (!activeTimingBenchmarkAttempt) {
            finishDispatchTimingBenchmark(true);
        }
    }

    private void scheduleDispatchTimingAttempt(long generation) {
        if (!dispatchTimingBenchmarkRunning) return;

        if (dispatchTimingBenchmarkCancelRequested
                || timingBenchmarkAttempted >= timingBenchmarkRequested) {
            finishDispatchTimingBenchmark(
                    dispatchTimingBenchmarkCancelRequested
            );
            return;
        }

        timingBenchmarkTargetNanos = timingBenchmarkStartNanos
                + (long) timingBenchmarkAttempted
                * timingBenchmarkIntervalNanos;

        pendingTimingBenchmarkStep = new Runnable() {
            @Override
            public void run() {
                if (!dispatchTimingBenchmarkRunning
                        || generation != timingBenchmarkGeneration
                        || dispatchTimingBenchmarkCancelRequested) {
                    return;
                }

                long now = SystemClock.elapsedRealtimeNanos();

                if (now < timingBenchmarkTargetNanos) {
                    handler.postDelayed(
                            this,
                            remainingDelayMs(timingBenchmarkTargetNanos, now)
                    );
                    return;
                }

                pendingTimingBenchmarkStep = null;
                runDispatchTimingAttempt(generation);
            }
        };

        long now = SystemClock.elapsedRealtimeNanos();
        long delay = remainingDelayMs(timingBenchmarkTargetNanos, now);

        if (delay == 0) pendingTimingBenchmarkStep.run();
        else handler.postDelayed(pendingTimingBenchmarkStep, delay);
    }

    private void runDispatchTimingAttempt(long generation) {
        if (!dispatchTimingBenchmarkRunning
                || generation != timingBenchmarkGeneration
                || dispatchTimingBenchmarkCancelRequested) {
            return;
        }

        if (!serviceReady || gestureInProgress
                || pendingScheduledClick != null) {
            timingBenchmarkRejected++;
            timingBenchmarkAttempted++;
            scheduleDispatchTimingAttempt(generation);
            return;
        }

        timingBenchmarkAttempted++;
        activeTimingBenchmarkAttempt = true;
        timingDispatchAccepted = false;

        int dispatched = performClickBatch(1, 0L, null);

        if (dispatched > 0) {
            timingDispatchAccepted = true;
            timingBenchmarkAccepted++;

            long actual = dispatchStartNanos;

            timingDispatchLatenessMs.add(
                    (actual - timingBenchmarkTargetNanos)
                            / (double) NANOS_PER_MS
            );

            if (timingBenchmarkPreviousDispatchNanos > 0) {
                double actualInterval =
                        (actual - timingBenchmarkPreviousDispatchNanos)
                                / (double) NANOS_PER_MS;

                double error = actualInterval
                        - timingBenchmarkIntervalNanos
                        / (double) NANOS_PER_MS;

                timingActualIntervalsMs.add(actualInterval);
                timingIntervalErrorsMs.add(error);
                timingAbsoluteIntervalErrorsMs.add(Math.abs(error));
            }

            timingBenchmarkPreviousDispatchNanos = actual;

        } else if (activeTimingBenchmarkAttempt) {
            activeTimingBenchmarkAttempt = false;
            timingBenchmarkRejected++;
            scheduleDispatchTimingAttempt(generation);
        }
    }

    private void onDispatchTimingGestureFinished(boolean success) {
        if (!activeTimingBenchmarkAttempt) return;

        activeTimingBenchmarkAttempt = false;

        if (timingDispatchAccepted) {
            timingBenchmarkCallbacks++;
            if (success) timingBenchmarkSuccessful++;
            else timingBenchmarkCancelled++;
        } else {
            timingBenchmarkRejected++;
        }

        timingDispatchAccepted = false;
        scheduleDispatchTimingAttempt(timingBenchmarkGeneration);
    }

    private void finishDispatchTimingBenchmark(boolean cancelled) {
        if (!dispatchTimingBenchmarkRunning) return;

        dispatchTimingBenchmarkRunning = false;
        dispatchTimingBenchmarkCancelRequested = false;
        activeTimingBenchmarkAttempt = false;

        if (pendingTimingBenchmarkStep != null) {
            handler.removeCallbacks(pendingTimingBenchmarkStep);
            pendingTimingBenchmarkStep = null;
        }

        double[] actual = summarizeDoubles(timingActualIntervalsMs);
        double[] errors = summarizeDoubles(timingAbsoluteIntervalErrorsMs);
        double[] lateness = summarizeDoubles(timingDispatchLatenessMs);

        double averageError = 0;

        if (!timingIntervalErrorsMs.isEmpty()) {
            double sum = 0;
            for (double value : timingIntervalErrorsMs) sum += value;
            averageError = sum / timingIntervalErrorsMs.size();
        }

        DispatchTimingBenchmarkResult result =
                new DispatchTimingBenchmarkResult(
                        timingBenchmarkRequested,
                        timingBenchmarkAttempted,
                        timingBenchmarkAccepted,
                        timingBenchmarkCallbacks,
                        timingBenchmarkSuccessful,
                        timingBenchmarkCancelled,
                        timingBenchmarkRejected,
                        timingBenchmarkIntervalNanos / (double) NANOS_PER_MS,
                        actual[0],
                        actual[1],
                        actual[2],
                        actual[3],
                        actual[4],
                        averageError,
                        errors[3],
                        lateness[1],
                        lateness[3],
                        cancelled,
                        cancelled ? timingBenchmarkCancellationReason : "",
                        timingBenchmarkInterruptions
                );

        lastDispatchTimingBenchmarkResult = result;

        DispatchTimingBenchmarkListener listener = timingBenchmarkListener;
        timingBenchmarkListener = null;

        if (listener != null) {
            try {
                listener.onBenchmarkFinished(result);
            } catch (Exception e) {
                Log.e(TAG, "Dispatch benchmark listener failed", e);
            }
        }
    }

    private static double[] summarizeDoubles(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return new double[]{0, 0, 0, 0, 0};
        }

        ArrayList<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        double sum = 0;
        for (double value : sorted) sum += value;

        int size = sorted.size();
        double median = size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1)
                + sorted.get(size / 2)) / 2.0;

        int p95 = Math.max(0, (int) Math.ceil(size * 0.95) - 1);

        return new double[]{
                sorted.get(0),
                sum / size,
                median,
                sorted.get(p95),
                sorted.get(size - 1)
        };
    }

    // -------------------------------------------------
    // Helpers
    // -------------------------------------------------

    private void completeSafely(Runnable completion) {
        if (completion == null) return;

        try {
            completion.run();
        } catch (Exception e) {
            Log.e(TAG, "Completion callback failed", e);
        }
    }

    private long remainingDelayMs(long targetNanos, long nowNanos) {
        if (targetNanos <= nowNanos) return 0;

        long difference = targetNanos - nowNanos;

        return Math.max(
                1L,
                (difference + NANOS_PER_MS - 1L) / NANOS_PER_MS
        );
    }
}