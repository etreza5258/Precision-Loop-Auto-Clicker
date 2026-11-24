
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {

    private static final long NS_PER_MS = 1_000_000L;

    private static final String PREFS = "auto_click_target";
    private static final String KEY_X = "target_x";
    private static final String KEY_Y = "target_y";
    private static final String KEY_ADAPTIVE_COMPENSATION =
            "adaptive_dispatch_compensation_ns";

    private static final int DEFAULT_X = 350;
    private static final int DEFAULT_Y = 700;

    private static final long MAX_GESTURE_MS =
            GestureDescription.getMaxGestureDuration();

    private static final long DEFAULT_TAP_DURATION_MS = 1L;
    private static final long STROKE_OFFSET_MS = 2L;
    private static final long STROKE_OFFSET_NS =
            STROKE_OFFSET_MS * NS_PER_MS;

    private static final long FINAL_SPIN_NS = 2_000_000L;
    private static final long ARM_LOOKAHEAD_NS = 500_000L;

    private static final long MIN_COMPENSATION_NS = -1_500_000L;
    private static final long MAX_COMPENSATION_NS = 1_500_000L;
    private static final long MAX_COMPENSATION_STEP_NS = 250_000L;

    private static final int ADAPTIVE_WINDOW = 9;

    private static final String CSV_NAME =
            "click_benchmark_v14.csv";

    private static final int QUEUED = 0;
    private static final int DISPATCHING = 1;
    private static final int ACTIVE = 2;
    private static final int FINISHED = 3;

    private static final String REASON_SUCCESS = "SUCCESS";
    private static final String REASON_CANCELLED = "CANCELLED";
    private static final String REASON_SERVICE_STOPPED = "SERVICE_STOPPED";
    private static final String REASON_DEADLINE = "DEADLINE_EXPIRED";
    private static final String REASON_DISPATCH_REJECTED = "DISPATCH_REJECTED";
    private static final String REASON_GESTURE_CANCELLED = "GESTURE_CANCELLED";
    private static final String REASON_GESTURE_FAILED = "GESTURE_FAILED";

    private static volatile AutoClickService instance;

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private volatile HandlerThread callbackThread;
    private volatile Handler callbackHandler;

    private final Object queueLock = new Object();

    private final AtomicLong queueVersion = new AtomicLong();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicLong cancellationGeneration = new AtomicLong();

    private final AtomicLong adaptiveCompensationNs =
            new AtomicLong(0L);

    private final AtomicLong adaptiveDeviationNs =
            new AtomicLong(0L);

    private final PriorityBlockingQueue<ClickRequest> pending =
            new PriorityBlockingQueue<>(
                    16,
                    Comparator.comparingLong(
                            (ClickRequest r) -> r.targetNanos
                    ).thenComparingLong(r -> r.requestId)
            );

    private final PriorityBlockingQueue<ClickRequest> ready =
            new PriorityBlockingQueue<>(
                    16,
                    Comparator.comparingLong(
                            (ClickRequest r) -> r.dispatchTargetNanos
                    ).thenComparingLong(r -> r.requestId)
            );

    private final java.util.Set<ClickRequest> activeRequests =
            ConcurrentHashMap.newKeySet();

    private final long[] adaptiveSamples =
            new long[ADAPTIVE_WINDOW];

    private final long[] adaptiveSortBuffer =
            new long[ADAPTIVE_WINDOW];

    private final long[] deviationSortBuffer =
            new long[ADAPTIVE_WINDOW];

    private int adaptiveSampleCount;
    private int adaptiveSampleCursor;

    private volatile boolean serviceReady;
    private volatile boolean schedulerStopping;
    private volatile Thread schedulerThread;

    private volatile int targetX = DEFAULT_X;
    private volatile int targetY = DEFAULT_Y;

    private volatile SharedPreferences targetPreferences;
    private volatile String lastRequestError = "";

    private volatile long benchmarkSessionId;
    private volatile ExecutorService benchmarkWriter;
    private volatile File benchmarkFile;

    private final class TimingGroup {

        final long firstTargetNanos;
        final long cancelGeneration;

        final List<ClickRequest> requests = new ArrayList<>();

        volatile boolean armed;

        TimingGroup(long firstTarget, long generation) {
            firstTargetNanos = firstTarget;
            cancelGeneration = generation;
        }
    }

    private final class ClickRequest extends GestureResultCallback {

        final long requestId = requestIds.incrementAndGet();

        final long cycleStartNanos;
        final long targetNanos;
        final long deadlineNanos;
        final long touchDurationNanos;

        final long enqueueNanos =
                SystemClock.elapsedRealtimeNanos();

        final long cancelGeneration;

        final int clickIndex;
        final int batchSize;

        final GestureDescription gesture;
        final TimingGroup timingGroup;

        final AtomicInteger state = new AtomicInteger(QUEUED);
        final AtomicBoolean completionDelivered = new AtomicBoolean();

        final Object callbackLock = new Object();

        volatile long dispatchTargetNanos;
        volatile long dispatchLeadNanos;
        volatile long compensationNanos;
        volatile long deviationNanos;

        volatile long schedulerReadyNanos;
        volatile long dispatchNanos;
        volatile long dispatchReturnNanos;
        volatile long callbackNanos;

        volatile boolean dispatchAccepted;
        volatile boolean dispatchCallReturned;

        volatile boolean callbackPending;
        volatile boolean callbackSuccess;
        volatile boolean callbackExpired;

        volatile String terminalReason = "";

        volatile GestureCompletion completion;

        ClickRequest(
                long cycleStart,
                long target,
                long deadline,
                long touchDuration,
                GestureDescription gesture,
                GestureCompletion completion,
                int clickIndex,
                int batchSize,
                long generation,
                TimingGroup group) {

            cycleStartNanos = cycleStart;
            targetNanos = target;
            deadlineNanos = deadline;
            touchDurationNanos = touchDuration;
            this.gesture = gesture;
            this.completion = completion;
            this.clickIndex = clickIndex;
            this.batchSize = batchSize;
            cancelGeneration = generation;
            timingGroup = group;
        }

        @Override
        public void onCompleted(GestureDescription gesture) {
            handleGestureCallback(this, true, false);
        }

        @Override
        public void onCancelled(GestureDescription gesture) {
            handleGestureCallback(this, false, false);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        startBenchmarkWriter();
        startCallbackThread();

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        targetPreferences = prefs;

        targetX = prefs.getInt(KEY_X, DEFAULT_X);
        targetY = prefs.getInt(KEY_Y, DEFAULT_Y);

        adaptiveCompensationNs.set(clamp(
                prefs.getLong(KEY_ADAPTIVE_COMPENSATION, 0L),
                MIN_COMPENSATION_NS,
                MAX_COMPENSATION_NS
        ));

        adaptiveDeviationNs.set(0L);
        adaptiveSampleCount = 0;
        adaptiveSampleCursor = 0;

        schedulerStopping = false;
        serviceReady = true;
        instance = this;

        startScheduler();
    }

    private void startCallbackThread() {
        HandlerThread current = callbackThread;

        if (current != null && current.isAlive()) {
            return;
        }

        HandlerThread thread = new HandlerThread(
                "PrecisionGestureCallback",
                Process.THREAD_PRIORITY_DISPLAY
        );

        thread.start();

        callbackThread = thread;
        callbackHandler = new Handler(thread.getLooper());
    }

    private void stopCallbackThread() {
        HandlerThread thread = callbackThread;

        callbackThread = null;
        callbackHandler = null;

        if (thread != null) {
            thread.quitSafely();
        }
    }

    private void startScheduler() {
        Thread current = schedulerThread;

        if (current != null && current.isAlive()) {
            return;
        }

        Thread worker = new Thread(
                this::schedulerLoop,
                "PrecisionClickNanoScheduler"
        );

        schedulerThread = worker;
        worker.start();
    }

    private void schedulerLoop() {
        try {
            Process.setThreadPriority(
                    Process.THREAD_PRIORITY_URGENT_DISPLAY
            );
        } catch (Exception ignored) {
        }

        while (!schedulerStopping) {
            long observedVersion = queueVersion.get();

            ClickRequest dispatchRequest = null;
            long schedulerReadyNanos = 0L;
            long waitUntilNanos = Long.MAX_VALUE;

            List<ClickRequest> rejected = null;

            synchronized (queueLock) {
                if (schedulerStopping) {
                    break;
                }

                long now = SystemClock.elapsedRealtimeNanos();

                ClickRequest readyHead = ready.peek();
                ClickRequest pendingHead = pending.peek();

                if (pendingHead != null) {
                    long earliestPossibleDispatch =
                            pendingHead.timingGroup.firstTargetNanos
                                    - STROKE_OFFSET_NS
                                    - MAX_COMPENSATION_NS;

                    boolean mustArm;

                    if (readyHead == null) {
                        long armAt =
                                earliestPossibleDispatch
                                        - ARM_LOOKAHEAD_NS;

                        mustArm = now >= armAt;

                        if (!mustArm) {
                            waitUntilNanos = armAt;
                        }
                    } else {
                        mustArm =
                                earliestPossibleDispatch
                                        <= readyHead.dispatchTargetNanos;
                    }

                    if (mustArm) {
                        rejected = armTimingGroupLocked(
                                pendingHead.timingGroup
                        );
                    }
                }

                readyHead = ready.peek();

                if (readyHead != null) {
                    long remaining =
                            readyHead.dispatchTargetNanos - now;

                    if (remaining <= 0L) {
                        dispatchRequest = readyHead;
                        schedulerReadyNanos =
                                SystemClock.elapsedRealtimeNanos();
                    } else {
                        waitUntilNanos = Math.min(
                                waitUntilNanos,
                                readyHead.dispatchTargetNanos
                        );
                    }
                } else if (pending.isEmpty()) {
                    waitUntilNanos = Long.MAX_VALUE;
                } else {
                    ClickRequest head = pending.peek();

                    long armAt =
                            head.timingGroup.firstTargetNanos
                                    - STROKE_OFFSET_NS
                                    - MAX_COMPENSATION_NS
                                    - ARM_LOOKAHEAD_NS;

                    waitUntilNanos = Math.min(waitUntilNanos, armAt);
                }
            }

            if (rejected != null) {
                String reason = !serviceReady || schedulerStopping
                        ? REASON_SERVICE_STOPPED
                        : REASON_CANCELLED;

                for (ClickRequest request : rejected) {
                    finishQueuedOrDispatching(
                            request, false, true, reason
                    );
                }
            }

            if (dispatchRequest != null) {
                dispatchReadyRequest(
                        dispatchRequest,
                        schedulerReadyNanos
                );
                continue;
            }

            if (schedulerStopping) {
                break;
            }

            if (queueVersion.get() != observedVersion) {
                continue;
            }

            if (waitUntilNanos == Long.MAX_VALUE) {
                LockSupport.park(this);
                continue;
            }

            long remaining =
                    waitUntilNanos
                            - SystemClock.elapsedRealtimeNanos();

            if (remaining > FINAL_SPIN_NS) {
                LockSupport.parkNanos(
                        this,
                        remaining - FINAL_SPIN_NS
                );
                continue;
            }

            if (remaining > 0L) {
                while (!schedulerStopping
                        && queueVersion.get() == observedVersion
                        && SystemClock.elapsedRealtimeNanos()
                        < waitUntilNanos) {
                }
            }
        }
    }

    private List<ClickRequest> armTimingGroupLocked(
            TimingGroup group) {

        if (group == null || group.armed) {
            return null;
        }

        if (!serviceReady
                || schedulerStopping
                || group.cancelGeneration
                != cancellationGeneration.get()) {

            List<ClickRequest> rejected =
                    new ArrayList<>(group.requests);

            for (ClickRequest request : rejected) {
                pending.remove(request);
            }

            group.armed = true;
            queueVersion.incrementAndGet();

            return rejected;
        }

        if (group.requests.isEmpty()) {
            group.armed = true;
            return null;
        }

        long compensation = clamp(
                adaptiveCompensationNs.get(),
                MIN_COMPENSATION_NS,
                MAX_COMPENSATION_NS
        );

        long deviation = adaptiveDeviationNs.get();

        for (ClickRequest request : group.requests) {
            pending.remove(request);

            request.compensationNanos = compensation;
            request.deviationNanos = deviation;

            request.dispatchLeadNanos =
                    STROKE_OFFSET_NS + compensation;

            request.dispatchTargetNanos =
                    request.targetNanos
                            - request.dispatchLeadNanos;

            ready.offer(request);
        }

        group.armed = true;
        queueVersion.incrementAndGet();

        return null;
    }

    private void dispatchReadyRequest(
            ClickRequest expected,
            long readyNanos) {

        long dispatchNanos = 0L;

        boolean accepted = false;
        boolean shouldFinish = false;
        boolean shouldFinishQueued = false;
        boolean expired = false;

        String finishReason = REASON_DISPATCH_REJECTED;

        synchronized (queueLock) {
            ClickRequest request = ready.poll();

            if (request == null || request != expected) {
                if (request != null) {
                    ready.offer(request);
                }

                queueVersion.incrementAndGet();
                return;
            }

            if (!request.state.compareAndSet(
                    QUEUED,
                    DISPATCHING
            )) {
                return;
            }

            if (!serviceReady
                    || schedulerStopping
                    || request.cancelGeneration
                    != cancellationGeneration.get()) {

                shouldFinishQueued = true;
                expired = true;

                finishReason = !serviceReady || schedulerStopping
                        ? REASON_SERVICE_STOPPED
                        : REASON_CANCELLED;

            } else {
                activeRequests.add(request);

                if (!request.state.compareAndSet(
                        DISPATCHING,
                        ACTIVE
                )) {
                    activeRequests.remove(request);
                    return;
                }

                Handler handler = callbackHandler;

                if (handler == null
                        || !serviceReady
                        || schedulerStopping
                        || request.cancelGeneration
                        != cancellationGeneration.get()) {

                    shouldFinish = true;
                    expired = true;

                    finishReason = !serviceReady || schedulerStopping
                            ? REASON_SERVICE_STOPPED
                            : REASON_CANCELLED;

                } else {
                    long now = SystemClock.elapsedRealtimeNanos();

                    long minimumTimeNeeded =
                            STROKE_OFFSET_NS
                                    + request.touchDurationNanos;

                    if (request.deadlineNanos - now
                            < minimumTimeNeeded) {

                        shouldFinish = true;
                        expired = true;
                        finishReason = REASON_DEADLINE;

                    } else {
                        request.schedulerReadyNanos = readyNanos;

                        dispatchNanos =
                                SystemClock.elapsedRealtimeNanos();

                        request.dispatchNanos = dispatchNanos;

                        try {
                            accepted = dispatchGesture(
                                    request.gesture,
                                    request,
                                    handler
                            );
                        } catch (Exception ignored) {
                            accepted = false;
                        }

                        synchronized (request.callbackLock) {
                            request.dispatchReturnNanos =
                                    SystemClock.elapsedRealtimeNanos();

                            request.dispatchAccepted = accepted;
                            request.dispatchCallReturned = true;
                        }
                    }
                }
            }
        }

        if (shouldFinishQueued) {
            finishQueuedOrDispatching(
                    expected,
                    false,
                    expired,
                    finishReason
            );
            return;
        }

        if (shouldFinish) {
            finishActive(
                    expected,
                    false,
                    expired,
                    finishReason
            );
            return;
        }

        if (dispatchNanos != 0L && accepted) {
            updateAdaptiveModel(
                    dispatchNanos,
                    expected.targetNanos,
                    expected.compensationNanos
            );
        }

        boolean pendingCallback;
        boolean pendingSuccess;
        boolean pendingExpired;

        synchronized (expected.callbackLock) {
            pendingCallback = expected.callbackPending;
            pendingSuccess = expected.callbackSuccess;
            pendingExpired = expected.callbackExpired;

            expected.callbackPending = false;
        }

        if (pendingCallback) {
            finishActive(
                    expected,
                    pendingSuccess,
                    pendingExpired,
                    pendingSuccess
                            ? REASON_SUCCESS
                            : REASON_GESTURE_CANCELLED
            );
        } else if (!accepted) {
            finishActive(
                    expected,
                    false,
                    false,
                    REASON_DISPATCH_REJECTED
            );
        }
    }

    private void handleGestureCallback(
            ClickRequest request,
            boolean success,
            boolean expired) {

        synchronized (request.callbackLock) {
            request.callbackNanos =
                    SystemClock.elapsedRealtimeNanos();

            if (!request.dispatchCallReturned) {
                request.callbackPending = true;
                request.callbackSuccess = success;
                request.callbackExpired = expired;
                return;
            }
        }

        finishActive(
                request,
                success,
                expired,
                success
                        ? REASON_SUCCESS
                        : REASON_GESTURE_CANCELLED
        );
    }

    private void updateAdaptiveModel(
            long dispatchNanos,
            long targetNanos,
            long appliedCompensationNanos) {

        /*
         * Estimated dispatch latency:
         * measured stroke error + compensation already applied.
         *
         * This avoids repeatedly adding the same group's
         * compensation error to the current compensation.
         */
        long latencyEstimate = clamp(
                dispatchNanos
                        + STROKE_OFFSET_NS
                        - targetNanos
                        + appliedCompensationNanos,
                MIN_COMPENSATION_NS,
                MAX_COMPENSATION_NS
        );

        adaptiveSamples[adaptiveSampleCursor] = latencyEstimate;

        adaptiveSampleCursor =
                (adaptiveSampleCursor + 1) % ADAPTIVE_WINDOW;

        if (adaptiveSampleCount < ADAPTIVE_WINDOW) {
            adaptiveSampleCount++;
        }

        for (int i = 0; i < adaptiveSampleCount; i++) {
            adaptiveSortBuffer[i] = adaptiveSamples[i];
        }

        sortPrefix(adaptiveSortBuffer, adaptiveSampleCount);

        long median = medianOfSorted(
                adaptiveSortBuffer,
                adaptiveSampleCount
        );

        for (int i = 0; i < adaptiveSampleCount; i++) {
            deviationSortBuffer[i] = Math.abs(
                    adaptiveSamples[i] - median
            );
        }

        sortPrefix(deviationSortBuffer, adaptiveSampleCount);

        long mad = medianOfSorted(
                deviationSortBuffer,
                adaptiveSampleCount
        );

        long current = adaptiveCompensationNs.get();

        long difference = clamp(
                median - current,
                -MAX_COMPENSATION_STEP_NS,
                MAX_COMPENSATION_STEP_NS
        );

        long updated = clamp(
                current + difference,
                MIN_COMPENSATION_NS,
                MAX_COMPENSATION_NS
        );

        adaptiveCompensationNs.set(updated);
        adaptiveDeviationNs.set(mad);

        saveAdaptiveCompensation(updated);
    }

    private void saveAdaptiveCompensation(long compensation) {
        SharedPreferences prefs = targetPreferences;
        ExecutorService writer = benchmarkWriter;

        if (prefs == null || writer == null) {
            return;
        }

        try {
            writer.execute(() ->
                    prefs.edit()
                            .putLong(
                                    KEY_ADAPTIVE_COMPENSATION,
                                    compensation
                            )
                            .apply()
            );
        } catch (Exception ignored) {
        }
    }

    private static void sortPrefix(long[] values, int length) {
        for (int i = 1; i < length; i++) {
            long value = values[i];
            int j = i - 1;

            while (j >= 0 && values[j] > value) {
                values[j + 1] = values[j];
                j--;
            }

            values[j + 1] = value;
        }
    }

    private static long medianOfSorted(
            long[] values,
            int length) {

        int middle = length / 2;

        if ((length & 1) != 0) {
            return values[middle];
        }

        return (
                values[middle - 1]
                        + values[middle]
        ) / 2L;
    }

    private static long clamp(
            long value,
            long min,
            long max) {

        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        runOnMain(this::cancelPendingInternal);
    }

    @Override
    public void onDestroy() {
        serviceReady = false;
        schedulerStopping = true;

        Thread worker = schedulerThread;
        schedulerThread = null;

        List<ClickRequest> queued = new ArrayList<>();

        synchronized (queueLock) {
            cancellationGeneration.incrementAndGet();

            ClickRequest request;

            while ((request = pending.poll()) != null) {
                queued.add(request);
            }

            while ((request = ready.poll()) != null) {
                queued.add(request);
            }

            queueVersion.incrementAndGet();
        }

        for (ClickRequest request : queued) {
            finishQueuedOrDispatching(
                    request,
                    false,
                    true,
                    REASON_SERVICE_STOPPED
            );
        }

        if (worker != null) {
            LockSupport.unpark(worker);
            worker.interrupt();
        }

        for (ClickRequest active :
                activeRequests.toArray(new ClickRequest[0])) {

            finishActive(
                    active,
                    false,
                    true,
                    REASON_SERVICE_STOPPED
            );
        }

        if (instance == this) {
            instance = null;
        }

        targetPreferences = null;

        stopBenchmarkWriter();
        stopCallbackThread();

        super.onDestroy();
    }

    public static AutoClickService getInstance() {
        return instance;
    }

    public boolean isServiceReady() {
        return serviceReady;
    }

    public boolean isGestureInProgress() {
        return !activeRequests.isEmpty();
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetY() {
        return targetY;
    }

    public String getLastRequestError() {
        return lastRequestError;
    }

    public void setTarget(int x, int y) {
        int newX = Math.max(0, x);
        int newY = Math.max(0, y);

        runOnMain(() -> {
            if (!serviceReady
                    || (targetX == newX && targetY == newY)) {
                return;
            }

            targetX = newX;
            targetY = newY;

            SharedPreferences prefs = targetPreferences;

            if (prefs != null) {
                prefs.edit()
                        .putInt(KEY_X, newX)
                        .putInt(KEY_Y, newY)
                        .apply();
            }
        });
    }

    private boolean validateTouchDuration(long durationNanos) {
        if (durationNanos <= 0L) {
            lastRequestError =
                    "مدت لمس باید بیشتر از صفر باشد";
            return false;
        }

        if (durationNanos % NS_PER_MS != 0L) {
            lastRequestError =
                    "مدت لمس باید مضرب دقیق ۱٬۰۰۰٬۰۰۰ نانوثانیه باشد";
            return false;
        }

        long durationMs = durationNanos / NS_PER_MS;

        if (durationMs + STROKE_OFFSET_MS > MAX_GESTURE_MS) {
            lastRequestError =
                    "مدت لمس از حداکثر زمان مجاز اندروید بیشتر است";
            return false;
        }

        return true;
    }

    private GestureDescription createTapGesture(
            int x,
            int y,
            long offsetMs,
            long durationMs) {

        Path path = new Path();

        path.moveTo(x, y);
        path.lineTo(x, y);

        return new GestureDescription.Builder()
                .addStroke(
                        new GestureDescription.StrokeDescription(
                                path,
                                offsetMs,
                                durationMs
                        )
                )
                .build();
    }

    public boolean performClickAtTarget(
            long targetNanos,
            long cycleStartNanos,
            long deadlineNanos,
            GestureCompletion completion) {

        return performClickAtTarget(
                targetNanos,
                cycleStartNanos,
                deadlineNanos,
                DEFAULT_TAP_DURATION_MS * NS_PER_MS,
                completion
        );
    }

    public boolean performClickAtTarget(
            long targetNanos,
            long cycleStartNanos,
            long deadlineNanos,
            long touchDurationNanos,
            GestureCompletion completion) {

        if (!serviceReady || schedulerStopping) {
            lastRequestError = "سرویس کلیک آماده نیست";
            return false;
        }

        if (completion == null) {
            lastRequestError = "تابع تکمیل کلیک مشخص نشده است";
            return false;
        }

        if (!validateTouchDuration(touchDurationNanos)) {
            return false;
        }

        if (deadlineNanos <= cycleStartNanos
                || targetNanos < cycleStartNanos
                || targetNanos >= deadlineNanos
                || touchDurationNanos
                > deadlineNanos - targetNanos) {

            lastRequestError =
                    "زمان کلیک یا مدت لمس خارج از محدوده چرخه است";
            return false;
        }

        GestureDescription gesture;

        try {
            gesture = createTapGesture(
                    targetX,
                    targetY,
                    STROKE_OFFSET_MS,
                    touchDurationNanos / NS_PER_MS
            );
        } catch (Exception ignored) {
            lastRequestError = "آماده‌سازی ژست کلیک ناموفق بود";
            return false;
        }

        long generation = cancellationGeneration.get();

        TimingGroup group = new TimingGroup(
                targetNanos,
                generation
        );

        ClickRequest request = new ClickRequest(
                cycleStartNanos,
                targetNanos,
                deadlineNanos,
                touchDurationNanos,
                gesture,
                completion,
                1,
                1,
                generation,
                group
        );

        group.requests.add(request);

        if (!enqueueGroup(group)) {
            return false;
        }

        lastRequestError = "";
        return true;
    }

    public boolean performClickBatchAtTargets(
            long[] targets,
            long cycleStart,
            long deadline,
            long touchDurationNanos,
            GestureCompletion[] completions) {

        if (!serviceReady || schedulerStopping) {
            lastRequestError = "سرویس کلیک آماده نیست";
            return false;
        }

        if (targets == null
                || completions == null
                || targets.length == 0
                || targets.length != completions.length) {

            lastRequestError = "اطلاعات دسته کلیک نامعتبر است";
            return false;
        }

        if (!validateTouchDuration(touchDurationNanos)) {
            return false;
        }

        if (deadline <= cycleStart) {
            lastRequestError = "مهلت چرخه نامعتبر است";
            return false;
        }

        GestureDescription gesture;

        try {
            gesture = createTapGesture(
                    targetX,
                    targetY,
                    STROKE_OFFSET_MS,
                    touchDurationNanos / NS_PER_MS
            );
        } catch (Exception ignored) {
            lastRequestError = "آماده‌سازی ژست کلیک ناموفق بود";
            return false;
        }

        long generation = cancellationGeneration.get();
        long previous = Long.MIN_VALUE;

        List<Long> validatedTargets =
                new ArrayList<>(targets.length);

        for (int i = 0; i < targets.length; i++) {
            long target = targets[i];

            if (completions[i] == null) {
                lastRequestError =
                        "تابع تکمیل یکی از کلیک‌ها مشخص نشده است";
                return false;
            }

            if (target < cycleStart || target >= deadline) {
                lastRequestError =
                        "زمان یکی از کلیک‌ها خارج از محدوده چرخه است";
                return false;
            }

            if (target < previous) {
                lastRequestError =
                        "ترتیب زمان‌های کلیک در دسته نامعتبر است";
                return false;
            }

            if (touchDurationNanos > deadline - target) {
                lastRequestError =
                        "مدت لمس یکی از کلیک‌ها از مهلت چرخه عبور می‌کند";
                return false;
            }

            previous = target;
            validatedTargets.add(target);
        }

        TimingGroup group = new TimingGroup(
                validatedTargets.get(0),
                generation
        );

        for (int i = 0; i < targets.length; i++) {
            ClickRequest request = new ClickRequest(
                    cycleStart,
                    targets[i],
                    deadline,
                    touchDurationNanos,
                    gesture,
                    completions[i],
                    i + 1,
                    targets.length,
                    generation,
                    group
            );

            group.requests.add(request);
        }

        if (!enqueueGroup(group)) {
            return false;
        }

        lastRequestError = "";
        return true;
    }

    private boolean enqueueGroup(TimingGroup group) {
        if (!serviceReady || schedulerStopping) {
            lastRequestError = "سرویس کلیک متوقف شده است";
            return false;
        }

        synchronized (queueLock) {
            if (!serviceReady
                    || schedulerStopping
                    || group.cancelGeneration
                    != cancellationGeneration.get()) {

                lastRequestError =
                        "درخواست هنگام ثبت لغو شده است";
                return false;
            }

            pending.addAll(group.requests);
            queueVersion.incrementAndGet();
        }

        wakeScheduler();
        return true;
    }

    private void wakeScheduler() {
        Thread worker = schedulerThread;

        if (worker != null) {
            LockSupport.unpark(worker);
        }
    }

    private void finishQueuedOrDispatching(
            ClickRequest request,
            boolean success,
            boolean expired,
            String reason) {

        int state;

        do {
            state = request.state.get();

            if (state == FINISHED || state == ACTIVE) {
                return;
            }
        } while (!request.state.compareAndSet(
                state,
                FINISHED
        ));

        request.terminalReason = reason;

        deliverCompletion(request, success, expired);
    }

    private void finishActive(
            ClickRequest request,
            boolean success,
            boolean expired,
            String reason) {

        if (!request.state.compareAndSet(
                ACTIVE,
                FINISHED
        )) {
            return;
        }

        request.terminalReason = reason;
        activeRequests.remove(request);

        deliverCompletion(request, success, expired);
    }

    private void deliverCompletion(
            ClickRequest request,
            boolean success,
            boolean expired) {

        if (!request.completionDelivered.compareAndSet(
                false,
                true
        )) {
            return;
        }

        if (request.callbackNanos == 0L
                && request.dispatchNanos != 0L) {

            request.callbackNanos =
                    SystemClock.elapsedRealtimeNanos();
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyCompletion(request, success, expired);
        } else if (!mainHandler.post(
                () -> notifyCompletion(
                        request,
                        success,
                        expired
                )
        )) {
            notifyCompletion(request, success, expired);
        }
    }

    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired) {

        writeBenchmarkRow(request);

        GestureCompletion callback = request.completion;
        request.completion = null;

        if (callback != null) {
            try {
                callback.onFinished(success, expired);
            } catch (Exception ignored) {
            }
        }
    }

    public void cancelPendingScheduledClick() {
        runOnMain(this::cancelPendingInternal);
    }

    private void cancelPendingInternal() {
        List<ClickRequest> queued = new ArrayList<>();

        synchronized (queueLock) {
            cancellationGeneration.incrementAndGet();

            ClickRequest request;

            while ((request = pending.poll()) != null) {
                queued.add(request);
            }

            while ((request = ready.poll()) != null) {
                queued.add(request);
            }

            queueVersion.incrementAndGet();
        }

        for (ClickRequest request : queued) {
            finishQueuedOrDispatching(
                    request,
                    false,
                    true,
                    REASON_CANCELLED
            );
        }

        wakeScheduler();
    }

    private void startBenchmarkWriter() {
        benchmarkSessionId = System.currentTimeMillis();

        File directory = getExternalFilesDir("benchmark");

        if (directory == null) {
            directory = getFilesDir();
        }

        if (!directory.exists() && !directory.mkdirs()) {
            return;
        }

        benchmarkFile = new File(directory, CSV_NAME);

        benchmarkWriter = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(
                    r,
                    "ClickBenchmarkWriter"
            );

            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });

        File output = benchmarkFile;

        benchmarkWriter.execute(() -> {
            if (output.length() != 0L) {
                return;
            }

            try (FileWriter writer = new FileWriter(output, true)) {
                writer.write(
                        "session_start_epoch_ms,request_id,"
                                + "cycle_start_elapsed_ns,target_elapsed_ns,"
                                + "dispatch_target_elapsed_ns,deadline_elapsed_ns,"
                                + "enqueue_elapsed_ns,scheduler_ready_elapsed_ns,"
                                + "dispatch_elapsed_ns,dispatch_pipeline_ns,"
                                + "scheduler_ready_late_ns,dispatch_late_ns,"
                                + "dispatch_schedule_late_ns,"
                                + "estimated_stroke_start_error_ns,"
                                + "adaptive_compensation_ns,adaptive_deviation_ns,"
                                + "dispatch_lead_ns,dispatch_accepted,"
                                + "dispatch_return_elapsed_ns,"
                                + "dispatch_call_duration_ns,"
                                + "callback_elapsed_ns,callback_delay_ns,"
                                + "click_index,batch_size,"
                                + "stroke_start_offset_ms,result,terminal_reason\n"
                );

                writer.flush();
            } catch (IOException ignored) {
            }
        });
    }

    private void writeBenchmarkRow(ClickRequest request) {
        ExecutorService writer = benchmarkWriter;
        File output = benchmarkFile;

        if (writer == null || output == null || request == null) {
            return;
        }

        long dispatch = request.dispatchNanos;
        long callback = request.callbackNanos;
        long readyTime = request.schedulerReadyNanos;

        long late = dispatch == 0L
                ? 0L
                : dispatch - request.targetNanos;

        long scheduleLate = dispatch == 0L
                ? 0L
                : dispatch - request.dispatchTargetNanos;

        long pipeline = dispatch == 0L || readyTime == 0L
                ? 0L
                : dispatch - readyTime;

        long readyLate = readyTime == 0L
                ? 0L
                : readyTime - request.dispatchTargetNanos;

        long strokeError = dispatch == 0L
                ? 0L
                : dispatch
                + STROKE_OFFSET_NS
                - request.targetNanos;

        long callbackDelay =
                dispatch == 0L || callback == 0L
                        ? 0L
                        : callback - dispatch;

        long callDuration =
                request.dispatchReturnNanos == 0L
                        || dispatch == 0L
                        ? 0L
                        : request.dispatchReturnNanos - dispatch;

        String result = request.terminalReason;

        if (result == null || result.isEmpty()) {
            result = REASON_GESTURE_FAILED;
        }

        String row =
                benchmarkSessionId + ","
                        + request.requestId + ","
                        + request.cycleStartNanos + ","
                        + request.targetNanos + ","
                        + request.dispatchTargetNanos + ","
                        + request.deadlineNanos + ","
                        + request.enqueueNanos + ","
                        + readyTime + ","
                        + dispatch + ","
                        + pipeline + ","
                        + readyLate + ","
                        + late + ","
                        + scheduleLate + ","
                        + strokeError + ","
                        + request.compensationNanos + ","
                        + request.deviationNanos + ","
                        + request.dispatchLeadNanos + ","
                        + request.dispatchAccepted + ","
                        + request.dispatchReturnNanos + ","
                        + callDuration + ","
                        + callback + ","
                        + callbackDelay + ","
                        + request.clickIndex + ","
                        + request.batchSize + ","
                        + STROKE_OFFSET_MS + ","
                        + result + ","
                        + result + "\n";

        try {
            writer.execute(() -> {
                try (BufferedWriter out =
                             new BufferedWriter(
                                     new FileWriter(output, true)
                             )) {

                    out.write(row);
                    out.flush();
                } catch (IOException ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void stopBenchmarkWriter() {
        ExecutorService writer = benchmarkWriter;
        benchmarkWriter = null;

        if (writer != null) {
            writer.shutdown();
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }
}
