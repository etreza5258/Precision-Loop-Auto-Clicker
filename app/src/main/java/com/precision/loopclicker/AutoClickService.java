
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.Comparator;

public class AutoClickService extends AccessibilityService {

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private static volatile AutoClickService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ArrayList<ClickRequest> pending = new ArrayList<>();
    private final ArrayList<ClickRequest> ready = new ArrayList<>();
    private final ArrayList<ClickRequest> activeRequests = new ArrayList<>();

    private int targetX = 350;
    private int targetY = 700;

    private volatile boolean serviceReady;
    private long nextRequestId;

    private static class ClickRequest {
        long id;
        long targetNanos;
        long deadlineNanos;
        long touchDurationMs;

        int x;
        int y;

        Runnable runnable;
        GestureCompletion completion;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        SharedPreferences prefs = getSharedPreferences(
                "auto_click_target",
                MODE_PRIVATE
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
        runOnMain(this::cancelPendingInternal);
    }

    @Override
    public void onDestroy() {
        runOnMain(() -> {
            cancelPendingInternal();

            ArrayList<ClickRequest> copy =
                    new ArrayList<>(activeRequests);

            activeRequests.clear();

            for (ClickRequest request : copy) {
                notifyCompletion(request, false, false);
            }

            serviceReady = false;

            if (instance == this) {
                instance = null;
            }
        });

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
        runOnMain(() -> {
            targetX = Math.max(0, x);
            targetY = Math.max(0, y);

            getSharedPreferences(
                    "auto_click_target",
                    MODE_PRIVATE
            ).edit()
                    .putInt("target_x", targetX)
                    .putInt("target_y", targetY)
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }

        if (!serviceReady || completion == null) {
            return false;
        }

        if (touchDurationMs < 1 ||
                touchDurationMs >
                        GestureDescription.getMaxGestureDuration()) {
            return false;
        }

        if (cycleDeadlineElapsedRealtimeNanos <=
                cycleStartElapsedRealtimeNanos) {
            return false;
        }

        if (targetElapsedRealtimeNanos <
                cycleStartElapsedRealtimeNanos ||
                targetElapsedRealtimeNanos >=
                        cycleDeadlineElapsedRealtimeNanos) {
            return false;
        }

        long touchNanos = touchDurationMs * 1_000_000L;

        if (touchNanos >
                cycleDeadlineElapsedRealtimeNanos -
                        targetElapsedRealtimeNanos) {
            return false;
        }

        ClickRequest request = new ClickRequest();

        request.id = ++nextRequestId;
        request.targetNanos = targetElapsedRealtimeNanos;
        request.deadlineNanos = cycleDeadlineElapsedRealtimeNanos;
        request.touchDurationMs = touchDurationMs;

        request.x = targetX;
        request.y = targetY;

        request.completion = completion;
        request.runnable = () -> executeRequest(request);

        pending.add(request);
        scheduleRequest(request);

        return true;
    }

    private void scheduleRequest(ClickRequest request) {
        if (!pending.contains(request)) {
            return;
        }

        long remaining =
                request.targetNanos -
                        SystemClock.elapsedRealtimeNanos();

        if (remaining <= 0) {
            handler.post(request.runnable);
            return;
        }

        long delayMs = remaining / 1_000_000L;

        if (remaining % 1_000_000L != 0) {
            delayMs++;
        }

        handler.postDelayed(request.runnable, delayMs);
    }

    private void executeRequest(ClickRequest request) {
        if (!pending.remove(request)) {
            return;
        }

        long now = SystemClock.elapsedRealtimeNanos();

        if (now < request.targetNanos) {
            pending.add(request);
            scheduleRequest(request);
            return;
        }

        ready.add(request);

        ready.sort(Comparator.comparingLong(
                item -> item.targetNanos
        ));

        pumpReadyQueue();
    }

    private void pumpReadyQueue() {
        if (!serviceReady) {
            return;
        }

        while (!ready.isEmpty()) {
            ClickRequest request = ready.remove(0);

            if (!dispatchRequest(request)) {
                notifyCompletion(request, false, false);
            }
        }
    }

    private boolean dispatchRequest(ClickRequest request) {
        try {
            Path path = new Path();

            // لمس ثابت؛ بدون حرکت یک‌پیکسلی
            path.moveTo(request.x, request.y);
            path.lineTo(request.x, request.y);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(
                            path,
                            0,
                            request.touchDurationMs
                    );

            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(stroke)
                            .build();

            activeRequests.add(request);

            boolean accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {
                            finishRequest(request, true, false);
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {
                            finishRequest(request, false, false);
                        }
                    },
                    handler
            );

            if (!accepted) {
                activeRequests.remove(request);
                return false;
            }

            return true;

        } catch (Exception e) {
            activeRequests.remove(request);
            return false;
        }
    }

    private void finishRequest(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
        if (!activeRequests.remove(request)) {
            return;
        }

        notifyCompletion(request, success, expired);
    }

    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
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
        ArrayList<ClickRequest> pendingCopy =
                new ArrayList<>(pending);

        pending.clear();

        for (ClickRequest request : pendingCopy) {
            handler.removeCallbacks(request.runnable);
            notifyCompletion(request, false, true);
        }

        ArrayList<ClickRequest> readyCopy =
                new ArrayList<>(ready);

        ready.clear();

        for (ClickRequest request : readyCopy) {
            notifyCompletion(request, false, true);
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
