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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {
    private static final long NS_MS = 1_000_000L, SPIN_NS = 5_000_000L;
    private static final long MAX_DURATION_MS = GestureDescription.getMaxGestureDuration();
    private static final int MAX_STROKES = Math.min(10, GestureDescription.getMaxStrokeCount());
    private static final int MAX_PREPARED_BATCHES = 20;
    private static final String PREFS = "auto_click_target", KEY_X = "target_x",
            KEY_Y = "target_y", LOG_FILE = "PrecisionClickTiming_v3.txt";
    private static final int DEFAULT_X = 350, DEFAULT_Y = 700;
    private static volatile AutoClickService instance;

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile HandlerThread callbackThread;
    private volatile Handler callbackHandler;
    private final Object lock = new Object();
    private final ArrayDeque<Request> pending = new ArrayDeque<>(128);
    private final ArrayList<Request> active = new ArrayList<>(64);
    private final LinkedBlockingQueue<Timing> logs = new LinkedBlockingQueue<>();
    private volatile boolean ready, stopping, writerStopping;
    private volatile int x = DEFAULT_X, y = DEFAULT_Y;
    private volatile long generation, lastCallbackElapsed;
    private volatile Thread scheduler, writerThread;
    private volatile ExecutorService preparer;
    private volatile String logPath = "فایل گزارش هنوز ساخته نشده است";
    private final ArrayDeque<PreparedBatch> preparedBatches =
            new ArrayDeque<>(MAX_PREPARED_BATCHES);
    private boolean dispatching;
    private SharedPreferences preferences;

    private static final class Request {
        final long target, deadline, durationMs, durationNs, generation;
        final int x, y;
        final AtomicBoolean recorded = new AtomicBoolean(), delivered = new AtomicBoolean();
        volatile Path path;
        volatile long offsetMs, prepareStart, prepareEnd;
        volatile long batchBuildStart, batchReady, workerStart;
        volatile long dispatchStart, dispatchDuration, completed, callbackElapsed;
        volatile long previousCallbackElapsed, handoffDelay;
        volatile boolean accepted;
        volatile String callbackResult = "NOT_DISPATCHED";
        volatile GestureCompletion completion;

        Request(long target, long deadline, long durationMs, int x, int y,
                long generation, GestureCompletion completion) {
            this.target = target;
            this.deadline = deadline;
            this.durationMs = durationMs;
            this.durationNs = durationMs * NS_MS;
            this.x = x;
            this.y = y;
            this.generation = generation;
            this.completion = completion;
        }

        void prepare() {
            if (path != null) return;
            prepareStart = SystemClock.elapsedRealtimeNanos();
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(x, y);
            path = p;
            prepareEnd = SystemClock.elapsedRealtimeNanos();
        }
    }

    private static final class PreparedBatch {
        final ArrayList<Request> requests;
        final GestureDescription gesture;
        final long baseTarget;

        PreparedBatch(ArrayList<Request> requests, GestureDescription gesture) {
            this.requests = requests;
            this.gesture = gesture;
            baseTarget = requests.get(0).target;
        }
    }

    private static final class Batch extends GestureResultCallback {
        final AutoClickService owner;
        final ArrayList<Request> requests;
        private boolean returned, accepted, received, success, handled;
        private long callbackTime;

        Batch(AutoClickService owner, ArrayList<Request> requests) {
            this.owner = owner;
            this.requests = requests;
        }

        @Override
        public void onCompleted(GestureDescription g) {
            receive(true);
        }

        @Override
        public void onCancelled(GestureDescription g) {
            receive(false);
        }

        private void receive(boolean result) {
            boolean process;
            synchronized (this) {
                if (handled || received) return;
                received = true;
                success = result;
                callbackTime = SystemClock.elapsedRealtimeNanos();
                process = returned && accepted;
                if (process) handled = true;
            }
            if (process) owner.finishBatch(this, result, callbackTime);
        }

        void onDispatchReturned(boolean result) {
            boolean process;
            synchronized (this) {
                returned = true;
                accepted = result;
                process = result && received && !handled;
                if (process) handled = true;
            }
            if (process) owner.finishBatch(this, success, callbackTime);
        }
    }

    private static final class Timing {
        final long target, prepareStart, prepareEnd;
        final long batchBuildStart, batchReady, workerStart;
        final long dispatchStart, dispatchDuration, completed, callbackElapsed;
        final long previousCallbackElapsed, handoffDelay;
        final int x, y;
        final boolean accepted, success, expired;
        final String callback;

        Timing(Request r, boolean success, boolean expired) {
            target = r.target;
            prepareStart = r.prepareStart;
            prepareEnd = r.prepareEnd;
            batchBuildStart = r.batchBuildStart;
            batchReady = r.batchReady;
            workerStart = r.workerStart;
            dispatchStart = r.dispatchStart;
            dispatchDuration = r.dispatchDuration;
            completed = r.completed;
            callbackElapsed = r.callbackElapsed;
            previousCallbackElapsed = r.previousCallbackElapsed;
            handoffDelay = r.handoffDelay;
            x = r.x;
            y = r.y;
            accepted = r.accepted;
            this.success = success;
            this.expired = expired;
            callback = r.callbackResult;
        }

        String csv() {
            long prepare = prepareStart == 0 || prepareEnd == 0
                    ? 0 : prepareEnd - prepareStart;
            long build = batchBuildStart == 0 || batchReady == 0
                    ? 0 : batchReady - batchBuildStart;
            long readyToWorker = batchReady == 0 || workerStart == 0
                    ? 0 : workerStart - batchReady;
            long targetWait = batchReady == 0
                    ? 0 : Math.max(0, target - batchReady);
            long previousWait = batchReady == 0 || previousCallbackElapsed == 0
                    ? 0 : Math.max(0, previousCallbackElapsed - batchReady);
            long dispatchDelta = dispatchStart == 0 ? 0 : dispatchStart - target;
            long pipeline = dispatchStart == 0 || workerStart == 0
                    ? 0 : dispatchStart - workerStart;
            long completionDelay = completed == 0 ? 0 : completed - target;
            long dispatchToCallback = dispatchStart == 0 || callbackElapsed == 0
                    ? 0 : callbackElapsed - dispatchStart;
            String result = expired ? "EXPIRED" : success ? "SUCCESS" : "FAILED";

            return target + "," + prepareStart + "," + prepareEnd + "," + prepare + ","
                    + batchBuildStart + "," + batchReady + "," + build + ","
                    + workerStart + "," + readyToWorker + "," + targetWait + ","
                    + previousWait + "," + dispatchStart + "," + dispatchDelta + ","
                    + pipeline + "," + dispatchDuration + "," + completed + ","
                    + completionDelay + "," + dispatchToCallback + "," + accepted + ","
                    + result + "," + callback + "," + x + "," + y + ","
                    + callbackElapsed + "," + previousCallbackElapsed + "," + handoffDelay;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        x = preferences.getInt(KEY_X, DEFAULT_X);
        y = preferences.getInt(KEY_Y, DEFAULT_Y);
        stopping = false;
        ready = true;
        instance = this;
        startWriter();
        startPreparer();
        startCallbackThread();
        startScheduler();
    }

    private void startCallbackThread() {
        HandlerThread old = callbackThread;
        if (old != null && old.isAlive()) return;
        HandlerThread thread = new HandlerThread(
                "PrecisionGestureCallback", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        callbackThread = thread;
        callbackHandler = new Handler(thread.getLooper());
    }

    private void startWriter() {
        if (writerThread != null && writerThread.isAlive()) return;
        writerStopping = false;
        writerThread = new Thread(this::writerLoop, "PrecisionTimingFileWriter");
        writerThread.start();
    }

    private void writerLoop() {
        BufferedWriter out = null;
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) {
                logPath = "خطا: پوشه گزارش ساخته نشد";
                return;
            }
            File file = new File(dir, LOG_FILE);
            boolean header = !file.exists() || file.length() == 0;
            out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8));
            logPath = file.getAbsolutePath();

            if (header) {
                out.write("target_elapsed_ns,prepare_start_elapsed_ns,prepare_end_elapsed_ns,"
                        + "prepare_duration_ns,batch_build_start_elapsed_ns,batch_ready_elapsed_ns,"
                        + "batch_build_duration_ns,dispatch_worker_start_elapsed_ns,"
                        + "batch_ready_to_worker_ns,target_wait_ns,previous_callback_wait_ns,"
                        + "dispatch_start_elapsed_ns,dispatch_start_delta_ns,"
                        + "dispatch_pipeline_delay_ns,dispatch_duration_ns,"
                        + "completion_elapsed_ns,completion_delay_ns,dispatch_to_callback_ns,"
                        + "accepted,result,callback_result,x,y,callback_elapsed_ns,"
                        + "previous_callback_elapsed_ns,handoff_delay_ns");
                out.newLine();
                out.flush();
            }

            while (!writerStopping || !logs.isEmpty()) {
                Timing item;
                try {
                    item = logs.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    continue;
                }
                if (item == null) continue;
                out.write(item.csv());
                out.newLine();
                out.flush();
            }
        } catch (IOException e) {
            logPath = "خطای نوشتن گزارش: " + e.getMessage();
        } finally {
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (IOException ignored) { }
            }
        }
    }

    private void record(Request r, boolean success, boolean expired) {
        if (r != null && r.recorded.compareAndSet(false, true))
            logs.offer(new Timing(r, success, expired));
    }

    private void startPreparer() {
        if (preparer != null && !preparer.isShutdown()) return;
        preparer = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Exception ignored) { }
            task.run();
        }, "PrecisionGesturePreparation"));
    }

    private void prepare(Request r) {
        if (!current(r)) return;
        try {
            r.prepare();
        } catch (Exception e) {
            boolean removed;
            synchronized (lock) {
                removed = pending.remove(r);
            }
            if (removed) {
                r.callbackResult = "PREPARATION_FAILED:" + e.getClass().getSimpleName();
                postFinish(r, false, false);
            }
        }
        unparkScheduler();
    }

    private void startScheduler() {
        if (scheduler != null && scheduler.isAlive()) return;
        scheduler = new Thread(this::schedulerLoop, "PrecisionClickNanoScheduler");
        scheduler.start();
    }

    private void schedulerLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) { }

        while (!stopping) {
            PreparedBatch headBatch;
            Request first;
            boolean busy, canBuild;
            ArrayList<Request> stale = new ArrayList<>();

            synchronized (lock) {
                while (!pending.isEmpty() && pending.peekFirst().generation != generation)
                    stale.add(pending.pollFirst());
                while (!preparedBatches.isEmpty()
                        && preparedBatches.peekFirst().requests.get(0).generation != generation) {
                    PreparedBatch old = preparedBatches.pollFirst();
                    stale.addAll(old.requests);
                }
                headBatch = preparedBatches.peekFirst();
                first = pending.peekFirst();
                busy = !active.isEmpty() || dispatching;
                canBuild = preparedBatches.size() < MAX_PREPARED_BATCHES;
            }

            for (Request r : stale) {
                r.callbackResult = "STALE_GENERATION";
                postFinish(r, false, true);
            }
            if (stopping) break;

            if (headBatch != null && !busy
                    && headBatch.baseTarget <= SystemClock.elapsedRealtimeNanos()) {
                dispatchPrepared();
                continue;
            }

            // Build future batches while the current Gesture is still running.
            if (canBuild && first != null && first.path != null) {
                if (hasUnpreparedBatchMember(first)) {
                    LockSupport.parkNanos(this, 100_000L);
                    continue;
                }
                ArrayList<Request> requests = collect(first);
                if (!requests.isEmpty()) {
                    long buildStart = SystemClock.elapsedRealtimeNanos();
                    for (Request r : requests) r.batchBuildStart = buildStart;
                    try {
                        GestureDescription gesture = buildGesture(requests);
                        long readyTime = SystemClock.elapsedRealtimeNanos();
                        synchronized (lock) {
                            if (!stopping && preparedBatches.size() < MAX_PREPARED_BATCHES
                                    && current(requests.get(0))) {
                                for (Request r : requests) r.batchReady = readyTime;
                                preparedBatches.addLast(new PreparedBatch(requests, gesture));
                            } else {
                                requeueCurrentInOrder(requests);
                            }
                        }
                    } catch (Exception e) {
                        finishWithoutDispatch(requests,
                                "BATCH_BUILD_FAILED:" + e.getClass().getSimpleName(), false);
                    }
                    continue;
                }
            }

            if (headBatch != null && !busy) {
                long remaining = headBatch.baseTarget - SystemClock.elapsedRealtimeNanos();
                if (remaining > 0L) LockSupport.parkNanos(this, remaining);
                continue;
            }

            if (first == null || first.path == null || !canBuild || busy) {
                LockSupport.park(this);
                continue;
            }
            LockSupport.park(this);
        }
    }
    private void requeueCurrentInOrder(List<Request> requests) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            Request r = requests.get(i);
            if (current(r) && !r.delivered.get()) pending.addFirst(r);
        }
    }

    private boolean hasUnpreparedBatchMember(Request first) {
        synchronized (lock) {
            if (pending.peekFirst() != first || !current(first)) return false;
            long base = first.target, gen = first.generation;
            int count = 0;
            for (Request r : pending) {
                if (count >= MAX_STROKES) break;
                if (r.generation != gen || r.target < base) break;
                long delta = r.target - base;
                if (delta % NS_MS != 0) break;
                long offset = delta / NS_MS;
                if (offset > MAX_DURATION_MS
                        || r.durationMs > MAX_DURATION_MS - offset) break;
                if (r.path == null) return true;
                count++;
            }
        }
        return false;
    }

    private ArrayList<Request> collect(Request first) {
        ArrayList<Request> result = new ArrayList<>(MAX_STROKES);
        synchronized (lock) {
            if (pending.peekFirst() != first || !current(first)) return result;
            long base = first.target, gen = first.generation;
            while (!pending.isEmpty() && result.size() < MAX_STROKES) {
                Request r = pending.peekFirst();
                if (r.generation != gen || r.path == null || r.target < base) break;
                long delta = r.target - base;
                if (delta % NS_MS != 0) break;
                long offset = delta / NS_MS;
                if (offset > MAX_DURATION_MS
                        || r.durationMs > MAX_DURATION_MS - offset) break;
                r.offsetMs = offset;
                result.add(pending.pollFirst());
            }
        }
        return result;
    }

    private boolean current(Request r) {
        return r != null && r.generation == generation && ready && !stopping;
    }

    private GestureDescription buildGesture(List<Request> requests) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (Request r : requests) {
            if (r.path == null) throw new IllegalStateException("PATH_NOT_PREPARED");
            builder.addStroke(new GestureDescription.StrokeDescription(
                    r.path, r.offsetMs, r.durationMs));
        }
        return builder.build();
    }

    private boolean fitsDeadline(Request r, long start) {
        if (start >= r.deadline) return false;
        long offset = r.offsetMs * NS_MS, remaining = r.deadline - start;
        return offset <= remaining && r.durationNs <= remaining - offset;
    }

    private boolean rebaseForDispatch(Request r, long now) {
        long delta = r.target - now;
        if (delta <= 0) {
            r.callbackResult = "TARGET_MISSED_BEFORE_DISPATCH";
            return false;
        }
        r.offsetMs = delta / NS_MS + (delta % NS_MS == 0 ? 0 : 1);
        if (r.offsetMs > MAX_DURATION_MS || r.durationMs > MAX_DURATION_MS - r.offsetMs
                || !fitsDeadline(r, now)) {
            r.callbackResult = "DEADLINE_AFTER_REBASE";
            return false;
        }
        return true;
    }

    private void dispatchPrepared() {
        PreparedBatch batch;
        synchronized (lock) {
            if (preparedBatches.isEmpty() || !active.isEmpty() || dispatching || stopping
                    || SystemClock.elapsedRealtimeNanos()
                    < preparedBatches.peekFirst().baseTarget) return;
            batch = preparedBatches.pollFirst();
            dispatching = true;
        }

        long workerStart = SystemClock.elapsedRealtimeNanos();
        ArrayList<Request> valid = new ArrayList<>(), expired = new ArrayList<>();
        boolean changed = false;
        for (Request r : batch.requests) {
            r.workerStart = workerStart;
            long old = r.offsetMs;
            if (current(r) && rebaseForDispatch(r, workerStart)) {
                valid.add(r);
                changed |= old != r.offsetMs;
            } else {
                expired.add(r);
            }
        }
        for (Request r : expired) postFinish(r, false, true);
        if (valid.isEmpty()) {
            clearDispatching();
            unparkScheduler();
            return;
        }

        GestureDescription gesture;
        try {
            gesture = changed ? buildGesture(valid) : batch.gesture;
        } catch (Exception e) {
            clearDispatching();
            finishWithoutDispatch(valid,
                    "BATCH_REBUILD_FAILED:" + e.getClass().getSimpleName(), false);
            unparkScheduler();
            return;
        }

        while (!valid.isEmpty() && !stopping) {
            ArrayList<Request> invalid = new ArrayList<>();
            Batch callback = null;
            boolean aborted = false, accepted = false, exception = false, retry = false;
            long start = 0, end = 0, previousCallback = 0;
            String exceptionName = "", buildError = "";

            synchronized (lock) {
                if (stopping || !current(valid.get(0)) || !active.isEmpty()) {
                    dispatching = false;
                    aborted = true;
                } else {
                    long now = SystemClock.elapsedRealtimeNanos();
                    for (Request r : valid) {
                        long old = r.offsetMs;
                        if (!current(r) || !rebaseForDispatch(r, now)) {
                            invalid.add(r);
                        } else {
                            changed |= old != r.offsetMs;
                        }
                    }
                    if (!invalid.isEmpty()) {
                        valid.removeAll(invalid);
                        changed = true;
                        retry = true;
                    }
                    if (valid.isEmpty()) {
                        dispatching = false;
                    } else if (!retry) {
                        try {
                            if (changed) gesture = buildGesture(valid);
                        } catch (Exception e) {
                            buildError = e.getClass().getSimpleName();
                        }

                        if (!buildError.isEmpty()) {
                            dispatching = false;
                        } else {
                            start = SystemClock.elapsedRealtimeNanos();
                            for (Request r : valid) {
                                if (r.target <= start) {
                                    r.callbackResult = "TARGET_MISSED_DURING_REBUILD";
                                    invalid.add(r);
                                } else if (!fitsDeadline(r, start)) {
                                    r.callbackResult = "DEADLINE_AFTER_REBUILD";
                                    invalid.add(r);
                                }
                            }

                            if (!invalid.isEmpty()) {
                                valid.removeAll(invalid);
                                changed = true;
                                retry = true;
                                if (valid.isEmpty()) dispatching = false;
                            } else {
                                previousCallback = lastCallbackElapsed;
                                for (Request r : valid) {
                                    r.dispatchStart = start;
                                    r.previousCallbackElapsed = previousCallback;
                                    r.handoffDelay = previousCallback == 0
                                            ? 0 : Math.max(0, start - previousCallback);
                                    r.callbackResult = "WAITING_FOR_CALLBACK";
                                }

                                callback = new Batch(this, valid);
                                active.addAll(valid);
                                try {
                                    Handler callbackTarget = callbackHandler;
                                    accepted = callbackTarget != null
                                            && dispatchGesture(gesture, callback, callbackTarget);
                                } catch (Exception e) {
                                    exception = true;
                                    exceptionName = e.getClass().getSimpleName();
                                }

                                end = SystemClock.elapsedRealtimeNanos();
                                for (Request r : valid) {
                                    r.dispatchDuration = end - start;
                                    r.accepted = accepted;
                                }
                                if (!accepted || exception) active.removeAll(valid);
                                dispatching = false;
                            }
                        }
                    }
                }
            }

            for (Request r : invalid) postFinish(r, false, true);
            if (aborted) {
                finishWithoutDispatch(valid, "CANCELLED_BEFORE_REGISTRATION", true);
                unparkScheduler();
                return;
            }
            if (!buildError.isEmpty()) {
                clearDispatching();
                finishWithoutDispatch(valid, "BATCH_REBUILD_FAILED:" + buildError, false);
                unparkScheduler();
                return;
            }
            if (retry) {
                if (valid.isEmpty()) {
                    clearDispatching();
                    unparkScheduler();
                    return;
                }
                continue;
            }
            if (exception || !accepted) {
                String reason = exception
                        ? "DISPATCH_EXCEPTION:" + exceptionName : "API_RETURNED_FALSE";
                for (Request r : valid) r.callbackResult = reason;
                finishWithoutDispatch(valid, reason, false);
                unparkScheduler();
                return;
            }
            callback.onDispatchReturned(true);
            unparkScheduler();
            return;
        }

        clearDispatching();
        if (!valid.isEmpty()) finishWithoutDispatch(valid, "SERVICE_STOPPING", true);
        unparkScheduler();
    }

    private void clearDispatching() {
        synchronized (lock) {
            dispatching = false;
        }
    }

    private void finishWithoutDispatch(List<Request> requests,
            String reason, boolean expired) {
        for (Request r : requests) {
            r.callbackResult = reason;
            postFinish(r, false, expired);
        }
    }

    private void finishBatch(Batch batch, boolean success, long callbackTime) {
        synchronized (lock) {
            active.removeAll(batch.requests);
        }
        lastCallbackElapsed = callbackTime;
        for (Request r : batch.requests) {
            r.completed = callbackTime;
            r.callbackElapsed = callbackTime;
            r.callbackResult = success ? "ON_COMPLETED" : "ON_CANCELLED";
        }
        // فقط زمان‌بند اختصاصی اجازه شروع Gesture بعدی را دارد.
        unparkScheduler();
        for (Request r : batch.requests) postFinish(r, success, false);
    }

    private void postFinish(Request r, boolean success, boolean expired) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finish(r, success, expired);
        } else {
            main.post(() -> finish(r, success, expired));
        }
    }

    private void finish(Request r, boolean success, boolean expired) {
        if (r == null || !r.delivered.compareAndSet(false, true)) return;
        if (r.completed == 0) r.completed = SystemClock.elapsedRealtimeNanos();
        record(r, success, expired);
        GestureCompletion callback = r.completion;
        r.completion = null;
        if (callback != null) {
            try {
                callback.onFinished(success, expired);
            } catch (Exception ignored) { }
        }
    }

    public boolean performClickAtTarget(long targetNanos, long cycleStartNanos,
            long deadlineNanos, long touchDurationNanos, GestureCompletion completion) {
        if (!ready || stopping || completion == null || touchDurationNanos < 0
                || deadlineNanos <= cycleStartNanos || targetNanos < cycleStartNanos
                || targetNanos >= deadlineNanos) return false;

        long durationMs = toDurationMs(touchDurationNanos);
        if (durationMs > MAX_DURATION_MS) return false;
        long durationNs = durationMs * NS_MS;
        if (durationNs > deadlineNanos - targetNanos) return false;

        synchronized (lock) {
            if (!ready || stopping || preparer == null || preparer.isShutdown())
                return false;
            Request r = new Request(targetNanos, deadlineNanos, durationMs,
                    x, y, generation, completion);
            pending.offerLast(r);
            try {
                preparer.execute(() -> prepare(r));
            } catch (Exception e) {
                pending.remove(r);
                return false;
            }
        }
        unparkScheduler();
        return true;
    }

    private static long toDurationMs(long nanos) {
        if (nanos <= 0) return 1;
        return Math.max(1, nanos / NS_MS + (nanos % NS_MS == 0 ? 0 : 1));
    }

    private void unparkScheduler() {
        Thread t = scheduler;
        if (t != null) LockSupport.unpark(t);
    }

    public void cancelPendingScheduledClick() {
        cancelPending();
    }

    private void cancelPending() {
        List<Request> cancelled = new ArrayList<>();
        synchronized (lock) {
            generation++;
            while (!pending.isEmpty()) cancelled.add(pending.pollFirst());
            while (!preparedBatches.isEmpty())
                cancelled.addAll(preparedBatches.pollFirst().requests);
        }
        unparkScheduler();
        for (Request r : cancelled) {
            r.callbackResult = "CANCELLED_BEFORE_DISPATCH";
            postFinish(r, false, true);
        }
    }

    @Override
    public void onInterrupt() {
        cancelPending();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onDestroy() {
        ready = false;
        stopping = true;
        Thread t = scheduler;
        scheduler = null;
        if (t != null) {
            LockSupport.unpark(t);
            t.interrupt();
        }

        ExecutorService executor = preparer;
        preparer = null;
        if (executor != null) executor.shutdownNow();

        List<Request> waiting = new ArrayList<>();
        List<Request> running = new ArrayList<>();
        synchronized (lock) {
            generation++;
            while (!pending.isEmpty()) waiting.add(pending.pollFirst());
            while (!preparedBatches.isEmpty())
                waiting.addAll(preparedBatches.pollFirst().requests);
            running.addAll(active);
            active.clear();
        }

        for (Request r : waiting) {
            r.callbackResult = "SERVICE_DESTROYED_PENDING";
            finish(r, false, true);
        }
        for (Request r : running) {
            r.callbackResult = "SERVICE_DESTROYED_ACTIVE";
            finish(r, false, false);
        }

        writerStopping = true;
        if (writerThread != null) writerThread.interrupt();

        HandlerThread callback = callbackThread;
        callbackThread = null;
        callbackHandler = null;
        if (callback != null) callback.quitSafely();

        if (instance == this) instance = null;
        preferences = null;
        super.onDestroy();
    }

    public static AutoClickService getInstance() { return instance; }
    public boolean isServiceReady() { return ready; }
    public boolean isGestureInProgress() {
        synchronized (lock) { return !active.isEmpty(); }
    }
    public int getTargetX() { return x; }
    public int getTargetY() { return y; }
    public String getTimingFilePath() { return logPath; }

    public void setTarget(int newX, int newY) {
        int nx = Math.max(0, newX), ny = Math.max(0, newY);
        runOnMain(() -> {
            if (!ready || (x == nx && y == ny)) return;
            x = nx;
            y = ny;
            SharedPreferences p = preferences;
            if (p != null)
                p.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply();
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            main.post(action);
        }
    }
}