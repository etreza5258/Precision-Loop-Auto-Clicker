
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final String TARGET_PREFS_NAME = "auto_click_target";
    private static final String TARGET_X_KEY = "target_x";
    private static final String TARGET_Y_KEY = "target_y";
    private static final int DEFAULT_TARGET_X = 350;
    private static final int DEFAULT_TARGET_Y = 700;

    private static final int INITIAL_PENDING_CAPACITY = 64;
    private static final int INITIAL_ACTIVE_CAPACITY = 32;
    private static final long MAX_GESTURE_DURATION_MS =
            GestureDescription.getMaxGestureDuration();

    private static volatile AutoClickService instance;

    private volatile String lastRequestError = "";

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();

    private final ArrayDeque<ClickRequest> pending =
            new ArrayDeque<>(INITIAL_PENDING_CAPACITY);

    private final HashSet<ClickRequest> activeRequests =
            new HashSet<>(INITIAL_ACTIVE_CAPACITY);

    private volatile boolean serviceReady;
    private volatile boolean schedulerStopping;
    private volatile Thread schedulerThread;

    private volatile int targetX = DEFAULT_TARGET_X;
    private volatile int targetY = DEFAULT_TARGET_Y;

    private SharedPreferences targetPreferences;

    private static final class ClickRequest
            extends AccessibilityService.GestureResultCallback {

        final AutoClickService owner;
        final long targetNanos;
        final long deadlineNanos;
        final long touchDurationMs;
        final int x;
        final int y;

        GestureCompletion completion;

        ClickRequest(
                AutoClickService owner,
                long targetNanos,
                long deadlineNanos,
                long touchDurationMs,
                int x,
                int y,
                GestureCompletion completion) {

            this.owner = owner;
            this.targetNanos = targetNanos;
            this.deadlineNanos = deadlineNanos;
            this.touchDurationMs = touchDurationMs;
            this.x = x;
            this.y = y;
            this.completion = completion;
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
                MODE_PRIVATE
        );

        targetX = targetPreferences.getInt(
                TARGET_X_KEY,
                DEFAULT_TARGET_X
        );

        targetY = targetPreferences.getInt(
                TARGET_Y_KEY,
                DEFAULT_TARGET_Y
        );

        schedulerStopping = false;
        serviceReady = true;
        instance = this;

        startNanoScheduler();
    }

    private void startNanoScheduler() {
        Thread existing = schedulerThread;

        if (existing != null && existing.isAlive()) {
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
        while (!schedulerStopping) {
            ClickRequest request;

            synchronized (queueLock) {
                request = pending.peekFirst();
            }

            if (request == null) {
                LockSupport.park(this);
                continue;
            }

            long remainingNanos =
                    request.targetNanos - SystemClock.elapsedRealtimeNanos();

            if (remainingNanos > 0L) {
                LockSupport.parkNanos(this, remainingNanos);
                continue;
            }

            synchronized (queueLock) {
                if (pending.peekFirst() != request) {
                    continue;
                }

                pending.pollFirst();
            }

            if (schedulerStopping || !serviceReady) {
                postCompletion(request, false, true);
                continue;
            }

            dispatchReadyRequest(request);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
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

        List<ClickRequest> pendingToFinish = new ArrayList<>();
        List<ClickRequest> activeToFinish = new ArrayList<>();

        synchronized (queueLock) {
            while (!pending.isEmpty()) {
                pendingToFinish.add(pending.pollFirst());
            }

            Iterator<ClickRequest> iterator = activeRequests.iterator();
            while (iterator.hasNext()) {
                activeToFinish.add(iterator.next());
                iterator.remove();
            }
        }

        for (ClickRequest request : pendingToFinish) {
            notifyCompletion(request, false, true);
        }

        for (ClickRequest request : activeToFinish) {
            notifyCompletion(request, false, false);
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

    public String getLastRequestError() {
        return lastRequestError;
    }

    public void setTarget(int x, int y) {
        final int newX = Math.max(0, x);
        final int newY = Math.max(0, y);

        runOnMain(() -> {
            if (!serviceReady || (targetX == newX && targetY == newY)) {
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

        if (!serviceReady || schedulerStopping) {
            lastRequestError = "سرویس کلیک آماده نیست";
            return false;
        }

        if (completion == null) {
            lastRequestError = "تابع تکمیل کلیک مشخص نشده است";
            return false;
        }

        if (touchDurationNanos <= 0L) {
            lastRequestError = "مدت لمس باید بیشتر از صفر باشد";
            return false;
        }

        if (touchDurationNanos % NANOS_PER_MS != 0L) {
            lastRequestError =
                    "مدت لمس با دقت واردشده توسط API اندروید قابل اجرا نیست؛ "
                    + "مدت باید مضرب دقیق ۱٬۰۰۰٬۰۰۰ نانوثانیه باشد";
            return false;
        }

        long touchDurationMs = touchDurationNanos / NANOS_PER_MS;

        if (touchDurationMs > MAX_GESTURE_DURATION_MS) {
            lastRequestError =
                    "مدت لمس از حداکثر زمان مجاز اندروید بیشتر است";
            return false;
        }

        if (cycleDeadlineElapsedRealtimeNanos
                    <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                    < cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                    >= cycleDeadlineElapsedRealtimeNanos) {
            lastRequestError = "زمان هدف خارج از محدوده چرخه است";
            return false;
        }

        ClickRequest request = new ClickRequest(
                this,
                targetElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                touchDurationMs,
                targetX,
                targetY,
                completion
        );

        boolean wasEmpty;

        synchronized (queueLock) {
            if (!serviceReady || schedulerStopping) {
                lastRequestError = "سرویس کلیک متوقف شده است";
                return false;
            }

            wasEmpty = pending.isEmpty();
            pending.offerLast(request);
        }

        lastRequestError = "";

        if (wasEmpty) {
            Thread worker = schedulerThread;
            if (worker != null) {
                LockSupport.unpark(worker);
            }
        }

        return true;
    }

    private void dispatchReadyRequest(ClickRequest request) {
        if (SystemClock.elapsedRealtimeNanos() >= request.deadlineNanos) {
            postCompletion(request, false, true);
            return;
        }

        if (!dispatchRequest(request)) {
            postCompletion(request, false, false);
        }
    }

    private boolean dispatchRequest(ClickRequest request) {
        try {
            Path path = new Path();
            path.moveTo(request.x, request.y);
            path.lineTo(request.x, request.y);

            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(new GestureDescription.StrokeDescription(
                                    path,
                                    0L,
                                    request.touchDurationMs
                            ))
                            .build();

            synchronized (queueLock) {
                if (!serviceReady || schedulerStopping) {
                    return false;
                }

                activeRequests.add(request);

                boolean accepted;
                try {
                    accepted = dispatchGesture(
                            gesture,
                            request,
                            mainHandler
                    );
                } catch (Exception ignored) {
                    activeRequests.remove(request);
                    return false;
                }

                if (!accepted) {
                    activeRequests.remove(request);
                }

                return accepted;
            }
        } catch (Exception ignored) {
            synchronized (queueLock) {
                activeRequests.remove(request);
            }
            return false;
        }
    }

    private void completeRequest(ClickRequest request, boolean success) {
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
        } else {
            mainHandler.post(
                    () -> notifyCompletion(request, success, expired)
            );
        }
    }

    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired) {

        if (request == null) {
            return;
        }

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
                cancelled.add(pending.pollFirst());
            }
        }

        Thread worker = schedulerThread;
        if (worker != null) {
            LockSupport.unpark(worker);
        }

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
