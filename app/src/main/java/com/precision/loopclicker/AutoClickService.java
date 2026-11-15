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
    private static final long MIN_SEQUENTIAL_INTERVAL_MS = 1L;
    private static final long NANOS_PER_MS = 1_000_000L;

    private static volatile AutoClickService instance;

    public interface DispatchListener {
        void onDispatched(long dispatchElapsedRealtimeNanos);
    }

    public interface CallbackBenchmarkListener {
        void onBenchmarkFinished(CallbackBenchmarkResult result);
    }

    public interface DispatchTimingBenchmarkListener {
        void onBenchmarkFinished(DispatchTimingBenchmarkResult result);
    }

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

    private volatile boolean lastBatchRejectedForInterval;
    private volatile int lastBatchSkippedByDeadline;
    private volatile int lastBatchRejectedClickCount;

    private volatile long successfulClicks;
    private volatile long failedClicks;
    private volatile long lastGestureLatencyNanos;
    private volatile long totalGestureLatencyNanos;
    private volatile long maxGestureLatencyNanos;
    private volatile long measuredGestures;

    private volatile String lastError = "هنوز کلیکی ارسال نشده";

    // ---------------------------------------------------------
    // Callback benchmark
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // Dispatch timing benchmark
    // ---------------------------------------------------------

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
                double minInterval,
                double averageInterval,
                double medianInterval,
                double p95Interval,
                double maxInterval,
                double averageError,
                double p95AbsoluteError,
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
            minActualIntervalMs = minInterval;
            averageActualIntervalMs = averageInterval;
            medianActualIntervalMs = medianInterval;
            p95ActualIntervalMs = p95Interval;
            maxActualIntervalMs = maxInterval;
            averageIntervalErrorMs = averageError;
            p95AbsoluteIntervalErrorMs = p95AbsoluteError;
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

    private long timingBenchmarkIntervalNanos = 50L * NANOS_PER_MS;
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

    // ---------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------

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
                Log.w(TAG, "Accessibility interruption during timing benchmark");
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
                finishGesture(activeGestureId, false, "سرویس بسته شد");
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
    // Public state and settings
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

    public long getAdaptiveDispatchLeadNanos() {
        return 0L;
    }

    public long getAdaptiveSamples() {
        return 0L;
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

    public void resetClickCounters() {
        successfulClicks = 0;
        failedClicks = 0;

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
    // Callback benchmark
    // ---------------------------------------------------------

    public boolean startCallbackBenchmark(
            int requestedAttempts,
            long intervalAfterCallbackMs,
            CallbackBenchmarkListener listener
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> startCallbackBenchmark(
                    requestedAttempts,
                    intervalAfterCallbackMs,
                    listener
            ));
            return true;
        }

        if (!serviceReady
                || callbackBenchmarkRunning
                || dispatchTimingBenchmarkRunning
                || gestureInProgress
                || pendingScheduledClick != null
                || requestedAttempts < 1
                || requestedAttempts > 5000
                || intervalAfterCallbackMs < 50L) {
            return false;
        }

        callbackBenchmarkRunning = true;
        callbackBenchmarkCancelRequested = false;

        benchmarkRequested = requestedAttempts;
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

        pendingBenchmarkStep = () -> runCallbackBenchmarkAttempt(generation);
        handler.post(pendingBenchmarkStep);

        return true;
    }

    public void cancelCallbackBenchmark() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::cancelCallbackBenchmark);
            return;
        }

        if (!callbackBenchmarkRunning) {
            return;
        }

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

        if (!serviceReady || gestureInProgress || pendingScheduledClick != null) {
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

        int attemptNumber = benchmarkAttempted;

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

        Log.d(TAG, "Callback benchmark attempt "
                + attemptNumber + " dispatched=" + dispatched);
    }

    private void onBenchmarkGestureFinished(
            boolean success,
            long latencyNanos
    ) {
        if (!activeBenchmarkAttempt) {
            return;
        }

        activeBenchmarkAttempt = false;
        benchmarkCallbacks++;

        benchmarkSamplesMs.add(
                Math.max(0L, latencyNanos / NANOS_PER_MS)
        );

        if (success) {
            benchmarkSuccessful++;
        } else {
            benchmarkCancelled++;
        }

        scheduleNextBenchmarkAttempt(benchmarkGeneration);
    }

    private void scheduleNextBenchmarkAttempt(long generation) {
        if (!callbackBenchmarkRunning) {
            return;
        }

        if (callbackBenchmarkCancelRequested
                || benchmarkAttempted >= benchmarkRequested) {
            finishCallbackBenchmark(callbackBenchmarkCancelRequested);
            return;
        }

        pendingBenchmarkStep = () -> runCallbackBenchmarkAttempt(generation);

        handler.postDelayed(
                pendingBenchmarkStep,
                benchmarkIntervalAfterCallbackMs
        );
    }

    private void finishCallbackBenchmark(boolean cancelled) {
        if (!callbackBenchmarkRunning) {
            return;
        }

        callbackBenchmarkRunning = false;
        callbackBenchmarkCancelRequested = false;
        activeBenchmarkAttempt = false;

        if (pendingBenchmarkStep != null) {
            handler.removeCallbacks(pendingBenchmarkStep);
            pendingBenchmarkStep = null;
        }

        ArrayList<Long> sorted = new ArrayList<>(benchmarkSamplesMs);
        Collections.sort(sorted);

        long min = 0L;
        long median = 0L;
        long p95 = 0L;
        long max = 0L;
        double average = 0.0;

        if (!sorted.isEmpty()) {
            min = sorted.get(0);
            max = sorted.get(sorted.size() - 1);

            long sum = 0L;
            for (long value : sorted) {
                sum += value;
            }

            average = (double) sum / sorted.size();

            int size = sorted.size();

            median = size % 2 == 1
                    ? sorted.get(size / 2)
                    : (sorted.get(size / 2 - 1)
                    + sorted.get(size / 2)) / 2L;

            p95 = sorted.get(Math.max(
                    0,
                    (int) Math.ceil(size * 0.95) - 1
            ));
        }

        CallbackBenchmarkResult result = new CallbackBenchmarkResult(
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

    // ---------------------------------------------------------
    // Dispatch timing benchmark
    // ---------------------------------------------------------

    public boolean startDispatchTimingBenchmark(
            int requestedAttempts,
            long requestedIntervalMs,
            DispatchTimingBenchmarkListener listener
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> startDispatchTimingBenchmark(
                    requestedAttempts,
                    requestedIntervalMs,
                    listener
            ));
            return true;
        }

        if (!serviceReady
                || dispatchTimingBenchmarkRunning
                || callbackBenchmarkRunning
                || gestureInProgress
                || pendingScheduledClick != null
                || requestedAttempts < 2
                || requestedAttempts > 2000
                || requestedIntervalMs < 10L
                || requestedIntervalMs > 60000L) {
            return false;
        }

        dispatchTimingBenchmarkRunning = true;
        dispatchTimingBenchmarkCancelRequested = false;

        activeTimingBenchmarkAttempt = false;
        timingDispatchAccepted = false;

        timingBenchmarkRequested = requestedAttempts;
        timingBenchmarkAttempted = 0;
        timingBenchmarkAccepted = 0;
        timingBenchmarkCallbacks = 0;
        timingBenchmarkSuccessful = 0;
        timingBenchmarkCancelled = 0;
        timingBenchmarkRejected = 0;
        timingBenchmarkInterruptions = 0;

        timingBenchmarkCancellationReason = "";
        timingBenchmarkIntervalNanos = requestedIntervalMs * NANOS_PER_MS;

        timingBenchmarkStartNanos = safeAdd(
                SystemClock.elapsedRealtimeNanos(),
                300L * NANOS_PER_MS
        );

        timingBenchmarkTargetNanos = timingBenchmarkStartNanos;
        timingBenchmarkPreviousDispatchNanos = 0L;
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

        if (!dispatchTimingBenchmarkRunning) {
            return;
        }

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
        if (!dispatchTimingBenchmarkRunning) {
            return;
        }

        if (dispatchTimingBenchmarkCancelRequested
                || timingBenchmarkAttempted >= timingBenchmarkRequested) {
            finishDispatchTimingBenchmark(
                    dispatchTimingBenchmarkCancelRequested
            );
            return;
        }

        timingBenchmarkTargetNanos = safeAdd(
                timingBenchmarkStartNanos,
                safeMultiply(
                        timingBenchmarkAttempted,
                        timingBenchmarkIntervalNanos
                )
        );

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

        if (delay == 0L) {
            pendingTimingBenchmarkStep.run();
        } else {
            handler.postDelayed(pendingTimingBenchmarkStep, delay);
        }
    }

    private void runDispatchTimingAttempt(long generation) {
        if (!dispatchTimingBenchmarkRunning
                || generation != timingBenchmarkGeneration
                || dispatchTimingBenchmarkCancelRequested) {
            return;
        }

        if (!serviceReady || gestureInProgress || pendingScheduledClick != null) {
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

            if (timingBenchmarkPreviousDispatchNanos > 0L) {
                double interval = (actual
                        - timingBenchmarkPreviousDispatchNanos)
                        / (double) NANOS_PER_MS;

                double error = interval
                        - timingBenchmarkIntervalNanos
                        / (double) NANOS_PER_MS;

                timingActualIntervalsMs.add(interval);
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
        if (!activeTimingBenchmarkAttempt) {
            return;
        }

        activeTimingBenchmarkAttempt = false;

        if (timingDispatchAccepted) {
            timingBenchmarkCallbacks++;

            if (success) {
                timingBenchmarkSuccessful++;
            } else {
                timingBenchmarkCancelled++;
            }
        } else {
            timingBenchmarkRejected++;
        }

        timingDispatchAccepted = false;
        scheduleDispatchTimingAttempt(timingBenchmarkGeneration);
    }

    private void finishDispatchTimingBenchmark(boolean cancelled) {
        if (!dispatchTimingBenchmarkRunning) {
            return;
        }

        dispatchTimingBenchmarkRunning = false;
        dispatchTimingBenchmarkCancelRequested = false;
        activeTimingBenchmarkAttempt = false;

        if (pendingTimingBenchmarkStep != null) {
            handler.removeCallbacks(pendingTimingBenchmarkStep);
            pendingTimingBenchmarkStep = null;
        }

        double[] actual = summarizeDoubles(timingActualIntervalsMs);
        double[] absoluteErrors = summarizeDoubles(
                timingAbsoluteIntervalErrorsMs
        );
        double[] lateness = summarizeDoubles(timingDispatchLatenessMs);

        double averageError = 0.0;

        if (!timingIntervalErrorsMs.isEmpty()) {
            double sum = 0.0;

            for (double value : timingIntervalErrorsMs) {
                sum += value;
            }

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
                        timingBenchmarkIntervalNanos
                                / (double) NANOS_PER_MS,
                        actual[0],
                        actual[1],
                        actual[2],
                        actual[3],
                        actual[4],
                        averageError,
                        absoluteErrors[3],
                        lateness[1],
                        lateness[3],
                        cancelled,
                        cancelled ? timingBenchmarkCancellationReason : "",
                        timingBenchmarkInterruptions
                );

        lastDispatchTimingBenchmarkResult = result;

        if (cancelled) {
            Log.w(TAG, "Dispatch timing benchmark cancelled: "
                    + result.cancellationReason
                    + " | interruptions=" + result.interruptions
                    + " | attempted=" + result.attemptedAttempts
                    + " | callbacks=" + result.callbacks);
        }

        DispatchTimingBenchmarkListener listener = timingBenchmarkListener;
        timingBenchmarkListener = null;

        if (listener != null) {
            try {
                listener.onBenchmarkFinished(result);
            } catch (Exception e) {
                Log.e(TAG, "Dispatch timing listener failed", e);
            }
        }
    }

    private static double[] summarizeDoubles(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return new double[]{0, 0, 0, 0, 0};
        }

        ArrayList<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        double sum = 0.0;
        for (double value : sorted) {
            sum += value;
        }

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

    // ---------------------------------------------------------
    // Click interval and batch helpers
    // ---------------------------------------------------------

    public long getEffectiveClickIntervalMs(long requestedIntervalMs) {
        if (requestedIntervalMs < 0L) {
            return -1L;
        }

        return Math.max(MIN_SEQUENTIAL_INTERVAL_MS, requestedIntervalMs);
    }

    public int getMaxBatchClickCount(long intervalMs) {
        long effective = getEffectiveClickIntervalMs(intervalMs);

        if (effective <= 0L) {
            return 0;
        }

        int maxStrokes = GestureDescription.getMaxStrokeCount();
        long maxDuration = GestureDescription.getMaxGestureDuration();

        if (maxStrokes <= 0 || maxDuration < TAP_DURATION_MS) {
            return 0;
        }

        long byDuration =
                ((maxDuration - TAP_DURATION_MS) / effective) + 1L;

        return (int) Math.max(
                1L,
                Math.min((long) maxStrokes, byDuration)
        );
    }

    public void performClick(Runnable completion) {
        performClickBatch(1, 0L, completion);
    }

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
            handler.post(() -> performClickBatch(
                    requestedCount,
                    requestedIntervalMs,
                    deadlineElapsedRealtimeMs,
                    completion
            ));
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

        if ((callbackBenchmarkRunning && !activeBenchmarkAttempt)
                || (dispatchTimingBenchmarkRunning
                && !activeTimingBenchmarkAttempt)) {
            lastError = "آزمون Benchmark در حال اجراست";
            completeSafely(completion);
            return 0;
        }

        if (gestureInProgress || pendingScheduledClick != null) {
            lastError = "کلیک قبلی هنوز در حال اجرا یا زمان‌بندی است";
            lastBatchRejectedClickCount = Math.max(0, requestedCount);
            completeSafely(completion);
            return 0;
        }

        if (requestedCount <= 0 || requestedIntervalMs < 0L) {
            lastError = "تعداد یا فاصله کلیک معتبر نیست";
            completeSafely(completion);
            return 0;
        }

        long effectiveInterval =
                getEffectiveClickIntervalMs(requestedIntervalMs);

        if (effectiveInterval <= 0L
                || effectiveInterval > Long.MAX_VALUE / NANOS_PER_MS) {
            lastError = "فاصله مؤثر کلیک معتبر نیست";
            completeSafely(completion);
            return 0;
        }

        long intervalNanos = effectiveInterval * NANOS_PER_MS;
        long now = SystemClock.elapsedRealtimeNanos();

        if (nextAllowedClickNanos > 0L && now < nextAllowedClickNanos) {
            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = requestedCount;
            lastError = "کلیک به علت رعایت فاصله زمانی ارسال نشد";
            completeSafely(completion);
            return 0;
        }

        int maxBatch = getMaxBatchClickCount(requestedIntervalMs);

        if (maxBatch <= 0) {
            lastError = "ظرفیت اجرای Gesture معتبر نیست";
            completeSafely(completion);
            return 0;
        }

        int deadlineAllowed = requestedCount;

        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE) {
            long deadline = elapsedMsToNanos(deadlineElapsedRealtimeMs);
            long remaining = deadline - now;
            long tapNanos = TAP_DURATION_MS * NANOS_PER_MS;

            if (remaining < tapNanos) {
                deadlineAllowed = 0;
            } else {
                long count = ((remaining - tapNanos) / intervalNanos) + 1L;
                deadlineAllowed = (int) Math.min(
                        (long) requestedCount,
                        Math.max(0L, count)
                );
            }
        }

        if (deadlineAllowed < requestedCount) {
            lastBatchSkippedByDeadline = requestedCount - deadlineAllowed;
        }

        int batchCount = Math.min(
                Math.min(requestedCount, maxBatch),
                deadlineAllowed
        );

        if (batchCount <= 0) {
            lastError = "زمان کافی تا پایان چرخه برای ارسال کلیک وجود ندارد";
            completeSafely(completion);
            return 0;
        }

        int clickX = targetX;
        int clickY = targetY;

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        try {
            for (int i = 0; i < batchCount; i++) {
                long offset = safeMultiply(i, effectiveInterval);

                Path path = new Path();
                path.moveTo(clickX, clickY);

                builder.addStroke(new GestureDescription.StrokeDescription(
                        path,
                        offset,
                        TAP_DURATION_MS
                ));
            }
        } catch (Exception e) {
            lastError = "خطا در ساخت Gesture: " + e.getMessage();
            lastBatchRejectedClickCount = batchCount;
            Log.e(TAG, lastError, e);
            completeSafely(completion);
            return 0;
        }

        final GestureDescription gesture;

        try {
            gesture = builder.build();
        } catch (Exception e) {
            lastError = "خطا در آماده‌سازی Gesture: " + e.getMessage();
            lastBatchRejectedClickCount = batchCount;
            Log.e(TAG, lastError, e);
            completeSafely(completion);
            return 0;
        }

        long checkNanos = SystemClock.elapsedRealtimeNanos();

        if (!fitsDeadline(
                checkNanos,
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

        long gestureId = ++activeGestureId;

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

        Log.d(TAG, "Dispatch batch"
                + " | count=" + batchCount
                + " | interval=" + effectiveInterval
                + " | x=" + clickX
                + " | y=" + clickY);

        boolean accepted = false;

        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription description) {
                            super.onCompleted(description);
                            finishGesture(
                                    gestureId,
                                    true,
                                    "دسته کلیک با موفقیت تکمیل شد"
                            );
                        }

                        @Override
                        public void onCancelled(GestureDescription description) {
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

        if (activeTimingBenchmarkAttempt) {
            timingDispatchAccepted = accepted;
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
    // Scheduled single click
    // ---------------------------------------------------------

    public boolean performClickAtTarget(
            long targetNanos,
            long cycleStartNanos,
            long cycleEndNanos,
            Runnable completion
    ) {
        return performClickAtTarget(
                targetNanos,
                cycleStartNanos,
                cycleEndNanos,
                null,
                completion
        );
    }

    public boolean performClickAtTarget(
            long targetNanos,
            long cycleStartNanos,
            long cycleEndNanos,
            DispatchListener dispatchListener,
            Runnable completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> performClickAtTarget(
                    targetNanos,
                    cycleStartNanos,
                    cycleEndNanos,
                    dispatchListener,
                    completion
            ));
            return true;
        }

        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedForInterval = false;
        lastBatchRejectedClickCount = 0;

        if (!serviceReady) {
            lastError = "سرویس دسترسی‌پذیری آماده نیست";
            return false;
        }

        if (callbackBenchmarkRunning || dispatchTimingBenchmarkRunning) {
            lastError = "آزمون Benchmark در حال اجراست";
            return false;
        }

        if (gestureInProgress || pendingScheduledClick != null) {
            lastError = "کلیک قبلی هنوز در حال اجرا یا زمان‌بندی است";
            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = 1;
            return false;
        }

        if (targetNanos < cycleStartNanos
                || cycleEndNanos <= cycleStartNanos
                || targetNanos >= cycleEndNanos) {
            lastError = "زمان هدف کلیک خارج از محدوده چرخه است";
            lastBatchSkippedByDeadline = 1;
            return false;
        }

        long now = SystemClock.elapsedRealtimeNanos();

        // اگر از همان ابتدا فرصت اجرای کامل لمس وجود ندارد،
        // درخواست به‌صورت synchronous رد می‌شود.
        if (now >= cycleEndNanos
                || safeAdd(now, TAP_DURATION_MS * NANOS_PER_MS)
                > cycleEndNanos) {
            lastBatchSkippedByDeadline = 1;
            lastError = "مهلت چرخه پیش از زمان ارسال کلیک تمام شده است";
            return false;
        }

        final long generation = ++scheduledClickGeneration;

        final boolean immediate = now >= targetNanos;
        final boolean[] immediateAccepted = {true};

        pendingScheduledClick = new Runnable() {
            @Override
            public void run() {
                if (generation != scheduledClickGeneration) {
                    return;
                }

                long current = SystemClock.elapsedRealtimeNanos();

                if (current < cycleStartNanos) {
                    handler.postDelayed(
                            this,
                            remainingDelayMs(cycleStartNanos, current)
                    );
                    return;
                }

                if (current < targetNanos) {
                    handler.postDelayed(
                            this,
                            remainingDelayMs(targetNanos, current)
                    );
                    return;
                }

                pendingScheduledClick = null;

                boolean accepted = dispatchScheduledSingleClick(
                        targetNanos,
                        cycleEndNanos,
                        dispatchListener,
                        completion,
                        immediate
                );

                if (immediate) {
                    immediateAccepted[0] = accepted;
                }
            }
        };

        long delay = remainingDelayMs(targetNanos, now);

        if (delay == 0L) {
            pendingScheduledClick.run();
            return immediateAccepted[0];
        }

        handler.postDelayed(pendingScheduledClick, delay);
        return true;
    }

    private boolean dispatchScheduledSingleClick(
            long targetNanos,
            long cycleEndNanos,
            DispatchListener dispatchListener,
            Runnable completion,
            boolean synchronous
    ) {
        if (!serviceReady || gestureInProgress) {
            lastError = "سرویس برای ارسال کلیک آماده نیست";
            if (!synchronous) {
                completeSafely(completion);
            }
            return false;
        }

        int clickX = targetX;
        int clickY = targetY;

        Path path = new Path();
        path.moveTo(clickX, clickY);

        final GestureDescription gesture;

        try {
            GestureDescription.Builder builder =
                    new GestureDescription.Builder();

            builder.addStroke(new GestureDescription.StrokeDescription(
                    path,
                    0L,
                    TAP_DURATION_MS
            ));

            gesture = builder.build();

        } catch (Exception e) {
            lastError = "خطا در ساخت تک‌کلیک: " + e.getMessage();
            failedClicks++;
            Log.e(TAG, lastError, e);

            if (!synchronous) {
                completeSafely(completion);
            }
            return false;
        }

        // کنترل نهایی Deadline درست پیش از dispatchGesture.
        // زمان ثبت‌شده، زمان واقعی Dispatch است.
        final long actualDispatchNanos =
                SystemClock.elapsedRealtimeNanos();

        final long tapEndNanos = safeAdd(
                actualDispatchNanos,
                TAP_DURATION_MS * NANOS_PER_MS
        );

        if (actualDispatchNanos >= cycleEndNanos
                || tapEndNanos > cycleEndNanos) {
            lastBatchSkippedByDeadline = 1;
            lastError = "مهلت چرخه پیش از ارسال کلیک تمام شده است";

            if (!synchronous) {
                completeSafely(completion);
            }
            return false;
        }

        gestureInProgress = true;
        activeCompletion = completion;
        activeBatchClickCount = 1;
        activeBatchIntervalMs = 0L;

        long gestureId = ++activeGestureId;
        dispatchStartNanos = actualDispatchNanos;

        Log.d(TAG, "Dispatch scheduled single click"
                + " | targetNanos=" + targetNanos
                + " | actualDispatchNanos=" + actualDispatchNanos
                + " | latenessNanos="
                + Math.max(0L, actualDispatchNanos - targetNanos)
                + " | deadlineNanos=" + cycleEndNanos
                + " | x=" + clickX
                + " | y=" + clickY);

        boolean accepted = false;

        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription description) {
                            super.onCompleted(description);
                            finishGesture(
                                    gestureId,
                                    true,
                                    "تک‌کلیک زمان‌بندی‌شده تکمیل شد"
                            );
                        }

                        @Override
                        public void onCancelled(GestureDescription description) {
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
            clearActiveGestureState();
            failedClicks++;
            lastError = "اندروید درخواست تک‌کلیک را نپذیرفت";

            if (!synchronous) {
                completeSafely(completion);
            }
            return false;
        }

        nextAllowedClickNanos = safeAdd(
                actualDispatchNanos,
                TAP_DURATION_MS * NANOS_PER_MS
        );

        // فقط پس از پذیرفته شدن واقعی dispatch فراخوانی می‌شود.
        if (dispatchListener != null) {
            try {
                dispatchListener.onDispatched(actualDispatchNanos);
            } catch (Exception e) {
                Log.e(TAG, "Dispatch listener failed", e);
            }
        }

        return true;
    }

    public void cancelPendingScheduledClick() {
        scheduledClickGeneration++;

        if (pendingScheduledClick != null) {
            handler.removeCallbacks(pendingScheduledClick);
            pendingScheduledClick = null;
        }
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
            handler.post(() -> finishGesture(gestureId, success, message));
            return;
        }

        if (!gestureInProgress || gestureId != activeGestureId) {
            return;
        }

        long latency = Math.max(
                0L,
                SystemClock.elapsedRealtimeNanos() - dispatchStartNanos
        );

        boolean benchmarkGesture =
                activeBenchmarkAttempt || activeTimingBenchmarkAttempt;

        if (!benchmarkGesture) {
            lastGestureLatencyNanos = latency;
            measuredGestures++;

            if (totalGestureLatencyNanos <= Long.MAX_VALUE - latency) {
                totalGestureLatencyNanos += latency;
            }

            maxGestureLatencyNanos = Math.max(
                    maxGestureLatencyNanos,
                    latency
            );
        }

        int completedCount = activeBatchClickCount;

        if (activeBenchmarkAttempt) {
            clearActiveGestureState();

            Runnable callback = activeCompletion;
            activeCompletion = null;

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
            clearActiveGestureState();

            Runnable callback = activeCompletion;
            activeCompletion = null;

            onDispatchTimingGestureFinished(success);
            completeSafely(callback);
            return;
        }

        if (success) {
            successfulClicks += completedCount;
            lastError = message;

            Log.d(TAG, message
                    + " | batchClicks=" + completedCount
                    + " | interval=" + activeBatchIntervalMs
                    + " | latencyNanos=" + latency);
        } else {
            failedClicks += completedCount;
            lastError = message;

            Log.e(TAG, message
                    + " | batchClicks=" + completedCount
                    + " | latencyNanos=" + latency);
        }

        Runnable callback = activeCompletion;
        clearActiveGestureState();

        completeSafely(callback);
    }

    private void clearActiveGestureState() {
        gestureInProgress = false;
        activeCompletion = null;
        activeBatchClickCount = 0;
        activeBatchIntervalMs = 0L;
    }

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

    // ---------------------------------------------------------
    // Time and overflow-safe helpers
    // ---------------------------------------------------------

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

    private static long safeAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }

        if (second < 0L && first < Long.MIN_VALUE - second) {
            return Long.MIN_VALUE;
        }

        return first + second;
    }

    private static long safeMultiply(long first, long second) {
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

        long rounded = safeAdd(
                difference,
                NANOS_PER_MS - 1L
        );

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

        long deadline = elapsedMsToNanos(deadlineElapsedRealtimeMs);

        long lastOffset = safeMultiply(
                count - 1L,
                intervalNanos
        );

        long lastEnd = safeAdd(
                safeAdd(dispatchNanos, lastOffset),
                TAP_DURATION_MS * NANOS_PER_MS
        );

        return lastEnd <= deadline;
    }
}