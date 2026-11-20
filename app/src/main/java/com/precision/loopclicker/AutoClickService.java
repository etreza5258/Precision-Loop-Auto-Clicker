
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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {
    private static final long NS_MS = 1_000_000L;
    private static final long SPIN_NS = 5_000_000L;
    private static final long MAX_DURATION_MS = GestureDescription.getMaxGestureDuration();
    private static final int MAX_BATCH_REQUESTS = 50;
    private static final int MAX_STROKES =
            Math.min(MAX_BATCH_REQUESTS, GestureDescription.getMaxStrokeCount());
    private static final String PREFS = "auto_click_target";
    private static final String KEY_X = "target_x";
    private static final String KEY_Y = "target_y";
    private static final String LOG_FILE = "PrecisionClickTiming.txt";
    private static final int DEFAULT_X = 350;
    private static final int DEFAULT_Y = 700;

    private static volatile AutoClickService instance;

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayDeque<Request> pending = new ArrayDeque<>(128);
    private final ArrayList<Request> active = new ArrayList<>(64);
    private final LinkedBlockingQueue<Timing> logs = new LinkedBlockingQueue<>();

    private volatile boolean ready, stopping, writerStopping;
    private volatile int x = DEFAULT_X, y = DEFAULT_Y;
    private volatile long generation;
    private volatile Thread scheduler, writerThread;
    private volatile ExecutorService preparer;
    private volatile String logPath = "فایل گزارش هنوز ساخته نشده است";

    private PreparedBatch prepared;
    private boolean dispatching;
    private volatile long lastCallbackElapsed;
    private SharedPreferences preferences;

    private static final class Request {
        final long target, deadline, durationMs, durationNs, generation;
        final int x, y;
        final AtomicBoolean recorded = new AtomicBoolean();
        final AtomicBoolean delivered = new AtomicBoolean();

        volatile Path path;
        volatile long offsetMs, prepareStart, prepareEnd, enqueue;
        volatile long workerStart, dispatchStart, dispatchDuration;
        volatile long completed, callbackElapsed, handoffDelay;
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
            this.baseTarget = requests.get(0).target;
        }
    }

    private static final class Batch extends AccessibilityService.GestureResultCallback {
        final AutoClickService owner;
        final ArrayList<Request> requests;
        final AtomicBoolean finished = new AtomicBoolean();

        Batch(AutoClickService owner, ArrayList<Request> requests) {
            this.owner = owner;
            this.requests = requests;
        }

        @Override
        public void onCompleted(GestureDescription gesture) {
            if (finished.compareAndSet(false, true))
                owner.finishBatch(this, true);
        }

        @Override
        public void onCancelled(GestureDescription gesture) {
            if (finished.compareAndSet(false, true))
                owner.finishBatch(this, false);
        }
    }

    private static final class Timing {
        final long target, prepareStart, prepareEnd, enqueue, workerStart;
        final long dispatchStart, dispatchDuration, completed;
        final long callbackElapsed, handoffDelay;
        final int x, y;
        final boolean accepted, success, expired;
        final String callback;

        Timing(Request r, boolean success, boolean expired) {
            target = r.target;
            prepareStart = r.prepareStart;
            prepareEnd = r.prepareEnd;
            enqueue = r.enqueue;
            workerStart = r.workerStart;
            dispatchStart = r.dispatchStart;
            dispatchDuration = r.dispatchDuration;
            completed = r.completed;
            callbackElapsed = r.callbackElapsed;
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
            long schedulerDelay = enqueue == 0 ? 0 : enqueue - target;
            long queueDelay = workerStart == 0 || enqueue == 0
                    ? 0 : workerStart - enqueue;
            long dispatchDelay = dispatchStart == 0 ? 0 : dispatchStart - target;
            long completionDelay = completed == 0 ? 0 : completed - target;
            String result = expired ? "EXPIRED" : success ? "SUCCESS" : "FAILED";

            return target + "," + prepareStart + "," + prepareEnd + "," + prepare + ","
                    + enqueue + "," + schedulerDelay + "," + workerStart + "," + queueDelay + ","
                    + dispatchStart + "," + dispatchDelay + "," + dispatchDuration + ","
                    + completed + "," + completionDelay + "," + accepted + "," + result + ","
                    + callback + "," + x + "," + y + "," + callbackElapsed + ","
                    + handoffDelay;
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
        startScheduler();
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
                        + "prepare_duration_ns,scheduler_enqueue_elapsed_ns,scheduler_delay_ns,"
                        + "dispatch_worker_start_elapsed_ns,dispatch_queue_delay_ns,"
                        + "dispatch_start_elapsed_ns,dispatch_delay_ns,dispatch_duration_ns,"
                        + "completion_elapsed_ns,completion_delay_ns,accepted,result,"
                        + "callback_result,x,y,previous_callback_elapsed_ns,handoff_delay_ns");
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
            PreparedBatch readyBatch;
            Request first;
            boolean busy, isDispatching;

            synchronized (lock) {
                readyBatch = prepared;
                first = pending.peekFirst();
                busy = !active.isEmpty();
                isDispatching = dispatching;
            }

            if (stopping) break;

            if (readyBatch != null) {
                if (!busy && !isDispatching) {
                    long remaining = readyBatch.baseTarget
                            - SystemClock.elapsedRealtimeNanos();
                    if (remaining > SPIN_NS) {
                        LockSupport.parkNanos(this, remaining - SPIN_NS);
                    } else {
                        while (!stopping && ready && readyBatch.baseTarget
                                > SystemClock.elapsedRealtimeNanos()) { }
                        if (!stopping) dispatchPrepared();
                    }
                } else {
                    LockSupport.park(this);
                }
                continue;
            }

            if (first == null || first.path == null) {
                LockSupport.park(this);
                continue;
            }

            ArrayList<Request> requests = collect(first);
            if (requests.isEmpty()) continue;

            long now = SystemClock.elapsedRealtimeNanos();
            for (Request r : requests) r.enqueue = now;

            try {
                GestureDescription gesture = buildGesture(requests);
                synchronized (lock) {
                    if (!stopping && prepared == null
                            && requests.get(0).generation == generation) {
                        prepared = new PreparedBatch(requests, gesture);
                    } else {
                        for (Request r : requests)
                            if (!r.delivered.get()) pending.addFirst(r);
                    }
                }
            } catch (Exception e) {
                finishWithoutDispatch(requests,
                        "BATCH_BUILD_FAILED:" + e.getClass().getSimpleName(), false);
            }

            unparkScheduler();
        }
    }

    private ArrayList<Request> collect(Request first) {
        ArrayList<Request> result = new ArrayList<>(MAX_STROKES);
        synchronized (lock) {
            if (pending.peekFirst() != first || !current(first)) return result;
            long base = first.target;
            long gen = first.generation;

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

    private boolean fitsDeadline(Request r, long dispatchStart) {
        if (dispatchStart >= r.deadline) return false;
        long offsetNs = r.offsetMs * NS_MS;
        long remaining = r.deadline - dispatchStart;
        if (offsetNs > remaining) return false;
        return r.durationNs <= remaining - offsetNs;
    }

    private void dispatchPrepared() {
        PreparedBatch batch;
        synchronized (lock) {
            if (prepared == null || !active.isEmpty() || dispatching || stopping)
                return;

            if (SystemClock.elapsedRealtimeNanos() < prepared.baseTarget)
                return;

            batch = prepared;
            prepared = null;
            dispatching = true;
        }

        long workerStart = SystemClock.elapsedRealtimeNanos();
        ArrayList<Request> valid = new ArrayList<>();
        ArrayList<Request> expired = new ArrayList<>();

        for (Request r : batch.requests) {
            r.workerStart = workerStart;
            if (current(r) && fitsDeadline(r, workerStart)) {
                valid.add(r);
            } else {
                r.callbackResult = "DEADLINE_OR_CANCEL_BEFORE_API";
                expired.add(r);
            }
        }

        for (Request r : expired) postFinish(r, false, true);

        if (valid.isEmpty()) {
            synchronized (lock) {
                dispatching = false;
            }
            unparkScheduler();
            return;
        }

        GestureDescription gesture;
        try {
            gesture = valid.size() == batch.requests.size()
                    ? batch.gesture : buildGesture(valid);
        } catch (Exception e) {
            synchronized (lock) {
                dispatching = false;
            }
            finishWithoutDispatch(valid,
                    "BATCH_REBUILD_FAILED:" + e.getClass().getSimpleName(), false);
            unparkScheduler();
            return;
        }

        long start = SystemClock.elapsedRealtimeNanos();
        ArrayList<Request> finalExpired = new ArrayList<>();

        for (Request r : valid) {
            if (!current(r) || !fitsDeadline(r, start)) {
                r.callbackResult = "FINAL_DEADLINE_CHECK_FAILED";
                finalExpired.add(r);
            }
        }

        if (!finalExpired.isEmpty()) {
            valid.removeAll(finalExpired);
            for (Request r : finalExpired) postFinish(r, false, true);

            if (valid.isEmpty()) {
                synchronized (lock) {
                    dispatching = false;
                }
                unparkScheduler();
                return;
            }

            try {
                gesture = buildGesture(valid);
            } catch (Exception e) {
                synchronized (lock) {
                    dispatching = false;
                }
                finishWithoutDispatch(valid, "FINAL_BUILD_FAILED", false);
                unparkScheduler();
                return;
            }

            start = SystemClock.elapsedRealtimeNanos();
            for (Request r : valid) {
                if (!current(r) || !fitsDeadline(r, start)) {
                    synchronized (lock) {
                        dispatching = false;
                    }
                    finishWithoutDispatch(valid, "FINAL_DEADLINE_CHECK_FAILED", true);
                    unparkScheduler();
                    return;
                }
            }
        }

        long previousCallback = lastCallbackElapsed;
        for (Request r : valid) {
            r.dispatchStart = start;
            r.callbackResult = "WAITING_FOR_CALLBACK";
            r.handoffDelay = previousCallback == 0 ? 0 : start - previousCallback;
        }

        synchronized (lock) {
            if (stopping || !current(valid.get(0)) || !active.isEmpty()) {
                dispatching = false;
                finishWithoutDispatch(valid, "CANCELLED_BEFORE_REGISTRATION", true);
                unparkScheduler();
                return;
            }
            active.addAll(valid);
        }

        boolean accepted;
        try {
            accepted = dispatchGesture(gesture, new Batch(this, valid), main);
        } catch (Exception e) {
            long end = SystemClock.elapsedRealtimeNanos();
            for (Request r : valid) {
                r.dispatchDuration = end - start;
                r.callbackResult = "DISPATCH_EXCEPTION:"
                        + e.getClass().getSimpleName();
            }

            synchronized (lock) {
                active.removeAll(valid);
                dispatching = false;
            }

            for (Request r : valid) postFinish(r, false, false);
            unparkScheduler();
            return;
        }

        long end = SystemClock.elapsedRealtimeNanos();
        for (Request r : valid) {
            r.dispatchDuration = end - start;
            r.accepted = accepted;
        }

        synchronized (lock) {
            dispatching = false;
            if (!accepted) active.removeAll(valid);
        }

        if (!accepted) {
            for (Request r : valid) {
                r.callbackResult = "API_RETURNED_FALSE";
                postFinish(r, false, false);
            }
            unparkScheduler();
        }
    }

    private void finishWithoutDispatch(List<Request> requests,
                                       String reason, boolean expired) {
        for (Request r : requests) {
            r.callbackResult = reason;
            postFinish(r, false, expired);
        }
    }

    private void finishBatch(Batch batch, boolean success) {
        long now = SystemClock.elapsedRealtimeNanos();

        synchronized (lock) {
            active.removeAll(batch.requests);
        }

        lastCallbackElapsed = now;

        for (Request r : batch.requests) {
            r.completed = now;
            r.callbackElapsed = now;
            r.callbackResult = success ? "ON_COMPLETED" : "ON_CANCELLED";
            finish(r, success, false);
        }

        tryDispatchAfterCallback();
        unparkScheduler();
    }

    private void tryDispatchAfterCallback() {
        PreparedBatch batch;
        synchronized (lock) {
            if (!active.isEmpty() || dispatching || prepared == null || stopping)
                return;
            batch = prepared;
        }

        if (SystemClock.elapsedRealtimeNanos() >= batch.baseTarget)
            dispatchPrepared();
    }

    private void postFinish(Request r, boolean success, boolean expired) {
        if (Looper.myLooper() == Looper.getMainLooper())
            finish(r, success, expired);
        else
            main.post(() -> finish(r, success, expired));
    }

    private void finish(Request r, boolean success, boolean expired) {
        if (r == null || !r.delivered.compareAndSet(false, true)) return;

        if (r.completed == 0)
            r.completed = SystemClock.elapsedRealtimeNanos();

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
            long deadlineNanos, long touchDurationNanos,
            GestureCompletion completion) {
        if (!ready || stopping || completion == null || touchDurationNanos < 0
                || deadlineNanos <= cycleStartNanos
                || targetNanos < cycleStartNanos || targetNanos >= deadlineNanos)
            return false;

        long durationMs = toDurationMs(touchDurationNanos);
        if (durationMs > MAX_DURATION_MS) return false;

        long durationNs = durationMs * NS_MS;
        if (durationNs > deadlineNanos - targetNanos) return false;

        Request request;
        synchronized (lock) {
            if (!ready || stopping || preparer == null || preparer.isShutdown())
                return false;

            request = new Request(targetNanos, deadlineNanos, durationMs,
                    x, y, generation, completion);

            pending.offerLast(request);

            try {
                preparer.execute(() -> prepare(request));
            } catch (Exception e) {
                pending.remove(request);
                return false;
            }
        }

        unparkScheduler();
        return true;
    }

    private static long toDurationMs(long nanos) {
        if (nanos <= 0) return 1;
        return Math.max(1, (nanos + NS_MS - 1) / NS_MS);
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
        PreparedBatch oldPrepared;

        synchronized (lock) {
            generation++;

            while (!pending.isEmpty())
                cancelled.add(pending.pollFirst());

            oldPrepared = prepared;
            prepared = null;
        }

        if (oldPrepared != null)
            cancelled.addAll(oldPrepared.requests);

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
        PreparedBatch oldPrepared;

        synchronized (lock) {
            generation++;

            while (!pending.isEmpty())
                waiting.add(pending.pollFirst());

            oldPrepared = prepared;
            prepared = null;

            if (oldPrepared != null)
                waiting.addAll(oldPrepared.requests);

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

        if (instance == this) instance = null;
        preferences = null;

        super.onDestroy();
    }

    public static AutoClickService getInstance() {
        return instance;
    }

    public boolean isServiceReady() {
        return ready;
    }

    public boolean isGestureInProgress() {
        synchronized (lock) {
            return !active.isEmpty();
        }
    }

    public int getTargetX() {
        return x;
    }

    public int getTargetY() {
        return y;
    }

    public String getTimingFilePath() {
        return logPath;
    }

    public void setTarget(int newX, int newY) {
        int nx = Math.max(0, newX);
        int ny = Math.max(0, newY);

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
        if (Looper.myLooper() == Looper.getMainLooper())
            action.run();
        else
            main.post(action);
    }
}
