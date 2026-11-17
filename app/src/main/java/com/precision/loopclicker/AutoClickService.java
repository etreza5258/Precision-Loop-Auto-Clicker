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
    private static final String PREF_NAME = "auto_click_target";
    private static final String KEY_TARGET_X = "target_x";
    private static final String KEY_TARGET_Y = "target_y";

    public interface DispatchListener {
        void onDispatched(long dispatchElapsedRealtimeNanos);
    }

    private static final long TAP_DURATION_MS = 1L;
    private static final long MIN_SEQUENTIAL_INTERVAL_MS = 1L;
    private static final long NANOS_PER_MS = 1_000_000L;

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
    private long nextAllowedClickNanos = 0;

    private Runnable pendingScheduledClick;
    private long scheduledClickGeneration = 0;

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
                int requestedAttempts,
                int attemptedAttempts,
                int callbacks,
                int successfulCallbacks,
                int cancelledCallbacks,
                int rejectedAttempts,
                long minCallbackMs,
                double averageCallbackMs,
                long medianCallbackMs,
                long p95CallbackMs,
                long maxCallbackMs,
                List<Long> samplesMs,
                boolean cancelled
        ) {
            this.requestedAttempts = requestedAttempts;
            this.attemptedAttempts = attemptedAttempts;
            this.callbacks = callbacks;
            this.successfulCallbacks = successfulCallbacks;
            this.cancelledCallbacks = cancelledCallbacks;
            this.rejectedAttempts = rejectedAttempts;
            this.minCallbackMs = minCallbackMs;
            this.averageCallbackMs = averageCallbackMs;
            this.medianCallbackMs = medianCallbackMs;
            this.p95CallbackMs = p95CallbackMs;
            this.maxCallbackMs = maxCallbackMs;
            this.samplesMs = Collections.unmodifiableList(
                    new ArrayList<>(samplesMs)
            );
            this.cancelled = cancelled;
        }
    }

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
                int requestedAttempts,
                int attemptedAttempts,
                int acceptedDispatches,
                int callbacks,
                int successfulCallbacks,
                int cancelledCallbacks,
                int rejectedAttempts,
                double requestedIntervalMs,
                double minActualIntervalMs,
                double averageActualIntervalMs,
                double medianActualIntervalMs,
                double p95ActualIntervalMs,
                double maxActualIntervalMs,
                double averageIntervalErrorMs,
                double p95AbsoluteIntervalErrorMs,
                double averageDispatchLatenessMs,
                double p95DispatchLatenessMs,
                boolean cancelled,
                String cancellationReason,
                int interruptions
        ) {
            this.requestedAttempts = requestedAttempts;
            this.attemptedAttempts = attemptedAttempts;
            this.acceptedDispatches = acceptedDispatches;
            this.callbacks = callbacks;
            this.successfulCallbacks = successfulCallbacks;
            this.cancelledCallbacks = cancelledCallbacks;
            this.rejectedAttempts = rejectedAttempts;
            this.requestedIntervalMs = requestedIntervalMs;
            this.minActualIntervalMs = minActualIntervalMs;
            this.averageActualIntervalMs = averageActualIntervalMs;
            this.medianActualIntervalMs = medianActualIntervalMs;
            this.p95ActualIntervalMs = p95ActualIntervalMs;
            this.maxActualIntervalMs = maxActualIntervalMs;
            this.averageIntervalErrorMs = averageIntervalErrorMs;
            this.p95AbsoluteIntervalErrorMs = p95AbsoluteIntervalErrorMs;
            this.averageDispatchLatenessMs = averageDispatchLatenessMs;
            this.p95DispatchLatenessMs = p95DispatchLatenessMs;
            this.cancelled = cancelled;
            this.cancellationReason = cancellationReason;
            this.interruptions = interruptions;
        }
    }

    private volatile boolean dispatchTimingBenchmarkRunning = false;
    private volatile boolean dispatchTimingBenchmarkCancelRequested = false;
    private boolean activeTimingBenchmarkAttempt = false;
    private boolean timingDispatchAccepted = false;

    private int timingBenchmarkRequested = 0;
    private int timingBenchmarkAttempted = 0;
    private int timingBenchmarkAccepted = 0;
    private int timingBenchmarkCallbacks = 0;
    private int timingBenchmarkSuccessful = 0;
    private int timingBenchmarkCancelled = 0;
    private int timingBenchmarkRejected = 0;
    private int timingBenchmarkInterruptions = 0;

    private String timingBenchmarkCancellationReason = "";
    private long timingBenchmarkIntervalNanos = 50L * NANOS_PER_MS;
    private long timingBenchmarkStartNanos = 0L;
    private long timingBenchmarkTargetNanos = 0L;
    private long timingBenchmarkPreviousDispatchNanos = 0L;
    private long timingBenchmarkGeneration = 0L;

    private Runnable pendingTimingBenchmarkStep;
    private DispatchTimingBenchmarkListener timingBenchmarkListener;

    private final ArrayList<Double> timingActualIntervalsMs =
            new ArrayList<>();
    private final ArrayList<Double> timingIntervalErrorsMs =
            new ArrayList<>();
    private final ArrayList<Double> timingAbsoluteIntervalErrorsMs =
            new ArrayList<>();
    private final ArrayList<Double> timingDispatchLatenessMs =
            new ArrayList<>();

    private volatile DispatchTimingBenchmarkResult
            lastDispatchTimingBenchmarkResult;

    private volatile boolean callbackBenchmarkRunning = false;
    private volatile boolean callbackBenchmarkCancelRequested = false;
    private boolean activeBenchmarkAttempt = false;
    private boolean benchmarkDispatchAccepted = false;

    private int benchmarkRequested = 0;
    private int benchmarkAttempted = 0;
    private int benchmarkCallbacks = 0;
    private int benchmarkSuccessful = 0;
    private int benchmarkCancelled = 0;
    private int benchmarkRejected = 0;

    private long benchmarkIntervalAfterCallbackMs = 50L;
    private long benchmarkAttemptStartNanos = 0L;
    private long benchmarkGeneration = 0L;

    private Runnable pendingBenchmarkStep;
    private CallbackBenchmarkListener benchmarkListener;
    private final ArrayList<Long> benchmarkSamplesMs = new ArrayList<>();

    private volatile CallbackBenchmarkResult lastCallbackBenchmarkResult;

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

        targetX = Math.max(0, getSharedPreferences(
                PREF_NAME, MODE_PRIVATE
        ).getInt(KEY_TARGET_X, 500));

        targetY = Math.max(0, getSharedPreferences(
                PREF_NAME, MODE_PRIVATE
        ).getInt(KEY_TARGET_Y, 500));

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
                Log.w(TAG,
                        "Accessibility onInterrupt during dispatch timing "
                                + "benchmark; waiting for the active gesture callback");
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

        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_TARGET_X, targetX)
                .putInt(KEY_TARGET_Y, targetY)
                .apply();
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

    public boolean isCallbackBenchmarkRunning() {
        return callbackBenchmarkRunning;
    }

    public CallbackBenchmarkResult getLastCallbackBenchmarkResult() {
        return lastCallbackBenchmarkResult;
    }

    public boolean isDispatchTimingBenchmarkRunning() {
        return dispatchTimingBenchmarkRunning;
    }

    public DispatchTimingBenchmarkResult
    getLastDispatchTimingBenchmarkResult() {
        return lastDispatchTimingBenchmarkResult;
    }

    public boolean startDispatchTimingBenchmark(
            int requestedAttempts,
            long requestedIntervalMs,
            DispatchTimingBenchmarkListener listener
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> startDispatchTimingBenchmark(
                    requestedAttempts, requestedIntervalMs, listener
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

        timingBenchmarkIntervalNanos =
                requestedIntervalMs * NANOS_PER_MS;

        timingBenchmarkStartNanos =
                SystemClock.elapsedRealtimeNanos()
                        + 300L * NANOS_PER_MS;

        timingBenchmarkTargetNanos = timingBenchmarkStartNanos;
        timingBenchmarkPreviousDispatchNanos = 0L;
        timingBenchmarkListener = listener;

        timingActualIntervalsMs.clear();
        timingIntervalErrorsMs.clear();
        timingAbsoluteIntervalErrorsMs.clear();
        timingDispatchLatenessMs.clear();

        lastDispatchTimingBenchmarkResult = null;

        final long generation = ++timingBenchmarkGeneration;
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
                    long delay = remainingDelayMs(
                            timingBenchmarkTargetNanos,
                            now
                    );
                    handler.postDelayed(this, delay);
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

            long actualDispatchNanos = dispatchStartNanos;

            double latenessMs =
                    (actualDispatchNanos - timingBenchmarkTargetNanos)
                            / (double) NANOS_PER_MS;

            timingDispatchLatenessMs.add(latenessMs);

            if (timingBenchmarkPreviousDispatchNanos > 0L) {
                double actualIntervalMs =
                        (actualDispatchNanos
                                - timingBenchmarkPreviousDispatchNanos)
                                / (double) NANOS_PER_MS;

                double intervalErrorMs =
                        actualIntervalMs
                                - (timingBenchmarkIntervalNanos
                                / (double) NANOS_PER_MS);

                timingActualIntervalsMs.add(actualIntervalMs);
                timingIntervalErrorsMs.add(intervalErrorMs);
                timingAbsoluteIntervalErrorsMs.add(
                        Math.abs(intervalErrorMs)
                );
            }

            timingBenchmarkPreviousDispatchNanos = actualDispatchNanos;

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
        double[] absoluteErrors =
                summarizeDoubles(timingAbsoluteIntervalErrorsMs);
        double[] lateness =
                summarizeDoubles(timingDispatchLatenessMs);

        double averageError = 0.0;

        if (!timingIntervalErrorsMs.isEmpty()) {
            double sum = 0.0;

            for (Double value : timingIntervalErrorsMs) {
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

        } else if (result.interruptions > 0) {
            Log.i(TAG,
                    "Dispatch timing benchmark completed with interruptions="
                            + result.interruptions);
        }

        DispatchTimingBenchmarkListener listener =
                timingBenchmarkListener;

        timingBenchmarkListener = null;

        if (listener != null) {
            try {
                listener.onBenchmarkFinished(result);
            } catch (Exception e) {
                Log.e(TAG,
                        "Dispatch timing benchmark listener failed", e);
            }
        }
    }

    private static double[] summarizeDoubles(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return new double[]{
                    0.0, 0.0, 0.0, 0.0, 0.0
            };
        }

        ArrayList<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        double sum = 0.0;

        for (Double value : sorted) {
            sum += value;
        }

        int size = sorted.size();

        double median = (size % 2 == 1)
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1)
                + sorted.get(size / 2)) / 2.0;

        int p95Index = Math.max(
                0,
                (int) Math.ceil(size * 0.95) - 1
        );

        return new double[]{
                sorted.get(0),
                sum / size,
                median,
                sorted.get(p95Index),
                sorted.get(size - 1)
        };
    }

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

        final long generation = ++benchmarkGeneration;

        pendingBenchmarkStep =
                () -> runCallbackBenchmarkAttempt(generation);

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

        if (!serviceReady
                || gestureInProgress
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
        benchmarkAttemptStartNanos =
                SystemClock.elapsedRealtimeNanos();

        activeBenchmarkAttempt = true;
        benchmarkDispatchAccepted = false;

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

        long elapsedMs = Math.max(
                0L,
                latencyNanos / NANOS_PER_MS
        );

        benchmarkSamplesMs.add(elapsedMs);

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

        pendingBenchmarkStep =
                () -> runCallbackBenchmarkAttempt(generation);

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

        ArrayList<Long> sorted =
                new ArrayList<>(benchmarkSamplesMs);

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

            for (Long sample : sorted) {
                sum += sample;
            }

            average = (double) sum / sorted.size();

            int size = sorted.size();

            median = (size % 2 == 1)
                    ? sorted.get(size / 2)
                    : (sorted.get(size / 2 - 1)
                    + sorted.get(size / 2)) / 2L;

            int p95Index = Math.max(
                    0,
                    (int) Math.ceil(size * 0.95) - 1
            );

            p95 = sorted.get(p95Index);
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
        return 0L;
    }

    public long getAdaptiveSamples() {
        return 0L;
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
        lastBatchRejectedForInterval = false;
        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedClickCount = 0;
        lastError = "آماده";
    }

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

    public void performClick(Runnable completion) {
        performClickBatch(1, 0, completion);
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
            lastError = "تست زمان‌بندی در حال اجراست";
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

        final long intervalNanos =
                effectiveInterval * NANOS_PER_MS;

        final long nowNanos =
                SystemClock.elapsedRealtimeNanos();

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

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            Runnable completion
    ) {
        return performClickAtTarget(
                targetElapsedRealtimeNanos,
                cycleStartElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                1L,
                null,
                completion
        );
    }

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            DispatchListener dispatchListener,
            Runnable completion
    ) {
        return performClickAtTarget(
                targetElapsedRealtimeNanos,
                cycleStartElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                1L,
                dispatchListener,
                completion
        );
    }

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            long touchDurationMs,
            DispatchListener dispatchListener,
            Runnable completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> performClickAtTarget(
                    targetElapsedRealtimeNanos,
                    cycleStartElapsedRealtimeNanos,
                    cycleDeadlineElapsedRealtimeNanos,
                    touchDurationMs,
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
            completeSafely(completion);
            return false;
        }

        if (callbackBenchmarkRunning || dispatchTimingBenchmarkRunning) {
            lastError = "تست زمان‌بندی در حال اجراست";
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

        if (touchDurationMs < 1L
                || touchDurationMs
                > GestureDescription.getMaxGestureDuration()) {
            lastError = "مدت لمس خارج از محدوده مجاز اندروید است";
            completeSafely(completion);
            return false;
        }

        if (targetElapsedRealtimeNanos < cycleStartElapsedRealtimeNanos
                || cycleDeadlineElapsedRealtimeNanos
                <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                >= cycleDeadlineElapsedRealtimeNanos) {
            lastError = "زمان هدف کلیک خارج از محدوده چرخه است";
            lastBatchSkippedByDeadline = 1;
            completeSafely(completion);
            return false;
        }

        final long touchDurationNanos =
                touchDurationMs * NANOS_PER_MS;

        final long generation = ++scheduledClickGeneration;
        final long desiredDispatchNanos = targetElapsedRealtimeNanos;

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

                long dispatchNow =
                        SystemClock.elapsedRealtimeNanos();

                long touchEnd = safeAdd(
                        dispatchNow,
                        touchDurationNanos
                );

                if (dispatchNow >= cycleDeadlineElapsedRealtimeNanos
                        || touchEnd > cycleDeadlineElapsedRealtimeNanos) {
                    pendingScheduledClick = null;
                    lastBatchSkippedByDeadline = 1;
                    lastError = "مهلت چرخه برای اجرای کلیک تمام شده است";
                    completeSafely(completion);
                    return;
                }

                pendingScheduledClick = null;

                dispatchScheduledSingleClick(
                        touchDurationMs,
                        dispatchListener,
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
            long touchDurationMs,
            DispatchListener dispatchListener,
            Runnable completion
    ) {
        if (!serviceReady || gestureInProgress) {
            lastError = "سرویس برای ارسال کلیک آماده نیست";
            completeSafely(completion);
            return;
        }

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
                            touchDurationMs
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

        final long gestureId = ++activeGestureId;

        boolean accepted = false;
        long actualDispatchNanos = 0L;

        try {
            actualDispatchNanos =
                    SystemClock.elapsedRealtimeNanos();

            dispatchStartNanos = actualDispatchNanos;

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
            return;
        }

        nextAllowedClickNanos = safeAdd(
                actualDispatchNanos,
                touchDurationMs * NANOS_PER_MS
        );

        if (dispatchListener != null) {
            try {
                dispatchListener.onDispatched(actualDispatchNanos);
            } catch (Exception e) {
                Log.e(TAG, "Dispatch listener failed", e);
            }
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

        int completedBatchCount = activeBatchClickCount;

        if (activeBenchmarkAttempt) {
            gestureInProgress = false;
            activeBatchClickCount = 0;

            Runnable benchmarkCompletion = activeCompletion;
            activeCompletion = null;

            if (benchmarkDispatchAccepted) {
                onBenchmarkGestureFinished(success, latency);
            } else {
                activeBenchmarkAttempt = false;
                benchmarkRejected++;
                scheduleNextBenchmarkAttempt(benchmarkGeneration);
            }

            completeSafely(benchmarkCompletion);
            return;
        }

        if (activeTimingBenchmarkAttempt) {
            gestureInProgress = false;
            activeBatchClickCount = 0;

            Runnable timingCompletion = activeCompletion;
            activeCompletion = null;

            onDispatchTimingGestureFinished(success);
            completeSafely(timingCompletion);
            return;
        }

        if (success) {
            successfulClicks += completedBatchCount;
            lastError = message;
        } else {
            failedClicks += completedBatchCount;
            lastError = message;

            Log.e(TAG,
                    message + " | batchClicks=" + completedBatchCount
                            + " | latencyNanos=" + latency);
        }

        gestureInProgress = false;
        activeBatchClickCount = 0;

        Runnable callback = activeCompletion;
        activeCompletion = null;

        completeSafely(callback);
    }

    private void clearActiveGestureState() {
        gestureInProgress = false;
        activeCompletion = null;
        activeBatchClickCount = 0;
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