
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {

    private static final long NANOS_PER_MS = 1_000_000L;

    /*
     * پنجره انتظار فعال:
     * 5 میلی‌ثانیه
     */
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

    private static volatile AutoClickService instance;

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    /*
     * قفل صف درخواست‌ها.
     */
    private final Object queueLock = new Object();

    /*
     * قفل مستقل برای هماهنگی مرز لغو و ارسال.
     */
    private final Object dispatchLock = new Object();

    private final ArrayDeque<ClickRequest> pending =
            new ArrayDeque<>(INITIAL_PENDING_CAPACITY);

    private final HashSet<ClickRequest> activeRequests =
            new HashSet<>(INITIAL_ACTIVE_CAPACITY);

    private volatile boolean serviceReady;
    private volatile boolean schedulerStopping;

    private volatile Thread schedulerThread;

    private volatile int targetX = DEFAULT_TARGET_X;
    private volatile int targetY = DEFAULT_TARGET_Y;

    /*
     * بررسی نسل بدون نیاز به گرفتن قفل.
     */
    private volatile long cancellationGeneration = 0L;

    private volatile ExecutorService preparationExecutor;

    private SharedPreferences targetPreferences;

    private static final class ClickRequest
            extends AccessibilityService.GestureResultCallback {

        final AutoClickService owner;

        final long targetNanos;
        final long deadlineNanos;
        final long touchDurationMs;

        final long requestGeneration;

        final int x;
        final int y;

        volatile GestureDescription gesture;

        GestureCompletion completion;

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
        }

        @Override
        public void onCompleted(GestureDescription gesture) {
            owner.completeRequest(this, true);
        }

        @Override
        public void onCancelled(GestureDescription gesture) {
            owner.completeRequest(this, false);
        }
    }

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

        startPreparationExecutor();
        startNanoScheduler();
    }

    /*
     * نخ مستقل برای آماده‌سازی Gesture.
     */
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

    /*
     * نخ مستقل زمان‌بندی نانوثانیه‌ای.
     */
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

    /*
     * آماده‌سازی درخواست خارج از نخ زمان‌بندی.
     */
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

    /*
     * زمان‌بند اصلی.
     */
    private void schedulerLoop() {

        try {

            Process.setThreadPriority(
                    Process.THREAD_PRIORITY_URGENT_DISPLAY);

        } catch (Exception ignored) {
            // Keep scheduler operational.
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

            /*
             * بررسی اعتبار بدون قفل.
             */
            if (!isRequestCurrent(request)) {

                removePendingHead(request);

                continue;
            }

            /*
             * ژست باید پیش از زمان هدف آماده شده باشد.
             */
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

            /*
             * انتظار فعال نهایی:
             * بدون گرفتن قفل در حلقه.
             */
            while (!schedulerStopping
                    && serviceReady
                    && request.requestGeneration
                            == cancellationGeneration
                    && SystemClock.elapsedRealtimeNanos()
                            < request.targetNanos) {

                // Maximum-priority precision spin.
            }

            if (schedulerStopping) {
                break;
            }

            if (request.requestGeneration
                    != cancellationGeneration) {

                removePendingHead(request);

                continue;
            }

            /*
             * خارج‌کردن درخواست از صف.
             */
            synchronized (queueLock) {

                if (pending.peekFirst() != request) {
                    continue;
                }

                if (request.requestGeneration
                        != cancellationGeneration) {

                    pending.pollFirst();

                    postCompletion(
                            request,
                            false,
                            true);

                    continue;
                }

                pending.pollFirst();
            }

            if (schedulerStopping || !serviceReady) {

                postCompletion(
                        request,
                        false,
                        true);

                continue;
            }

            dispatchReadyRequest(request);
        }
    }

    /*
     * بررسی اعتبار درخواست بدون قفل.
     */
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event processing required.
    }

    @Override
    public void onInterrupt() {

        cancelPendingInternal();
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

        ExecutorService executor = preparationExecutor;

        preparationExecutor = null;

        if (executor != null) {
            executor.shutdownNow();
        }

        List<ClickRequest> pendingToFinish =
                new ArrayList<>();

        List<ClickRequest> activeToFinish =
                new ArrayList<>();

        /*
         * ترتیب قفل‌ها در تمام مسیرها یکسان است:
         * dispatchLock سپس queueLock.
         */
        synchronized (dispatchLock) {

            synchronized (queueLock) {

                cancellationGeneration++;

                while (!pending.isEmpty()) {

                    pendingToFinish.add(
                            pending.pollFirst());
                }

                Iterator<ClickRequest> iterator =
                        activeRequests.iterator();

                while (iterator.hasNext()) {

                    activeToFinish.add(iterator.next());

                    iterator.remove();
                }
            }
        }

        for (ClickRequest request : pendingToFinish) {

            notifyCompletion(
                    request,
                    false,
                    true);
        }

        for (ClickRequest request : activeToFinish) {

            notifyCompletion(
                    request,
                    false,
                    false);
        }

        if (instance == this) {
            instance = null;
        }

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

            /*
             * ثبت کار آماده‌سازی در همان ترتیب FIFO.
             */
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

    private void dispatchReadyRequest(ClickRequest request) {

        /*
         * حفظ قانون مهلت چرخه.
         */
        if (SystemClock.elapsedRealtimeNanos()
                >= request.deadlineNanos) {

            postCompletion(
                    request,
                    false,
                    true);

            return;
        }

        if (!dispatchRequest(request)) {

            postCompletion(
                    request,
                    false,
                    false);
        }
    }

    /*
     * ارسال ژست با قفل مستقل.
     *
     * queueLock هنگام dispatchGesture گرفته نمی‌شود.
     */
    private boolean dispatchRequest(ClickRequest request) {

        GestureDescription gesture = request.gesture;

        if (gesture == null) {
            return false;
        }

        synchronized (dispatchLock) {

            /*
             * کنترل نهایی نسل لغو.
             */
            if (!serviceReady
                    || schedulerStopping
                    || request.requestGeneration
                            != cancellationGeneration) {

                return false;
            }

            synchronized (queueLock) {

                activeRequests.add(request);
            }

            boolean accepted;

            try {

                accepted = dispatchGesture(
                        gesture,
                        request,
                        mainHandler);

            } catch (Exception ignored) {

                synchronized (queueLock) {

                    activeRequests.remove(request);
                }

                return false;
            }

            if (!accepted) {

                synchronized (queueLock) {

                    activeRequests.remove(request);
                }
            }

            return accepted;
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

        if (request == null) {
            return;
        }

        GestureCompletion callback =
                request.completion;

        request.completion = null;

        if (callback != null) {

            try {

                callback.onFinished(
                        success,
                        expired);

            } catch (Exception ignored) {
                // Keep scheduler alive.
            }
        }
    }

    /*
     * لغو فوری و thread-safe.
     *
     * دیگر برای اجرای عملیات لغو منتظر نخ اصلی نمی‌مانیم.
     */
    public void cancelPendingScheduledClick() {

        cancelPendingInternal();
    }

    private void cancelPendingInternal() {

        List<ClickRequest> cancelled =
                new ArrayList<>();

        synchronized (dispatchLock) {

            synchronized (queueLock) {

                /*
                 * تمام درخواست‌های نسل قبلی نامعتبر می‌شوند.
                 */
                cancellationGeneration++;

                while (!pending.isEmpty()) {

                    cancelled.add(
                            pending.pollFirst());
                }
            }
        }

        Thread worker = schedulerThread;

        if (worker != null) {

            LockSupport.unpark(worker);
        }

        for (ClickRequest request : cancelled) {

            postCompletion(
                    request,
                    false,
                    true);
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
