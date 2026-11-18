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

    /*
     * Park until close to the target, then use a very short final spin.
     * This reduces avoidable wake-up lateness without dispatching early.
     */
    private static final long FINAL_SPIN_WINDOW_NANOS = 300_000L;
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

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    /*
     * Gesture callbacks and client completion callbacks are delivered on the
     * service main thread. The timing worker itself never blocks that thread.
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /*
     * FIFO order is intentionally preserved. Requests are not sorted or
     * coalesced, so the existing click ordering semantics remain unchanged.
     */
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
        volatile GestureDescription gesture;
        final int x;
        final int y;

        /*
         * Accessed only from the main thread when completion is delivered.
         */
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

        void prepareGesture() {
            if (gesture != null) {
                return;
            }
            Path path = new Path();
            path.moveTo(x, y);
            path.lineTo(x, y);
            gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(
                            path, 0L, touchDurationMs))
                    .build();
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

    /*
     * Waits against elapsedRealtimeNanos() using a dedicated worker thread.
     *
     * parkNanos() accepts a nanosecond duration. It does not promise that the
     * operating system will wake the thread at that exact nanosecond; it only
     * removes Handler.postDelayed()'s millisecond delay conversion.
     *
     * The worker parks for the coarse part of the wait and spins only for a
     * bounded final window. It never intentionally dispatches before target.
     */
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

            // Prepare the immutable gesture while there is still time before
            // its target, keeping allocation out of the deadline-critical path.
            try {
                request.prepareGesture();
            } catch (Exception ignored) {
                synchronized (queueLock) {
                    if (pending.peekFirst() == request) {
                        pending.pollFirst();
                    } else {
                        continue;
                    }
                }
                postCompletion(request, false, false);
                continue;
            }

            long remainingNanos =
                    request.targetNanos - SystemClock.elapsedRealtimeNanos();

            if (remainingNanos > FINAL_SPIN_WINDOW_NANOS) {
                // Leave only a short interval for the final precision wait.
                LockSupport.parkNanos(
                        this, remainingNanos - FINAL_SPIN_WINDOW_NANOS);
                continue;
            }

            // Never dispatch before the absolute target. The spin is bounded
            // by FINAL_SPIN_WINDOW_NANOS and is used only for the final wait.
            while (!schedulerStopping
                    && SystemClock.elapsedRealtimeNanos() < request.targetNanos) {
                // Intentionally empty: a sub-millisecond final wait.
            }

            if (schedulerStopping) {
                break;
            }

            synchronized (queueLock) {
                /*
                 * Cancellation may have removed the head while this thread
                 * was parked. Re-check identity before removing it.
                 */
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
        // No event processing is required by this click service.
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

    /**
     * touchDurationNanos is received in nanoseconds.
     *
     * Existing cycle rules are preserved:
     * - target time must be inside the cycle;
     * - dispatch must begin before the cycle deadline;
     * - after dispatch begins in time, Android may complete the gesture
     *   after the cycle deadline.
     *
     * This method can now be called from any thread. Queue access is protected
     * by queueLock and the timing worker performs the actual dispatch.
     */
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

        long touchDurationMs = toGestureDurationMs(touchDurationNanos);

        if (touchDurationMs > MAX_GESTURE_DURATION_MS) {
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
                return false;
            }

            wasEmpty = pending.isEmpty();
            pending.offerLast(request);
        }

        /*
         * Wake the worker only when the queue changes from empty to non-empty.
         * FIFO ordering is unchanged, so a later appended request cannot
         * replace the current head's target.
         */
        if (wasEmpty) {
            Thread worker = schedulerThread;
            if (worker != null) {
                LockSupport.unpark(worker);
            }
        }

        return true;
    }

    private static long toGestureDurationMs(long durationNanos) {
        if (durationNanos <= 0L) {
            return 1L;
        }

        long millis = durationNanos / NANOS_PER_MS;

        if (durationNanos % NANOS_PER_MS != 0L) {
            millis++;
        }

        return Math.max(1L, millis);
    }

    private void dispatchReadyRequest(ClickRequest request) {
        /*
         * Preserve the original deadline rule. Only the dispatch start must
         * occur before the deadline; gesture completion may occur afterward.
         */
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
            GestureDescription gesture = request.gesture;

            /*
             * Register before dispatch so a fast callback cannot arrive before
             * the request is known as active. The lock also prevents onDestroy()
             * from clearing the service while dispatch is being initiated.
             */
            synchronized (queueLock) {
                if (!serviceReady || schedulerStopping) {
                    return false;
                }

                activeRequests.add(request);

                boolean accepted;
                try {
                    /*
                     * dispatchGesture is initiated by the dedicated timing
                     * worker. Its result callback is explicitly delivered on
                     * the main thread through mainHandler.
                     */
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

    /*
     * Worker-thread outcomes are posted to the main thread so callers continue
     * receiving completion callbacks on the same thread as before.
     */
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
                // A client callback must not interrupt service scheduling.
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
