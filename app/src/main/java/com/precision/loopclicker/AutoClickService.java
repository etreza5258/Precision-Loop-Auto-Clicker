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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
public class AutoClickService extends AccessibilityService {
    private static final long NANOS_PER_MS = 1_000_000L;
    private static final String TARGET_PREFS_NAME = "auto_click_target";
    private static final String TARGET_X_KEY = "target_x";
    private static final String TARGET_Y_KEY = "target_y";
    private static final int DEFAULT_TARGET_X = 350;
    private static final int DEFAULT_TARGET_Y = 700;
    private static final long MAX_GESTURE_DURATION_MS =
            GestureDescription.getMaxGestureDuration();
    private static final long FINAL_SPIN_WINDOW_NS = 1_000_000L;
    private static final long DISPATCH_LEAD_MS = 2L;
    private static final long DISPATCH_LEAD_NS = DISPATCH_LEAD_MS * NANOS_PER_MS;
    private static final String BENCHMARK_FILE_NAME = "click_benchmark_v4.csv";
    private static volatile AutoClickService instance;
    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile HandlerThread callbackThread;
    private volatile Handler callbackHandler;
    private final Object queueLock = new Object();
    private final PriorityQueue<ClickRequest> pending =
            new PriorityQueue<>(
                    Comparator.comparingLong(request -> request.dispatchTargetNanos));
    private final HashSet<ClickRequest> dispatching = new HashSet<>();
    private final HashSet<ClickRequest> activeRequests = new HashSet<>();
    private volatile boolean serviceReady;
    private volatile boolean schedulerStopping;
    private volatile Thread schedulerThread;
    private volatile int targetX = DEFAULT_TARGET_X;
    private volatile int targetY = DEFAULT_TARGET_Y;
    private SharedPreferences targetPreferences;
    private volatile String lastRequestError = "";
    private final AtomicLong benchmarkRequestIds = new AtomicLong();
    private volatile long benchmarkSessionId;
    private volatile ExecutorService benchmarkWriter;
    private volatile File benchmarkFile;
    private final class ClickRequest extends AccessibilityService.GestureResultCallback {
        final long requestId;
        final long cycleStartNanos;
        final long targetNanos;
        final long dispatchTargetNanos;
        final long deadlineNanos;
        final long touchDurationNanos;
        final long enqueueNanos;
        final int clickIndex;
        final int batchSize;
        volatile long dispatchNanos;
        volatile long callbackNanos;
        volatile boolean dispatchAccepted;
        final GestureDescription gesture;
        GestureCompletion completion;
        final AtomicBoolean completionDelivered = new AtomicBoolean();
        ClickRequest(
                long cycleStart,
                long target,
                long deadline,
                long touchDurationNanos,
                GestureDescription gesture,
                GestureCompletion completion,
                int clickIndex,
                int batchSize) {
            requestId = benchmarkRequestIds.incrementAndGet();
            cycleStartNanos = cycleStart;
            targetNanos = target;
            dispatchTargetNanos = target - DISPATCH_LEAD_NS;
            deadlineNanos = deadline;
            this.touchDurationNanos = touchDurationNanos;
            this.enqueueNanos = SystemClock.elapsedRealtimeNanos();
            this.clickIndex = clickIndex;
            this.batchSize = batchSize;
            this.gesture = gesture;
            this.completion = completion;
        }
        @Override
        public void onCompleted(GestureDescription gesture) {
            completeRequest(this, true);
        }
        @Override
        public void onCancelled(GestureDescription gesture) {
            completeRequest(this, false);
        }
    }
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        startBenchmarkWriter();
        startCallbackThread();
        targetPreferences = getSharedPreferences(
                TARGET_PREFS_NAME, MODE_PRIVATE);
        targetX = targetPreferences.getInt(
                TARGET_X_KEY, DEFAULT_TARGET_X);
        targetY = targetPreferences.getInt(
                TARGET_Y_KEY, DEFAULT_TARGET_Y);
        schedulerStopping = false;
        serviceReady = true;
        instance = this;
        startNanoScheduler();
    }
    private void startCallbackThread() {
        HandlerThread existing = callbackThread;
        if (existing != null && existing.isAlive()) return;
        HandlerThread thread = new HandlerThread("PrecisionGestureCallback", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        callbackThread = thread;
        callbackHandler = new Handler(thread.getLooper());
    }
    private void stopCallbackThread() {
        HandlerThread thread = callbackThread;
        callbackThread = null;
        callbackHandler = null;
        if (thread != null) thread.quitSafely();
    }
    private void startNanoScheduler() {
        Thread existing = schedulerThread;
        if (existing != null && existing.isAlive()) return;
        Thread worker = new Thread(
                this::schedulerLoop,
                "PrecisionClickNanoScheduler");
        schedulerThread = worker;
        worker.start();
    }
    private void schedulerLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        } catch (Exception ignored) {
        }
        while (!schedulerStopping) {
            ClickRequest request;
            synchronized (queueLock) {
                request = pending.peek();
            }
            if (request == null) {
                LockSupport.park(this);
                continue;
            }
            long now = SystemClock.elapsedRealtimeNanos();
            long remaining = request.dispatchTargetNanos - now;
            if (remaining > FINAL_SPIN_WINDOW_NS) {
                LockSupport.parkNanos(
                        this, remaining - FINAL_SPIN_WINDOW_NS);
                continue;
            }
            if (remaining > 0) {
                do {
                    now = SystemClock.elapsedRealtimeNanos();
                } while (now < request.dispatchTargetNanos && !schedulerStopping);
                if (schedulerStopping) break;
            }
            synchronized (queueLock) {
                if (pending.peek() != request) continue;
                pending.poll();
                dispatching.add(request);
            }
            dispatchReadyRequest(request);
        }
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
        if (worker != null) {
            LockSupport.unpark(worker);
            worker.interrupt();
        }
        List<ClickRequest> toFinish = new ArrayList<>();
        synchronized (queueLock) {
            while (!pending.isEmpty()) {
                toFinish.add(pending.poll());
            }
            toFinish.addAll(dispatching);
            dispatching.clear();
            toFinish.addAll(activeRequests);
            activeRequests.clear();
        }
        for (ClickRequest request : toFinish) {
            notifyCompletion(request, false, true);
        }
        if (instance == this) instance = null;
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
    public String getLastRequestError() {
        return lastRequestError;
    }
    public void setTarget(int x, int y) {
        int newX = Math.max(0, x);
        int newY = Math.max(0, y);
        runOnMain(() -> {
            if (!serviceReady ||
                    (targetX == newX && targetY == newY)) {
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
    private boolean validateTouchDuration(long durationNanos) {
        if (durationNanos <= 0) {
            lastRequestError = "مدت لمس باید بیشتر از صفر باشد";
            return false;
        }
        if (durationNanos % NANOS_PER_MS != 0) {
            lastRequestError =
                    "مدت لمس باید مضرب دقیق ۱٬۰۰۰٬۰۰۰ نانوثانیه باشد";
            return false;
        }
        long durationMs = durationNanos / NANOS_PER_MS;
        if (durationMs + DISPATCH_LEAD_MS > MAX_GESTURE_DURATION_MS) {
            lastRequestError =
                    "مدت لمس از حداکثر زمان مجاز اندروید بیشتر است";
            return false;
        }
        return true;
    }
    private GestureDescription createTapGesture(
            int x, int y, long startOffsetMs, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, y);
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(
                        path, startOffsetMs, durationMs))
                .build();
    }
    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
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
        if (cycleDeadlineElapsedRealtimeNanos
                <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                < cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                >= cycleDeadlineElapsedRealtimeNanos
                || touchDurationNanos
                > cycleDeadlineElapsedRealtimeNanos
                - targetElapsedRealtimeNanos) {
            lastRequestError =
                    "زمان کلیک یا مدت لمس خارج از محدوده چرخه است";
            return false;
        }
        GestureDescription gesture;
        try {
            gesture = createTapGesture(
                    targetX, targetY, DISPATCH_LEAD_MS,
                    touchDurationNanos / NANOS_PER_MS);
        } catch (Exception e) {
            lastRequestError = "آماده‌سازی ژست کلیک ناموفق بود";
            return false;
        }
        ClickRequest request = new ClickRequest(
                cycleStartElapsedRealtimeNanos,
                targetElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                touchDurationNanos,
                gesture,
                completion,
                1,
                1);
        synchronized (queueLock) {
            if (!serviceReady || schedulerStopping) {
                lastRequestError = "سرویس کلیک متوقف شده است";
                return false;
            }
            pending.offer(request);
        }
        lastRequestError = "";
        wakeScheduler();
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
        if (targets == null || completions == null
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
        long durationMs = touchDurationNanos / NANOS_PER_MS;
        long previousTarget = Long.MIN_VALUE;
        int x = targetX;
        int y = targetY;
        GestureDescription gesture;
        try {
            gesture = createTapGesture(x, y, DISPATCH_LEAD_MS, durationMs);
        } catch (Exception e) {
            lastRequestError = "آماده‌سازی ژست کلیک ناموفق بود";
            return false;
        }
        List<ClickRequest> requests =
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
            if (target < previousTarget) {
                lastRequestError =
                        "ترتیب زمان‌های کلیک در دسته نامعتبر است";
                return false;
            }
            if (touchDurationNanos > deadline - target) {
                lastRequestError =
                        "مدت لمس یکی از کلیک‌ها از مهلت چرخه عبور می‌کند";
                return false;
            }
            previousTarget = target;
            requests.add(new ClickRequest(
                    cycleStart,
                    target,
                    deadline,
                    touchDurationNanos,
                    gesture,
                    completions[i],
                    i + 1,
                    targets.length));
        }
        synchronized (queueLock) {
            if (!serviceReady || schedulerStopping) {
                lastRequestError = "سرویس کلیک متوقف شده است";
                return false;
            }
            pending.addAll(requests);
        }
        lastRequestError = "";
        wakeScheduler();
        return true;
    }
    private void wakeScheduler() {
        Thread worker = schedulerThread;
        if (worker != null) {
            LockSupport.unpark(worker);
        }
    }
    private void dispatchReadyRequest(ClickRequest request) {
        boolean expired = false;
        boolean unavailable = false;
        synchronized (queueLock) {
            if (!dispatching.remove(request)) return;
            if (!serviceReady || schedulerStopping) {
                unavailable = true;
            } else {
                long now = SystemClock.elapsedRealtimeNanos();
                if (request.deadlineNanos - now
                        < DISPATCH_LEAD_NS + request.touchDurationNanos) {
                    expired = true;
                } else {
                    activeRequests.add(request);
                }
            }
        }
        if (unavailable || expired) {
            postCompletion(request, false, true);
            return;
        }
        if (!serviceReady || schedulerStopping) {
            synchronized (queueLock) {
                activeRequests.remove(request);
            }
            postCompletion(request, false, true);
            return;
        }
        boolean accepted = false;
        request.dispatchNanos = SystemClock.elapsedRealtimeNanos();
        try {
            Handler handler = callbackHandler;
            if (handler == null) throw new IllegalStateException("Callback thread unavailable");
            accepted = dispatchGesture(request.gesture, request, handler);
        } catch (Exception ignored) {
            accepted = false;
        }
        request.dispatchAccepted = accepted;
        if (!accepted) {
            synchronized (queueLock) {
                activeRequests.remove(request);
            }
            postCompletion(request, false, false);
        }
    }
    private void completeRequest(
            ClickRequest request,
            boolean success) {
        request.callbackNanos = SystemClock.elapsedRealtimeNanos();
        boolean wasActive;
        synchronized (queueLock) {
            wasActive = activeRequests.remove(request);
        }
        if (wasActive) {
            if (!mainHandler.post(() -> notifyCompletion(request, success, false))) {
                notifyCompletion(request, success, false);
            }
        }
    }
    private void postCompletion(
            ClickRequest request,
            boolean success,
            boolean expired) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyCompletion(request, success, expired);
            return;
        }
        if (!mainHandler.post(
                () -> notifyCompletion(request, success, expired))) {
            notifyCompletion(request, success, expired);
        }
    }
    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired) {
        if (request == null) return;
        if (!request.completionDelivered.compareAndSet(false, true))
            return;
        String result = success ? "SUCCESS" : (expired ? "EXPIRED_OR_CANCELLED" : "FAILED");
        writeBenchmarkRow(request, result);
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
        List<ClickRequest> cancelled = new ArrayList<>();
        synchronized (queueLock) {
            while (!pending.isEmpty()) {
                cancelled.add(pending.poll());
            }
            cancelled.addAll(dispatching);
            dispatching.clear();
        }
        wakeScheduler();
        for (ClickRequest request : cancelled) {
            notifyCompletion(request, false, true);
        }
    }
    private void startBenchmarkWriter() {
        benchmarkSessionId = System.currentTimeMillis();
        File directory = getExternalFilesDir("benchmark");
        if (directory == null) directory = getFilesDir();
        if (!directory.exists() && !directory.mkdirs()) return;
        benchmarkFile = new File(directory, BENCHMARK_FILE_NAME);
        benchmarkWriter = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ClickBenchmarkWriter");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        final File output = benchmarkFile;
        benchmarkWriter.execute(() -> {
            if (output.length() == 0) {
                try (FileWriter writer = new FileWriter(output, true)) {
                    writer.write("session_start_epoch_ms,request_id,cycle_start_elapsed_ns,target_elapsed_ns,dispatch_target_elapsed_ns,deadline_elapsed_ns,enqueue_elapsed_ns,dispatch_elapsed_ns,dispatch_late_ns,dispatch_schedule_late_ns,dispatch_accepted,callback_elapsed_ns,callback_delay_ns,click_index,batch_size,dispatch_lead_ms,result\n");
                    writer.flush();
                } catch (IOException ignored) {
                }
            }
        });
    }
    private void writeBenchmarkRow(ClickRequest request, String result) {
        ExecutorService writer = benchmarkWriter;
        File output = benchmarkFile;
        if (writer == null || output == null || request == null) return;
        long dispatch = request.dispatchNanos;
        long callback = request.callbackNanos;
        long late = dispatch == 0 ? 0 : dispatch - request.targetNanos;
        long scheduleLate = dispatch == 0 ? 0 : dispatch - request.dispatchTargetNanos;
        long callbackDelay = dispatch == 0 || callback == 0 ? 0 : callback - dispatch;
        String row = benchmarkSessionId + "," + request.requestId + "," + request.cycleStartNanos + ","
                + request.targetNanos + "," + request.dispatchTargetNanos + "," + request.deadlineNanos + ","
                + request.enqueueNanos + "," + dispatch + "," + late + "," + scheduleLate + ","
                + request.dispatchAccepted + "," + callback + "," + callbackDelay + ","
                + request.clickIndex + "," + request.batchSize + "," + DISPATCH_LEAD_MS + "," + result + "\n";
        try {
            writer.execute(() -> {
                try (BufferedWriter out = new BufferedWriter(new FileWriter(output, true))) {
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
        if (writer != null) writer.shutdown();
    }
    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }
}
