
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {

    private static final long MS = 1_000_000L;
    private static final long SPIN = 5_000_000L;
    private static final long MAX_MS =
            GestureDescription.getMaxGestureDuration();

    private static final int MAX_REQUESTS = 50;
    private static final int MAX_STROKES = Math.min(
            MAX_REQUESTS, GestureDescription.getMaxStrokeCount());

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
    private final LinkedBlockingQueue<Timing> logs =
            new LinkedBlockingQueue<>();

    private volatile boolean ready;
    private volatile boolean stopping;
    private volatile boolean writerStopping;
    private volatile boolean dispatching;

    private volatile int x = DEFAULT_X;
    private volatile int y = DEFAULT_Y;
    private volatile long generation;
    private volatile long nextBatchAllowedNs;

    private volatile Thread scheduler;
    private volatile Thread writerThread;

    private volatile ExecutorService preparer;
    private volatile ExecutorService dispatcher;

    private volatile String logPath = "فایل گزارش هنوز ساخته نشده است";
    private SharedPreferences preferences;

    private static final class Request {
        final long target, deadline, durationMs, durationNs, generation;
        final int x, y;

        final AtomicBoolean recorded = new AtomicBoolean();
        final AtomicBoolean delivered = new AtomicBoolean();

        volatile Path path;
        volatile long offsetMs;
        volatile long prepareStart, prepareEnd, enqueue;
        volatile long workerStart, dispatchStart, dispatchDuration, completed;
        volatile boolean accepted;
        volatile String callbackResult = "NOT_DISPATCHED";
        volatile GestureCompletion completion;

        Request(long target, long deadline, long durationMs,
                int x, int y, long generation,
                GestureCompletion completion) {
            this.target = target;
            this.deadline = deadline;
            this.durationMs = durationMs;
            this.durationNs = durationMs * MS;
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

    private static final class Batch
            extends AccessibilityService.GestureResultCallback {
        final AutoClickService owner;
        final ArrayList<Request> requests;

        Batch(AutoClickService owner, ArrayList<Request> requests) {
            this.owner = owner;
            this.requests = requests;
        }

        @Override
        public void onCompleted(GestureDescription gesture) {
            owner.finishBatch(this, true);
        }

        @Override
        public void onCancelled(GestureDescription gesture) {
            owner.finishBatch(this, false);
        }
    }

    private static final class Timing {
        final long target, prepareStart, prepareEnd, enqueue;
        final long workerStart, dispatchStart, dispatchDuration, completed;
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
            long dispatchDelay = dispatchStart == 0
                    ? 0 : dispatchStart - target;
            long completionDelay = completed == 0
                    ? 0 : completed - target;

            String result = expired ? "EXPIRED"
                    : success ? "SUCCESS" : "FAILED";

            return target + "," + prepareStart + "," + prepareEnd + ","
                    + prepare + "," + enqueue + "," + schedulerDelay + ","
                    + workerStart + "," + queueDelay + ","
                    + dispatchStart + "," + dispatchDelay + ","
                    + dispatchDuration + "," + completed + ","
                    + completionDelay + "," + accepted + ","
                    + result + "," + callback + "," + x + "," + y;
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
        startExecutors();
        startScheduler();
    }

    private void startWriter() {
        if (writerThread != null && writerThread.isAlive()) return;
        writerStopping = false;
        writerThread = new Thread(this::writerLoop, "PrecisionTimingWriter");
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
                out.write("target_elapsed_ns,prepare_start_elapsed_ns,"
                        + "prepare_end_elapsed_ns,prepare_duration_ns,"
                        + "scheduler_enqueue_elapsed_ns,scheduler_delay_ns,"
                        + "dispatch_worker_start_elapsed_ns,dispatch_queue_delay_ns,"
                        + "dispatch_start_elapsed_ns,dispatch_delay_ns,"
                        + "dispatch_duration_ns,completion_elapsed_ns,"
                        + "completion_delay_ns,accepted,result,"
                        + "callback_result,x,y");
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
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void record(Request r, boolean success, boolean expired) {
        if (r != null && r.recorded.compareAndSet(false, true)) {
            logs.offer(new Timing(r, success, expired));
        }
    }

    private void startExecutors() {
        if (preparer == null || preparer.isShutdown()) {
            preparer = Executors.newSingleThreadExecutor(task ->
                    new Thread(() -> {
                        setUrgentPriority();
                        task.run();
                    }, "PrecisionGesturePreparation"));
        }

        if (dispatcher == null || dispatcher.isShutdown()) {
            dispatcher = Executors.newSingleThreadExecutor(task ->
                    new Thread(() -> {
                        setUrgentPriority();
                        task.run();
                    }, "PrecisionGestureDispatch"));
        }
    }

    private static void setUrgentPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) {
        }
    }

    private void prepare(Request r) {
        if (!current(r)) return;
        try {
            r.prepare();
            if (current(r)) unparkScheduler();
        } catch (Exception e) {
            boolean removed;
            synchronized (lock) {
                removed = pending.remove(r);
            }
            if (removed) {
                r.callbackResult = "PREPARATION_FAILED:"
                        + e.getClass().getSimpleName();
                postFinish(r, false, false);
            }
            unparkScheduler();
        }
    }

    private void startScheduler() {
        if (scheduler != null && scheduler.isAlive()) return;
        scheduler = new Thread(this::schedulerLoop, "PrecisionClickScheduler");
        scheduler.start();
    }

    private void schedulerLoop() {
        setUrgentPriority();

        while (!stopping) {
            Request first;
            synchronized (lock) {
                first = pending.peekFirst();
            }

            if (first == null || dispatching) {
                LockSupport.park(this);
                continue;
            }

            if (!current(first)) {
                removeHead(first);
                continue;
            }

            if (first.path == null) {
                LockSupport.park(this);
                continue;
            }

            long allowed = nextBatchAllowedNs;
            long due = allowed > 0
                    ? Math.min(first.target, allowed)
                    : first.target;

            long remaining = due - SystemClock.elapsedRealtimeNanos();
            if (remaining > SPIN) {
                LockSupport.parkNanos(this, remaining - SPIN);
                continue;
            }

            while (!stopping && ready
                    && first.generation == generation
                    && SystemClock.elapsedRealtimeNanos() < due) {
            }

            if (stopping) break;
            if (first.generation != generation) {
                removeHead(first);
                continue;
            }

            ArrayList<Request> batch = collect(first);
            if (batch.isEmpty()) continue;

            long now = SystemClock.elapsedRealtimeNanos();
            for (Request r : batch) r.enqueue = now;

            dispatching = true;
            try {
                ExecutorService executor = dispatcher;
                if (executor == null || executor.isShutdown()) {
                    throw new IllegalStateException("DISPATCHER_UNAVAILABLE");
                }
                executor.execute(() -> dispatchBatch(batch));
            } catch (Exception e) {
                dispatching = false;
                finishWithoutDispatch(batch, "DISPATCH_QUEUE_FAILED", false);
                unparkScheduler();
            }
        }
    }

    private ArrayList<Request> collect(Request first) {
        ArrayList<Request> result = new ArrayList<>(MAX_STROKES);

        synchronized (lock) {
            if (pending.peekFirst() != first || !current(first)) {
                return result;
            }

            long base = first.target;
            long gen = first.generation;

            while (!pending.isEmpty() && result.size() < MAX_STROKES) {
                Request r = pending.peekFirst();

                if (r.generation != gen || r.path == null || r.target < base) {
                    break;
                }

                long delta = r.target - base;
                if (delta % MS != 0) break;

                long offset = delta / MS;
                if (offset > MAX_MS || r.durationMs > MAX_MS - offset) {
                    break;
                }

                r.offsetMs = offset;
                result.add(pending.pollFirst());
            }
        }
        return result;
    }

    private boolean current(Request r) {
        return r != null && r.generation == generation
                && ready && !stopping;
    }

    private void removeHead(Request r) {
        boolean removed = false;
        synchronized (lock) {
            if (pending.peekFirst() == r) {
                pending.pollFirst();
                removed = true;
            }
        }
        if (removed) {
            r.callbackResult = "REMOVED_FROM_PENDING";
            postFinish(r, false, true);
        }
    }

    private boolean setOffsets(List<Request> requests, long baseNs) {
        for (Request r : requests) {
            long delta = r.target - baseNs;
            long offset = delta <= 0 ? 0 : (delta + MS - 1) / MS;

            if (offset > MAX_MS || r.durationMs > MAX_MS - offset) {
                return false;
            }
            r.offsetMs = offset;
        }
        return true;
    }

    private GestureDescription buildGesture(List<Request> requests) {
        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        for (Request r : requests) {
            if (r.path == null) {
                throw new IllegalStateException("PATH_NOT_PREPARED");
            }
            builder.addStroke(new GestureDescription.StrokeDescription(
                    r.path, r.offsetMs, r.durationMs));
        }
        return builder.build();
    }

    private void dispatchBatch(ArrayList<Request> requests) {
        try {
            dispatchBatchInternal(requests);
        } finally {
            dispatching = false;
            unparkScheduler();
        }
    }

    private void dispatchBatchInternal(ArrayList<Request> requests) {
        if (requests.isEmpty()) return;

        long workerStart = SystemClock.elapsedRealtimeNanos();
        for (Request r : requests) r.workerStart = workerStart;

        if (!current(requests.get(0))) {
            finishWithoutDispatch(requests, "CANCELLED_BEFORE_DISPATCH", true);
            return;
        }

        long base = SystemClock.elapsedRealtimeNanos();
        if (!setOffsets(requests, base)) {
            finishWithoutDispatch(requests, "BATCH_DURATION_LIMIT", true);
            return;
        }

        GestureDescription gesture;
        try {
            gesture = buildGesture(requests);
        } catch (Exception e) {
            finishWithoutDispatch(requests,
                    "BATCH_BUILD_FAILED:" + e.getClass().getSimpleName(), false);
            return;
        }

        long check = SystemClock.elapsedRealtimeNanos();
        ArrayList<Request> valid = new ArrayList<>(requests.size());

        for (Request r : requests) {
            long needed = r.offsetMs * MS + r.durationNs;
            if (check < r.deadline && needed <= r.deadline - check
                    && current(r)) {
                valid.add(r);
            } else {
                r.callbackResult = "DEADLINE_OR_CANCEL_BEFORE_API";
                postFinish(r, false, true);
            }
        }

        if (valid.isEmpty()) return;

        if (valid.size() != requests.size()) {
            requests = valid;
            try {
                gesture = buildGesture(requests);
            } catch (Exception e) {
                finishWithoutDispatch(requests, "BATCH_REBUILD_FAILED", false);
                return;
            }
        }

        synchronized (lock) {
            if (!current(requests.get(0))) {
                finishWithoutDispatch(requests, "CANCELLED_BEFORE_API", true);
                return;
            }
            active.addAll(requests);
        }

        long start = SystemClock.elapsedRealtimeNanos();
        ArrayList<Request> apiValid = new ArrayList<>(requests.size());

        for (Request r : requests) {
            long needed = r.offsetMs * MS + r.durationNs;
            if (start < r.deadline && needed <= r.deadline - start
                    && current(r)) {
                apiValid.add(r);
            } else {
                r.callbackResult = "DEADLINE_OR_CANCEL_AT_API_ENTRY";
                postFinish(r, false, true);
            }
        }

        if (apiValid.size() != requests.size()) {
            synchronized (lock) {
                active.removeAll(requests);
            }

            if (apiValid.isEmpty()) return;
            requests = apiValid;

            try {
                gesture = buildGesture(requests);
            } catch (Exception e) {
                finishWithoutDispatch(requests, "BATCH_FINAL_BUILD_FAILED", false);
                return;
            }

            synchronized (lock) {
                if (!current(requests.get(0))) {
                    finishWithoutDispatch(requests,
                            "CANCELLED_BEFORE_FINAL_API", true);
                    return;
                }
                active.addAll(requests);
            }

            start = SystemClock.elapsedRealtimeNanos();
            for (Request r : requests) {
                long needed = r.offsetMs * MS + r.durationNs;
                if (start >= r.deadline || needed > r.deadline - start
                        || !current(r)) {
                    synchronized (lock) {
                        active.removeAll(requests);
                    }
                    finishWithoutDispatch(requests,
                            "FINAL_DEADLINE_CHECK_FAILED", true);
                    return;
                }
            }
        }

        for (Request r : requests) {
            r.dispatchStart = start;
            r.callbackResult = "WAITING_FOR_CALLBACK";
        }

        boolean accepted;
        try {
            accepted = dispatchGesture(gesture, new Batch(this, requests), main);
        } catch (Exception e) {
            long end = SystemClock.elapsedRealtimeNanos();
            for (Request r : requests) {
                r.dispatchDuration = end - start;
                r.callbackResult = "DISPATCH_EXCEPTION:"
                        + e.getClass().getSimpleName();
            }
            synchronized (lock) {
                active.removeAll(requests);
            }
            for (Request r : requests) postFinish(r, false, false);
            return;
        }

        long end = SystemClock.elapsedRealtimeNanos();
        for (Request r : requests) {
            r.dispatchDuration = end - start;
            r.accepted = accepted;
        }

        if (accepted) {
            long duration = 0;
            for (Request r : requests) {
                long strokeEnd = r.offsetMs * MS + r.durationNs;
                if (strokeEnd > duration) duration = strokeEnd;
            }
            nextBatchAllowedNs = start + duration * 95L / 100L;
        } else {
            for (Request r : requests) r.callbackResult = "API_RETURNED_FALSE";
            synchronized (lock) {
                active.removeAll(requests);
            }
            for (Request r : requests) postFinish(r, false, false);
        }
    }

    private void finishWithoutDispatch(
            List<Request> requests, String reason, boolean expired) {
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

        for (Request r : batch.requests) {
            r.completed = now;
            r.callbackResult = success ? "ON_COMPLETED" : "ON_CANCELLED";
            finish(r, success, false);
        }
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

        if (r.completed == 0) {
            r.completed = SystemClock.elapsedRealtimeNanos();
        }

        record(r, success, expired);

        GestureCompletion callback = r.completion;
        r.completion = null;

        if (callback != null) {
            try {
                callback.onFinished(success, expired);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean performClickAtTarget(
            long targetNanos,
            long cycleStartNanos,
            long deadlineNanos,
            long touchDurationNanos,
            GestureCompletion completion) {

        if (!ready || stopping || completion == null
                || touchDurationNanos < 0
                || deadlineNanos <= cycleStartNanos
                || targetNanos < cycleStartNanos
                || targetNanos >= deadlineNanos) {
            return false;
        }

        long durationMs = toDurationMs(touchDurationNanos);
        if (durationMs > MAX_MS) return false;

        long durationNs = durationMs * MS;
        if (durationNs > deadlineNanos - targetNanos) return false;

        boolean empty;
        Request request;

        synchronized (lock) {
            if (!ready || stopping || preparer == null
                    || preparer.isShutdown()) {
                return false;
            }

            request = new Request(targetNanos, deadlineNanos,
                    durationMs, x, y, generation, completion);

            empty = pending.isEmpty();
            pending.offerLast(request);

            try {
                preparer.execute(() -> prepare(request));
            } catch (Exception e) {
                pending.remove(request);
                return false;
            }
        }

        if (empty) unparkScheduler();
        return true;
    }

    private static long toDurationMs(long nanos) {
        if (nanos <= 0) return 1;
        return Math.max(1, (nanos + MS - 1) / MS);
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
            nextBatchAllowedNs = 0;
            while (!pending.isEmpty()) {
                cancelled.add(pending.pollFirst());
            }
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
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

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

        ExecutorService p = preparer;
        preparer = null;
        if (p != null) p.shutdownNow();

        ExecutorService d = dispatcher;
        dispatcher = null;
        if (d != null) d.shutdownNow();

        List<Request> waiting = new ArrayList<>();
        List<Request> running = new ArrayList<>();

        synchronized (lock) {
            generation++;
            while (!pending.isEmpty()) waiting.add(pending.pollFirst());
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
            if (p != null) {
                p.edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply();
            }
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
