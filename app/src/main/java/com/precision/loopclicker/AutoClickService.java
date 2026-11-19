
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {

    private static final long NANOS_PER_MS = 1_000_000L;

    private static final long FINAL_SPIN_WINDOW_NANOS =
            5_000_000L;

    private static final String TARGET_PREFS_NAME =
            "auto_click_target";

    private static final String TARGET_X_KEY = "target_x";
    private static final String TARGET_Y_KEY = "target_y";

    private static final int DEFAULT_TARGET_X = 350;
    private static final int DEFAULT_TARGET_Y = 700;

    private static final int INITIAL_PENDING_CAPACITY = 64;

    private static final int INITIAL_ACTIVE_CAPACITY = 32;

    private static final long MAX_GESTURE_DURATION_MS =
            GestureDescription.getMaxGestureDuration();

    private static final String TIMING_FILE_NAME =
            "PrecisionClickTiming.txt";

    private static volatile AutoClickService instance;

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final Object queueLock = new Object();

    private final ArrayDeque<ClickRequest> pending =
            new ArrayDeque<>(INITIAL_PENDING_CAPACITY);

    private final HashSet<ClickRequest> dispatchQueuedRequests =
            new HashSet<>(INITIAL_PENDING_CAPACITY);

    private final HashSet<ClickRequest> activeRequests =
            new HashSet<>(INITIAL_ACTIVE_CAPACITY);

    private volatile boolean serviceReady;
    private volatile boolean schedulerStopping;

    private volatile Thread schedulerThread;

    private volatile int targetX = DEFAULT_TARGET_X;
    private volatile int targetY = DEFAULT_TARGET_Y;

    private volatile long cancellationGeneration = 0L;

    private volatile ExecutorService preparationExecutor;
    private volatile ExecutorService dispatchExecutor;

    private SharedPreferences targetPreferences;

    // =========================================================
    // گزارش‌گیری غیرمسدودکننده
    // =========================================================

    private final LinkedBlockingQueue<TimingRecord> timingQueue =
            new LinkedBlockingQueue<>();

    private volatile Thread timingWriterThread;
    private volatile boolean timingWriterStopping;

    private volatile String timingFilePath =
            "فایل گزارش هنوز ساخته نشده است";

    private static final class TimingRecord {

        final long targetNanos;
        final long prepareStartNanos;
        final long prepareEndNanos;
        final long dispatchStartNanos;
        final long dispatchDurationNanos;
        final long completionNanos;

        final int x;
        final int y;

        final boolean accepted;
        final boolean success;
        final boolean expired;

        final String callbackResult;

        TimingRecord(
                ClickRequest request,
                boolean success,
                boolean expired) {

            targetNanos = request.targetNanos;
            prepareStartNanos = request.prepareStartNanos;
            prepareEndNanos = request.prepareEndNanos;
            dispatchStartNanos = request.dispatchStartNanos;
            dispatchDurationNanos =
                    request.dispatchDurationNanos;
            completionNanos = request.completionNanos;

            x = request.x;
            y = request.y;

            accepted = request.dispatchAccepted;
            this.success = success;
            this.expired = expired;

            callbackResult = request.callbackResult;
        }

        String toCsvLine() {

            long dispatchDelay =
                    dispatchStartNanos == 0L
                            ? 0L
                            : dispatchStartNanos - targetNanos;

            long preparationDuration =
                    prepareStartNanos == 0L
                            || prepareEndNanos == 0L
                            ? 0L
                            : prepareEndNanos - prepareStartNanos;

            long completionDelay =
                    completionNanos == 0L
                            ? 0L
                            : completionNanos - targetNanos;

            String result;

            if (expired) {
                result = "EXPIRED";
            } else if (success) {
                result = "SUCCESS";
            } else {
                result = "FAILED";
            }

            return targetNanos + ","
                    + prepareStartNanos + ","
                    + prepareEndNanos + ","
                    + preparationDuration + ","
                    + dispatchStartNanos + ","
                    + dispatchDelay + ","
                    + dispatchDurationNanos + ","
                    + completionNanos + ","
                    + completionDelay + ","
                    + accepted + ","
                    + result + ","
                    + callbackResult + ","
                    + x + ","
                    + y;
        }
    }

    private void startTimingWriter() {

        timingWriterStopping = false;

        Thread writer = new Thread(
                this::timingWriterLoop,
                "PrecisionTimingFileWriter");

        timingWriterThread = writer;
        writer.start();
    }

    private void timingWriterLoop() {

        BufferedWriter writer = null;

        try {

            File directory = getExternalFilesDir(null);

            if (directory == null) {
                directory = getFilesDir();
            }

            if (!directory.exists() && !directory.mkdirs()) {
                return;
            }

            File file = new File(
                    directory,
                    TIMING_FILE_NAME);

            boolean writeHeader =
                    !file.exists() || file.length() == 0L;

            writer = new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file, true),
                            StandardCharsets.UTF_8));

            timingFilePath = file.getAbsolutePath();

            if (writeHeader) {

                writer.write(
                        "target_elapsed_ns,"
                                + "prepare_start_elapsed_ns,"
                                + "prepare_end_elapsed_ns,"
                                + "prepare_duration_ns,"
                                + "dispatch_start_elapsed_ns,"
                                + "dispatch_delay_ns,"
                                + "dispatch_duration_ns,"
                                + "completion_elapsed_ns,"
                                + "completion_delay_ns,"
                                + "accepted,"
                                + "result,"
                                + "callback_result,"
                                + "x,"
                                + "y");

                writer.newLine();
                writer.flush();
            }

            while (!timingWriterStopping
                    || !timingQueue.isEmpty()) {

                TimingRecord record;

                try {

                    record = timingQueue.poll(
                            500,
                            TimeUnit.MILLISECONDS);

                } catch (InterruptedException ignored) {
                    continue;
                }

                if (record == null) {
                    continue;
                }

                writer.write(record.toCsvLine());
                writer.newLine();
                writer.flush();
            }

        } catch (IOException ignored) {

            // خطای فایل نباید زمان‌بندی کلیک را متوقف کند.

        } finally {

            if (writer != null) {

                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void recordTiming(
            ClickRequest request,
            boolean success,
            boolean expired) {

        if (request == null
                || !request.timingRecorded.compareAndSet(
                        false,
                        true)) {
            return;
        }

        timingQueue.offer(
                new TimingRecord(
                        request,
                        success,
                        expired));
    }

    private void stopTimingWriter() {

        timingWriterStopping = true;

        Thread writer = timingWriterThread;

        if (writer != null) {
            writer.interrupt();
        }
    }

    public String getTimingFilePath() {
        return timingFilePath;
    }

    // =========================================================
    // درخواست کلیک
    // =========================================================

    private static final class ClickRequest
            extends AccessibilityService.GestureResultCallback {

        final AutoClickService owner;

        final long targetNanos;
        final long deadlineNanos;
        final long touchDurationMs;

        final long requestGeneration;

        final int x;
        final int y;

        final AtomicBoolean timingRecorded =
                new AtomicBoolean(false);

        final AtomicBoolean completionDelivered =
                new AtomicBoolean(false);

        volatile GestureDescription gesture;

        volatile long prepareStartNanos;
        volatile long prepareEndNanos;

        volatile long dispatchStartNanos;
        volatile long dispatchDurationNanos;
        volatile long completionNanos;

        volatile boolean dispatchAccepted;

        volatile String callbackResult = "NOT_DISPATCHED";

        volatile GestureCompletion completion;

        ClickRequest(
                AutoClickService owner,
                long targetNanos,
                long deadlineNanos,
                long touchDurationMs,
                int x,
                int y,
                long requestGeneration,
                GestureCompletion completion) {

            this.owner = owner;

            this.targetNanos = targetNanos;
            this.deadlineNanos = deadlineNanos;
            this.touchDurationMs = touchDurationMs;

            this.x = x;
            this.y = y;

            this.requestGeneration = requestGeneration;
            this.completion = completion;
        }

        void prepareGesture() {

            if (gesture != null) {
                return;
            }

            prepareStartNanos =
                    SystemClock.elapsedRealtimeNanos();

            Path path = new Path();

            path.moveTo(x, y);
            path.lineTo(x, y);

            GestureDescription prepared =
                    new GestureDescription.Builder()
                            .addStroke(
                                    new GestureDescription.StrokeDescription(
                                            path,
                                            0L,
                                            touchDurationMs))
                            .build();

            gesture = prepared;

            prepareEndNanos =
                    SystemClock.elapsedRealtimeNanos();
        }

        @Override
        public void onCompleted(GestureDescription gesture) {

            callbackResult = "ON_COMPLETED";

            owner.completeRequest(this, true);
        }

        @Override
        public void onCancelled(GestureDescription gesture) {

            callbackResult = "ON_CANCELLED";

            owner.completeRequest(this, false);
        }
    }

    // =========================================================
    // اتصال سرویس
    // =========================================================

    @Override
    protected void onServiceConnected() {

        super.onServiceConnected();

        targetPreferences = getSharedPreferences(
                TARGET_PREFS_NAME,
                MODE_PRIVATE);

        targetX = targetPreferences.getInt(
                TARGET_X_KEY,
                DEFAULT_TARGET_X);

        targetY = targetPreferences.getInt(
                TARGET_Y_KEY,
                DEFAULT_TARGET_Y);

        schedulerStopping = false;
        serviceReady = true;

        instance = this;

        startTimingWriter();
        startPreparationExecutor();
        startDispatchExecutor();
        startNanoScheduler();
    }

    // =========================================================
    // نخ آماده‌سازی Gesture
    // =========================================================

    private void startPreparationExecutor() {

        ExecutorService existing = preparationExecutor;

        if (existing != null && !existing.isShutdown()) {
            return;
        }

        ThreadFactory factory = runnable -> {

            Thread thread = new Thread(
                    runnable,
                    "PrecisionGesturePreparation");

            return thread;
        };

        preparationExecutor =
                Executors.newSingleThreadExecutor(factory);
    }

    private void prepareRequest(ClickRequest request) {

        if (!isRequestCurrent(request)) {
            return;
        }

        try {

            request.prepareGesture();

            if (isRequestCurrent(request)) {

                Thread worker = schedulerThread;

                if (worker != null) {
                    LockSupport.unpark(worker);
                }
            }

        } catch (Exception ignored) {

            boolean removed;

            synchronized (queueLock) {
                removed = pending.remove(request);
            }

            if (removed) {

                request.callbackResult =
                        "PREPARATION_FAILED";

                postCompletion(
                        request,
                        false,
                        false);
            }

            Thread worker = schedulerThread;

            if (worker != null) {
                LockSupport.unpark(worker);
            }
        }
    }

    // =========================================================
    // نخ مستقل ارسال Gesture
    // =========================================================

    private void startDispatchExecutor() {

        ExecutorService existing = dispatchExecutor;

        if (existing != null && !existing.isShutdown()) {
            return;
        }

        ThreadFactory factory = runnable -> {

            Thread thread = new Thread(() -> {

                try {

                    Process.setThreadPriority(
                            Process.THREAD_PRIORITY_URGENT_DISPLAY);

                } catch (Exception ignored) {
                }

                runnable.run();

            }, "PrecisionGestureDispatch");

            return thread;
        };

        dispatchExecutor =
                Executors.newSingleThreadExecutor(factory);
    }

    private void enqueueDispatch(ClickRequest request) {

        ExecutorService executor = dispatchExecutor;

        if (executor == null || executor.isShutdown()) {

            request.callbackResult =
                    "DISPATCH_EXECUTOR_UNAVAILABLE";

            postCompletion(
                    request,
                    false,
                    false);

            return;
        }

        try {

            executor.execute(() ->
                    dispatchReadyRequest(request));

        } catch (Exception ignored) {

            request.callbackResult =
                    "DISPATCH_QUEUE_REJECTED";

            postCompletion(
                    request,
                    false,
                    false);
        }
    }

    // =========================================================
    // زمان‌بند نانوثانیه‌ای
    // =========================================================

    private void startNanoScheduler() {

        Thread existing = schedulerThread;

        if (existing != null && existing.isAlive()) {
            return;
        }

        Thread worker = new Thread(
                this::schedulerLoop,
                "PrecisionClickNanoScheduler");

        schedulerThread = worker;
        worker.start();
    }

    private void schedulerLoop() {

        try {

            Process.setThreadPriority(
                    Process.THREAD_PRIORITY_URGENT_DISPLAY);

        } catch (Exception ignored) {
        }

        while (!schedulerStopping) {

            ClickRequest request;

            synchronized (queueLock) {
                request = pending.peekFirst();
            }

            if (request == null) {

                LockSupport.park(this);
                continue;
            }

            if (!isRequestCurrent(request)) {

                removePendingHead(request);
                continue;
            }

            if (request.gesture == null) {

                LockSupport.park(this);
                continue;
            }

            long nowNanos =
                    SystemClock.elapsedRealtimeNanos();

            long remainingNanos =
                    request.targetNanos - nowNanos;

            if (remainingNanos > FINAL_SPIN_WINDOW_NANOS) {

                LockSupport.parkNanos(
                        this,
                        remainingNanos - FINAL_SPIN_WINDOW_NANOS);

                continue;
            }

            while (!schedulerStopping
                    && serviceReady
                    && request.requestGeneration
                            == cancellationGeneration
                    && SystemClock.elapsedRealtimeNanos()
                            < request.targetNanos) {

                // Precision spin.
            }

            if (schedulerStopping) {
                break;
            }

            if (request.requestGeneration
                    != cancellationGeneration) {

                removePendingHead(request);
                continue;
            }

            boolean movedToDispatchQueue = false;

            synchronized (queueLock) {

                if (pending.peekFirst() != request) {
                    continue;
                }

                if (request.requestGeneration
                        != cancellationGeneration) {

                    pending.pollFirst();

                } else {

                    pending.pollFirst();

                    dispatchQueuedRequests.add(request);

                    movedToDispatchQueue = true;
                }
            }

            if (!movedToDispatchQueue) {

                postCompletion(
                        request,
                        false,
                        true);

                continue;
            }

            enqueueDispatch(request);
        }
    }

    private boolean isRequestCurrent(ClickRequest request) {

        return request != null
                && request.requestGeneration
                        == cancellationGeneration
                && serviceReady
                && !schedulerStopping;
    }

    private void removePendingHead(ClickRequest request) {

        boolean removed = false;

        synchronized (queueLock) {

            if (pending.peekFirst() == request) {

                pending.pollFirst();
                removed = true;
            }
        }

        if (removed) {

            postCompletion(
                    request,
                    false,
                    true);
        }
    }

    // =========================================================
    // رویدادهای Accessibility
    // =========================================================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event processing required.
    }

    @Override
    public void onInterrupt() {

        cancelPendingInternal();
    }

    // =========================================================
    // بستن سرویس
    // =========================================================

    @Override
    public void onDestroy() {

        serviceReady = false;
        schedulerStopping = true;

        Thread worker = schedulerThread;
        schedulerThread = null;

        if (worker != null) {

            LockSupport.unpark(worker);
            worker.interrupt();
        }

        ExecutorService prepExecutor = preparationExecutor;
        preparationExecutor = null;

        if (prepExecutor != null) {
            prepExecutor.shutdownNow();
        }

        ExecutorService senderExecutor = dispatchExecutor;
        dispatchExecutor = null;

        if (senderExecutor != null) {
            senderExecutor.shutdownNow();
        }

        List<ClickRequest> pendingToFinish =
                new ArrayList<>();

        List<ClickRequest> queuedToFinish =
                new ArrayList<>();

        List<ClickRequest> activeToFinish =
                new ArrayList<>();

        synchronized (queueLock) {

            cancellationGeneration++;

            while (!pending.isEmpty()) {

                pendingToFinish.add(
                        pending.pollFirst());
            }

            queuedToFinish.addAll(
                    dispatchQueuedRequests);

            dispatchQueuedRequests.clear();

            activeToFinish.addAll(activeRequests);
            activeRequests.clear();
        }

        for (ClickRequest request : pendingToFinish) {

            request.callbackResult = "SERVICE_DESTROYED";

            notifyCompletion(
                    request,
                    false,
                    true);
        }

        for (ClickRequest request : queuedToFinish) {

            request.callbackResult = "SERVICE_DESTROYED";

            notifyCompletion(
                    request,
                    false,
                    true);
        }

        for (ClickRequest request : activeToFinish) {

            request.callbackResult = "SERVICE_DESTROYED";

            notifyCompletion(
                    request,
                    false,
                    false);
        }

        stopTimingWriter();

        if (instance == this) {
            instance = null;
        }

        targetPreferences = null;

        super.onDestroy();
    }

    // =========================================================
    // دسترسی به نمونه سرویس
    // =========================================================

    public static AutoClickService getInstance() {
        return instance;
    }

    public boolean isServiceReady() {
        return serviceReady;
    }

    public boolean isGestureInProgress() {

        synchronized (queueLock) {
            return !activeRequests.isEmpty();
        }
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetY() {
        return targetY;
    }

    // =========================================================
    // تنظیم مختصات
    // =========================================================

    public void setTarget(int x, int y) {

        final int newX = Math.max(0, x);
        final int newY = Math.max(0, y);

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
                        .putInt(TARGET_X_KEY, newX)
                        .putInt(TARGET_Y_KEY, newY)
                        .apply();
            }
        });
    }

    // =========================================================
    // ثبت درخواست کلیک
    // =========================================================

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            long touchDurationNanos,
            GestureCompletion completion) {

        if (!serviceReady
                || schedulerStopping
                || completion == null
                || touchDurationNanos < 0
                || cycleDeadlineElapsedRealtimeNanos
                        <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                        < cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                        >= cycleDeadlineElapsedRealtimeNanos) {

            return false;
        }

        long touchDurationMs =
                toGestureDurationMs(touchDurationNanos);

        if (touchDurationMs > MAX_GESTURE_DURATION_MS) {
            return false;
        }

        boolean wasEmpty;
        ClickRequest request;

        synchronized (queueLock) {

            if (!serviceReady || schedulerStopping) {
                return false;
            }

            ExecutorService executor = preparationExecutor;

            if (executor == null || executor.isShutdown()) {
                return false;
            }

            request = new ClickRequest(
                    this,
                    targetElapsedRealtimeNanos,
                    cycleDeadlineElapsedRealtimeNanos,
                    touchDurationMs,
                    targetX,
                    targetY,
                    cancellationGeneration,
                    completion);

            wasEmpty = pending.isEmpty();

            pending.offerLast(request);

            try {

                executor.execute(
                        () -> prepareRequest(request));

            } catch (Exception ignored) {

                pending.remove(request);
                return false;
            }
        }

        if (wasEmpty) {

            Thread worker = schedulerThread;

            if (worker != null) {
                LockSupport.unpark(worker);
            }
        }

        return true;
    }

    private static long toGestureDurationMs(
            long durationNanos) {

        if (durationNanos <= 0L) {
            return 1L;
        }

        long millis =
                durationNanos / NANOS_PER_MS;

        if (durationNanos % NANOS_PER_MS != 0L) {
            millis++;
        }

        return Math.max(1L, millis);
    }

    // =========================================================
    // کنترل مهلت چرخه و ارسال
    // =========================================================

    private void dispatchReadyRequest(ClickRequest request) {

        synchronized (queueLock) {
            dispatchQueuedRequests.remove(request);
        }

        if (!isRequestCurrent(request)) {

            request.callbackResult =
                    "REQUEST_CANCELLED_BEFORE_DISPATCH";

            postCompletion(
                    request,
                    false,
                    true);

            return;
        }

        long beforeDispatch =
                SystemClock.elapsedRealtimeNanos();

        if (beforeDispatch >= request.deadlineNanos) {

            request.callbackResult =
                    "DEADLINE_EXPIRED_IN_DISPATCH_QUEUE";

            postCompletion(
                    request,
                    false,
                    true);

            return;
        }

        if (!dispatchRequest(request)) {

            if ("NOT_DISPATCHED".equals(
                    request.callbackResult)) {

                request.callbackResult =
                        "DISPATCH_REJECTED";
            }

            postCompletion(
                    request,
                    false,
                    false);
        }
    }

    private boolean dispatchRequest(ClickRequest request) {

        GestureDescription gesture = request.gesture;

        if (gesture == null) {

            request.callbackResult =
                    "GESTURE_NOT_PREPARED";

            return false;
        }

        if (!serviceReady
                || schedulerStopping
                || request.requestGeneration
                        != cancellationGeneration) {

            request.callbackResult =
                    "REQUEST_CANCELLED_BEFORE_API";

            return false;
        }

        long finalCheckNanos =
                SystemClock.elapsedRealtimeNanos();

        if (finalCheckNanos >= request.deadlineNanos) {

            request.callbackResult =
                    "DEADLINE_EXPIRED_BEFORE_API";

            return false;
        }

        synchronized (queueLock) {

            if (!serviceReady
                    || schedulerStopping
                    || request.requestGeneration
                            != cancellationGeneration) {

                request.callbackResult =
                        "REQUEST_CANCELLED_BEFORE_API";

                return false;
            }

            activeRequests.add(request);
        }

        long dispatchStart =
                SystemClock.elapsedRealtimeNanos();

        if (dispatchStart >= request.deadlineNanos) {

            synchronized (queueLock) {
                activeRequests.remove(request);
            }

            request.callbackResult =
                    "DEADLINE_EXPIRED_AT_API_ENTRY";

            return false;
        }

        request.dispatchStartNanos = dispatchStart;

        boolean accepted;

        try {

            accepted = dispatchGesture(
                    gesture,
                    request,
                    mainHandler);

        } catch (Exception ignored) {

            request.dispatchDurationNanos =
                    SystemClock.elapsedRealtimeNanos()
                            - dispatchStart;

            request.callbackResult =
                    "DISPATCH_EXCEPTION";

            synchronized (queueLock) {
                activeRequests.remove(request);
            }

            return false;
        }

        long dispatchEnd =
                SystemClock.elapsedRealtimeNanos();

        request.dispatchDurationNanos =
                dispatchEnd - dispatchStart;

        request.dispatchAccepted = accepted;

        if (!accepted) {

            request.callbackResult =
                    "API_RETURNED_FALSE";

            synchronized (queueLock) {
                activeRequests.remove(request);
            }

            return false;
        }

        request.callbackResult =
                "WAITING_FOR_CALLBACK";

        return true;
    }

    // =========================================================
    // تکمیل کلیک
    // =========================================================

    private void completeRequest(
            ClickRequest request,
            boolean success) {

        boolean wasActive;

        synchronized (queueLock) {
            wasActive = activeRequests.remove(request);
        }

        if (wasActive) {

            request.completionNanos =
                    SystemClock.elapsedRealtimeNanos();

            notifyCompletion(
                    request,
                    success,
                    false);
        }
    }

    private void postCompletion(
            ClickRequest request,
            boolean success,
            boolean expired) {

        if (Looper.myLooper() == Looper.getMainLooper()) {

            notifyCompletion(
                    request,
                    success,
                    expired);

        } else {

            mainHandler.post(() ->
                    notifyCompletion(
                            request,
                            success,
                            expired));
        }
    }

    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired) {

        if (request == null
                || !request.completionDelivered.compareAndSet(
                        false,
                        true)) {
            return;
        }

        if (request.completionNanos == 0L) {

            request.completionNanos =
                    SystemClock.elapsedRealtimeNanos();
        }

        recordTiming(
                request,
                success,
                expired);

        GestureCompletion callback =
                request.completion;

        request.completion = null;

        if (callback != null) {

            try {

                callback.onFinished(
                        success,
                        expired);

            } catch (Exception ignored) {
                // Keep service operational.
            }
        }
    }

    // =========================================================
    // لغو درخواست‌ها
    // =========================================================

    public void cancelPendingScheduledClick() {

        cancelPendingInternal();
    }

    private void cancelPendingInternal() {

        List<ClickRequest> cancelled =
                new ArrayList<>();

        synchronized (queueLock) {

            cancellationGeneration++;

            while (!pending.isEmpty()) {

                cancelled.add(
                        pending.pollFirst());
            }

            cancelled.addAll(
                    dispatchQueuedRequests);

            dispatchQueuedRequests.clear();
        }

        Thread worker = schedulerThread;

        if (worker != null) {
            LockSupport.unpark(worker);
        }

        for (ClickRequest request : cancelled) {

            request.callbackResult =
                    "CANCELLED_BEFORE_DISPATCH";

            postCompletion(
                    request,
                    false,
                    true);
        }
    }

    // =========================================================
    // اجرای عملیات روی نخ اصلی
    // =========================================================

    private void runOnMain(Runnable action) {

        if (Looper.myLooper() == Looper.getMainLooper()) {

            action.run();

        } else {

            mainHandler.post(action);
        }
    }
}
