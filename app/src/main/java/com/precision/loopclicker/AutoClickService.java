
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
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
    private static final long FINAL_SPIN_WINDOW_NS = 250_000L;

    private static volatile AutoClickService instance;

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();

    private final PriorityQueue<ClickRequest> pending =
            new PriorityQueue<>(
                    Comparator.comparingLong(request -> request.targetNanos));

    private final HashSet<ClickRequest> dispatching = new HashSet<>();
    private final HashSet<ClickRequest> activeRequests = new HashSet<>();

    private volatile boolean serviceReady;
    private volatile boolean schedulerStopping;
    private volatile Thread schedulerThread;
    private volatile int targetX = DEFAULT_TARGET_X;
    private volatile int targetY = DEFAULT_TARGET_Y;

    private SharedPreferences targetPreferences;
    private volatile String lastRequestError = "";

    private final class ClickRequest extends AccessibilityService.GestureResultCallback {
        final long targetNanos;
        final long deadlineNanos;
        final long touchDurationMs;
        final long touchDurationNanos;
        final int x;
        final int y;
        final GestureDescription gesture;

        GestureCompletion completion;

        ClickRequest(
                long target,
                long deadline,
                long durationMs,
                int x,
                int y,
                GestureCompletion completion) {

            targetNanos = target;
            deadlineNanos = deadline;
            touchDurationMs = durationMs;
            touchDurationNanos = durationMs * NANOS_PER_MS;
            this.x = x;
            this.y = y;
            this.completion = completion;

            Path path = new Path();
            path.moveTo(x, y);
            path.lineTo(x, y);
            gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(
                            path, 0L, durationMs))
                    .build();
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
            long remaining = request.targetNanos - now;

            if (remaining > FINAL_SPIN_WINDOW_NS) {
                LockSupport.parkNanos(
                        this, remaining - FINAL_SPIN_WINDOW_NS);
                continue;
            }

            if (remaining > 0) {
                do {
                    now = SystemClock.elapsedRealtimeNanos();
                } while (now < request.targetNanos && !schedulerStopping);
                if (schedulerStopping) break;
            }

            synchronized (queueLock) {
                if (pending.peek() != request) continue;

                pending.poll();
                dispatching.add(request);
            }

            if (schedulerStopping || !serviceReady) {
                removeDispatching(request);
                postCompletion(request, false, true);
                continue;
            }

            boolean posted = mainHandler.post(
                    () -> dispatchReadyRequest(request));

            if (!posted) {
                removeDispatching(request);
                notifyCompletion(request, false, true);
            }
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

        if (durationMs > MAX_GESTURE_DURATION_MS) {
            lastRequestError =
                    "مدت لمس از حداکثر زمان مجاز اندروید بیشتر است";
            return false;
        }

        return true;
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

        ClickRequest request = new ClickRequest(
                targetElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                touchDurationNanos / NANOS_PER_MS,
                targetX,
                targetY,
                completion);

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
                    target,
                    deadline,
                    durationMs,
                    x,
                    y,
                    completions[i]));
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

    private void removeDispatching(ClickRequest request) {
        synchronized (queueLock) {
            dispatching.remove(request);
        }
    }

    private void dispatchReadyRequest(ClickRequest request) {
        synchronized (queueLock) {
            if (!dispatching.remove(request)) return;
        }

        if (!serviceReady || schedulerStopping) {
            notifyCompletion(request, false, true);
            return;
        }

        long now = SystemClock.elapsedRealtimeNanos();
        long remaining = request.deadlineNanos - now;

        if (remaining < request.touchDurationNanos) {
            notifyCompletion(request, false, true);
            return;
        }

        if (!dispatchRequest(request)) {
            notifyCompletion(request, false, false);
        }
    }

    private boolean dispatchRequest(ClickRequest request) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }

        try {
            long now = SystemClock.elapsedRealtimeNanos();

            if (request.deadlineNanos - now
                    < request.touchDurationNanos) {
                notifyCompletion(request, false, true);
                return true;
            }

            synchronized (queueLock) {
                if (!serviceReady || schedulerStopping) {
                    return false;
                }

                activeRequests.add(request);
            }

            boolean accepted;

            try {
                accepted = dispatchGesture(
                        request.gesture,
                        request,
                        mainHandler);
            } catch (Exception e) {
                accepted = false;
            }

            if (!accepted) {
                synchronized (queueLock) {
                    activeRequests.remove(request);
                }
            }

            return accepted;

        } catch (Exception e) {
            synchronized (queueLock) {
                activeRequests.remove(request);
            }

            return false;
        }
    }

    private void completeRequest(
            ClickRequest request,
            boolean success) {

        boolean wasActive;

        synchronized (queueLock) {
            wasActive = activeRequests.remove(request);
        }

        if (wasActive) {
            notifyCompletion(request, success, false);
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

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }
}
