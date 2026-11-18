
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
import java.util.HashSet;
import java.util.Iterator;

public class AutoClickService extends AccessibilityService {

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final long NO_SCHEDULE = Long.MAX_VALUE;

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private static volatile AutoClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<ClickRequest> pending = new ArrayDeque<>();
    private final HashSet<ClickRequest> activeRequests = new HashSet<>();

    private int targetX = 350;
    private int targetY = 700;

    private volatile boolean serviceReady;
    private long scheduledTargetNanos = NO_SCHEDULE;

    private final Runnable schedulerRunnable = this::processDueRequests;

    /*
     * هر درخواست، Callback ژست خودش را نیز مدیریت می‌کند.
     * به این ترتیب برای هر کلیک یک Callback جداگانه ساخته نمی‌شود.
     */
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
                GestureCompletion completion
        ) {
            this.owner = owner;
            this.targetNanos = targetNanos;
            this.deadlineNanos = deadlineNanos;
            this.touchDurationMs = touchDurationMs;
            this.x = x;
            this.y = y;
            this.completion = completion;
        }

        @Override
        public void onCompleted(GestureDescription description) {
            owner.finishRequest(this, true);
        }

        @Override
        public void onCancelled(GestureDescription description) {
            owner.finishRequest(this, false);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        SharedPreferences prefs = getSharedPreferences(
                "auto_click_target", MODE_PRIVATE
        );

        targetX = prefs.getInt("target_x", 350);
        targetY = prefs.getInt("target_y", 700);

        instance = this;
        serviceReady = true;
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
        handler.removeCallbacks(schedulerRunnable);
        scheduledTargetNanos = NO_SCHEDULE;

        while (!pending.isEmpty()) {
            notifyCompletion(pending.pollFirst(), false, true);
        }

        Iterator<ClickRequest> iterator = activeRequests.iterator();
        while (iterator.hasNext()) {
            ClickRequest request = iterator.next();
            iterator.remove();
            notifyCompletion(request, false, false);
        }

        if (instance == this) instance = null;
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

    public void setTarget(int x, int y) {
        final int newX = Math.max(0, x);
        final int newY = Math.max(0, y);

        runOnMain(() -> {
            if (targetX == newX && targetY == newY) return;

            targetX = newX;
            targetY = newY;

            getSharedPreferences("auto_click_target", MODE_PRIVATE)
                    .edit()
                    .putInt("target_x", newX)
                    .putInt("target_y", newY)
                    .apply();
        });
    }

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            long touchDurationMs,
            GestureCompletion completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()
                || !serviceReady
                || completion == null
                || touchDurationMs < 1
                || touchDurationMs > GestureDescription.getMaxGestureDuration()
                || cycleDeadlineElapsedRealtimeNanos <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos < cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos >= cycleDeadlineElapsedRealtimeNanos) {
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

        ClickRequest head = pending.peekFirst();

        // درخواست سررسیدشده، در صورت خالی بودن صف، مستقیم ارسال می‌شود.
        if (head == null && targetElapsedRealtimeNanos
                <= SystemClock.elapsedRealtimeNanos()) {
            dispatchReadyRequest(request);
            return true;
        }

        // درخواست‌ها باید به ترتیب زمان هدف وارد شوند.
        pending.offerLast(request);

        if (head == null) scheduleHead();
        return true;
    }

    private void scheduleHead() {
        ClickRequest head = pending.peekFirst();

        if (head == null) {
            if (scheduledTargetNanos != NO_SCHEDULE) {
                handler.removeCallbacks(schedulerRunnable);
                scheduledTargetNanos = NO_SCHEDULE;
            }
            return;
        }

        if (scheduledTargetNanos == head.targetNanos) return;

        handler.removeCallbacks(schedulerRunnable);
        scheduledTargetNanos = head.targetNanos;

        long remaining = head.targetNanos - SystemClock.elapsedRealtimeNanos();

        if (remaining <= 0) {
            handler.post(schedulerRunnable);
            return;
        }

        long delayMs = (remaining + NANOS_PER_MS - 1) / NANOS_PER_MS;
        handler.postDelayed(schedulerRunnable, delayMs);
    }

    private void processDueRequests() {
        scheduledTargetNanos = NO_SCHEDULE;

        long now;
        ClickRequest request;

        while ((request = pending.peekFirst()) != null) {
            now = SystemClock.elapsedRealtimeNanos();
            if (now < request.targetNanos) break;

            pending.pollFirst();
            dispatchReadyRequest(request);
        }

        scheduleHead();
    }

    private void dispatchReadyRequest(ClickRequest request) {
        if (SystemClock.elapsedRealtimeNanos() >= request.deadlineNanos) {
            notifyCompletion(request, false, true);
        } else if (!dispatchRequest(request)) {
            notifyCompletion(request, false, false);
        }
    }

    private boolean dispatchRequest(ClickRequest request) {
        try {
            Path path = new Path();
            path.moveTo(request.x, request.y);
            path.lineTo(request.x, request.y);

            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(
                            path, 0, request.touchDurationMs
                    ))
                    .build();

            activeRequests.add(request);

            if (dispatchGesture(gesture, request, handler)) return true;

            activeRequests.remove(request);
            return false;

        } catch (Exception ignored) {
            activeRequests.remove(request);
            return false;
        }
    }

    private void finishRequest(ClickRequest request, boolean success) {
        if (activeRequests.remove(request)) {
            notifyCompletion(request, success, false);
        }
    }

    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
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
        handler.removeCallbacks(schedulerRunnable);
        scheduledTargetNanos = NO_SCHEDULE;

        while (!pending.isEmpty()) {
            notifyCompletion(pending.pollFirst(), false, true);
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
