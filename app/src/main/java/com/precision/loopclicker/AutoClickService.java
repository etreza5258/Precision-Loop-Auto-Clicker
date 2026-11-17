
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayDeque;

public class AutoClickService extends AccessibilityService {

    private static final String TAG = "PrecisionAutoClick";
    private static final String PREF_NAME = "auto_click_target";
    private static final String KEY_TARGET_X = "target_x";
    private static final String KEY_TARGET_Y = "target_y";

    private static final long NS_PER_MS = 1_000_000L;

    public interface DispatchListener {
        void onDispatched(long dispatchElapsedRealtimeNanos);
    }

    private static volatile AutoClickService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final ArrayDeque<ClickRequest> clickQueue =
            new ArrayDeque<>();

    private volatile int targetX = 500;
    private volatile int targetY = 500;
    private volatile boolean serviceReady;
    private volatile boolean gestureInProgress;

    private long queueGeneration;
    private long busyUntilNanos;
    private Runnable pumpRunnable;
    private Runnable releaseRunnable;

    private static final class ClickRequest {
        final long targetNanos;
        final long cycleStartNanos;
        final long deadlineNanos;
        final long touchDurationMs;
        final DispatchListener dispatchListener;
        final Runnable completion;

        ClickRequest(
                long targetNanos,
                long cycleStartNanos,
                long deadlineNanos,
                long touchDurationMs,
                DispatchListener dispatchListener,
                Runnable completion
        ) {
            this.targetNanos = targetNanos;
            this.cycleStartNanos = cycleStartNanos;
            this.deadlineNanos = deadlineNanos;
            this.touchDurationMs = touchDurationMs;
            this.dispatchListener = dispatchListener;
            this.completion = completion;
        }
    }

    public static AutoClickService getInstance() {
        AutoClickService service = instance;

        if (service == null || !service.serviceReady) {
            return null;
        }

        return service;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        instance = this;

        targetX = Math.max(0, getSharedPreferences(
                PREF_NAME, MODE_PRIVATE
        ).getInt(KEY_TARGET_X, 500));

        targetY = Math.max(0, getSharedPreferences(
                PREF_NAME, MODE_PRIVATE
        ).getInt(KEY_TARGET_Y, 500));

        serviceReady = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        runOnMain(() -> {
            clearQueue();
            gestureInProgress = false;
            busyUntilNanos = 0L;
            removeReleaseRunnable();
        });
    }

    @Override
    public void onDestroy() {
        serviceReady = false;

        Runnable cleanup = () -> {
            clearQueue();
            gestureInProgress = false;
            busyUntilNanos = 0L;
            removeReleaseRunnable();
        };

        runOnMain(cleanup);

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }

    public boolean isServiceReady() {
        return serviceReady;
    }

    public void setTarget(int x, int y) {
        targetX = Math.max(0, x);
        targetY = Math.max(0, y);

        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_TARGET_X, targetX)
                .putInt(KEY_TARGET_Y, targetY)
                .apply();
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetY() {
        return targetY;
    }

    public boolean isGestureInProgress() {
        return gestureInProgress;
    }

    /**
     * ثبت کلیک در صف زمان‌بندی.
     * زمان‌بندی کلیک‌های بعدی به Callback اندروید وابسته نیست.
     */
    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            Runnable completion
    ) {
        return performClickAtTarget(
                targetElapsedRealtimeNanos,
                cycleStartElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                1L,
                null,
                completion
        );
    }

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            DispatchListener dispatchListener,
            Runnable completion
    ) {
        return performClickAtTarget(
                targetElapsedRealtimeNanos,
                cycleStartElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                1L,
                dispatchListener,
                completion
        );
    }

    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            long touchDurationMs,
            DispatchListener dispatchListener,
            Runnable completion
    ) {
        if (!serviceReady) {
            return false;
        }

        if (touchDurationMs < 1L
                || touchDurationMs
                > GestureDescription.getMaxGestureDuration()) {
            return false;
        }

        if (cycleDeadlineElapsedRealtimeNanos
                <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                < cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos
                >= cycleDeadlineElapsedRealtimeNanos) {
            return false;
        }

        ClickRequest request = new ClickRequest(
                targetElapsedRealtimeNanos,
                cycleStartElapsedRealtimeNanos,
                cycleDeadlineElapsedRealtimeNanos,
                touchDurationMs,
                dispatchListener,
                completion
        );

        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> enqueueRequest(request));
        } else {
            enqueueRequest(request);
        }

        return true;
    }

    private void enqueueRequest(ClickRequest request) {
        if (!serviceReady) {
            completeSafely(request.completion);
            return;
        }

        clickQueue.addLast(request);
        schedulePump();
    }

    /**
     * اجرای صف بدون انتظار برای Callback اندروید.
     */
    private void schedulePump() {
        if (!serviceReady || clickQueue.isEmpty()) {
            return;
        }

        if (pumpRunnable != null) {
            handler.removeCallbacks(pumpRunnable);
        }

        long now = SystemClock.elapsedRealtimeNanos();
        ClickRequest next = clickQueue.peekFirst();

        long allowedTime = Math.max(
                next.targetNanos,
                busyUntilNanos
        );

        long delay = allowedTime <= now
                ? 0L
                : remainingDelayMs(allowedTime, now);

        final long generation = queueGeneration;

        pumpRunnable = () -> {
            pumpRunnable = null;

            if (generation != queueGeneration) {
                return;
            }

            processNextRequest();
        };

        if (delay == 0L) {
            pumpRunnable.run();
        } else {
            handler.postDelayed(pumpRunnable, delay);
        }
    }

    private void processNextRequest() {
        if (!serviceReady || clickQueue.isEmpty()) {
            return;
        }

        long now = SystemClock.elapsedRealtimeNanos();

        if (gestureInProgress && now < busyUntilNanos) {
            schedulePump();
            return;
        }

        gestureInProgress = false;

        ClickRequest request = clickQueue.peekFirst();

        if (request == null) {
            return;
        }

        long dispatchTime = Math.max(
                now,
                request.targetNanos
        );

        if (dispatchTime < request.cycleStartNanos) {
            schedulePump();
            return;
        }

        long touchNanos = multiplySafe(
                request.touchDurationMs,
                NS_PER_MS
        );

        long touchEnd = addSafe(
                dispatchTime,
                touchNanos
        );

        if (dispatchTime >= request.deadlineNanos
                || touchEnd > request.deadlineNanos) {
            clickQueue.removeFirst();
            completeSafely(request.completion);
            schedulePump();
            return;
        }

        if (!dispatchSingleGesture(request, dispatchTime)) {
            clickQueue.removeFirst();
            completeSafely(request.completion);
            schedulePump();
        }
    }

    private boolean dispatchSingleGesture(
            ClickRequest request,
            long dispatchTime
    ) {
        int x = targetX;
        int y = targetY;

        Path path = new Path();
        path.moveTo(x, y);

        final GestureDescription gesture;

        try {
            GestureDescription.Builder builder =
                    new GestureDescription.Builder();

            builder.addStroke(
                    new GestureDescription.StrokeDescription(
                            path,
                            0L,
                            request.touchDurationMs
                    )
            );

            gesture = builder.build();

        } catch (Exception e) {
            Log.e(TAG, "ساخت ژست ناموفق بود", e);
            return false;
        }

        boolean accepted;

        try {
            accepted = dispatchGesture(
                    gesture,
                    null,
                    handler
            );
        } catch (Exception e) {
            Log.e(TAG, "ارسال ژست ناموفق بود", e);
            return false;
        }

        if (!accepted) {
            return false;
        }

        clickQueue.removeFirst();

        gestureInProgress = true;

        long durationNanos = multiplySafe(
                request.touchDurationMs,
                NS_PER_MS
        );

        busyUntilNanos = addSafe(
                dispatchTime,
                durationNanos
        );

        if (request.dispatchListener != null) {
            try {
                request.dispatchListener.onDispatched(
                        SystemClock.elapsedRealtimeNanos()
                );
            } catch (Exception e) {
                Log.e(TAG, "Dispatch listener error", e);
            }
        }

        scheduleRelease(request, busyUntilNanos);
        return true;
    }

    /**
     * آزادسازی قفل بر اساس مدت اعلام‌شده ژست.
     * این بخش به Callback اندروید وابسته نیست.
     */
    private void scheduleRelease(
            ClickRequest request,
            long expectedEndNanos
    ) {
        removeReleaseRunnable();

        final long generation = queueGeneration;

        releaseRunnable = () -> {
            releaseRunnable = null;

            if (generation != queueGeneration) {
                return;
            }

            gestureInProgress = false;
            busyUntilNanos = 0L;

            completeSafely(request.completion);

            schedulePump();
        };

        long now = SystemClock.elapsedRealtimeNanos();

        long delay = expectedEndNanos <= now
                ? 0L
                : remainingDelayMs(expectedEndNanos, now);

        handler.postDelayed(releaseRunnable, delay);
    }

    /**
     * لغو کلیک‌های منتظر در صف.
     * ژستی که قبلاً به اندروید ارسال شده، با این متد متوقف نمی‌شود.
     */
    public void cancelPendingScheduledClick() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::cancelPendingScheduledClick);
            return;
        }

        queueGeneration++;

        if (pumpRunnable != null) {
            handler.removeCallbacks(pumpRunnable);
            pumpRunnable = null;
        }

        while (!clickQueue.isEmpty()) {
            ClickRequest request = clickQueue.removeFirst();
            completeSafely(request.completion);
        }
    }

    private void clearQueue() {
        queueGeneration++;

        if (pumpRunnable != null) {
            handler.removeCallbacks(pumpRunnable);
            pumpRunnable = null;
        }

        while (!clickQueue.isEmpty()) {
            ClickRequest request = clickQueue.removeFirst();
            completeSafely(request.completion);
        }

        removeReleaseRunnable();
    }

    private void removeReleaseRunnable() {
        if (releaseRunnable != null) {
            handler.removeCallbacks(releaseRunnable);
            releaseRunnable = null;
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }

    private void completeSafely(Runnable completion) {
        if (completion == null) {
            return;
        }

        try {
            completion.run();
        } catch (Exception e) {
            Log.e(TAG, "Completion error", e);
        }
    }

    private long remainingDelayMs(
            long targetNanos,
            long nowNanos
    ) {
        if (targetNanos <= nowNanos) {
            return 0L;
        }

        long difference = targetNanos - nowNanos;

        if (difference <= 0L) {
            return 0L;
        }

        return Math.max(
                1L,
                difference / NS_PER_MS
                        + (difference % NS_PER_MS == 0 ? 0L : 1L)
        );
    }

    private long addSafe(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }

        return a + b;
    }

    private long multiplySafe(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }

        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }

        return a * b;
    }
}
