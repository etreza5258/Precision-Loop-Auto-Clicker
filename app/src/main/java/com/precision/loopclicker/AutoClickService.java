
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

    public interface GestureCompletion {
        void onFinished(boolean success, boolean expired);
    }

    private static volatile AutoClickService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    // درخواست‌ها به ترتیب زمانی وارد این صف می‌شوند.
    private final ArrayDeque<ClickRequest> pending =
            new ArrayDeque<>();

    // فقط برای پیگیری نتیجه ژست‌های ارسال‌شده است.
    // این مجموعه مانع ارسال ژست جدید نمی‌شود.
    private final HashSet<ClickRequest> activeRequests =
            new HashSet<>();

    private int targetX = 350;
    private int targetY = 700;

    private volatile boolean serviceReady;

    private long scheduledTargetNanos = Long.MAX_VALUE;

    private static final class ClickRequest {

        long targetNanos;
        long deadlineNanos;
        long touchDurationMs;

        int x;
        int y;

        GestureCompletion completion;
    }

    private final Runnable schedulerRunnable =
            this::processDueRequests;

    // چرخه عمر سرویس

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
        serviceReady = false;

        handler.removeCallbacks(schedulerRunnable);
        scheduledTargetNanos = Long.MAX_VALUE;

        while (!pending.isEmpty()) {
            notifyCompletion(
                    pending.pollFirst(),
                    false,
                    true
            );
        }

        Iterator<ClickRequest> iterator =
                activeRequests.iterator();

        while (iterator.hasNext()) {
            ClickRequest request = iterator.next();
            iterator.remove();

            notifyCompletion(
                    request,
                    false,
                    false
            );
        }

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }

    // دسترسی به سرویس

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

    // تنظیم مختصات هدف

    public void setTarget(int x, int y) {
        final int newX = Math.max(0, x);
        final int newY = Math.max(0, y);

        runOnMain(() -> {
            if (targetX == newX && targetY == newY) {
                return;
            }

            targetX = newX;
            targetY = newY;

            getSharedPreferences(
                    "auto_click_target",
                    MODE_PRIVATE
            ).edit()
                    .putInt("target_x", newX)
                    .putInt("target_y", newY)
                    .apply();
        });
    }

    // دریافت درخواست کلیک

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

        ClickRequest request = new ClickRequest();

        request.targetNanos = targetElapsedRealtimeNanos;
        request.deadlineNanos =
                cycleDeadlineElapsedRealtimeNanos;

        request.touchDurationMs = touchDurationMs;

        request.x = targetX;
        request.y = targetY;

        request.completion = completion;

        ClickRequest head = pending.peekFirst();

        // اگر صف خالی و زمان درخواست رسیده باشد،
        // بدون واسطه وارد مرحله ارسال می‌شویم.
        if (head == null &&
                targetElapsedRealtimeNanos <=
                        SystemClock.elapsedRealtimeNanos()) {

            dispatchReadyRequest(request);
            return true;
        }

        // درخواست‌ها به ترتیب زمان هدف وارد صف می‌شوند.
        pending.offerLast(request);

        if (head == null) {
            scheduleHead();
        }

        return true;
    }

    // زمان‌بندی اولین درخواست صف

    private void scheduleHead() {
        ClickRequest head = pending.peekFirst();

        if (head == null) {
            if (scheduledTargetNanos != Long.MAX_VALUE) {
                handler.removeCallbacks(schedulerRunnable);
                scheduledTargetNanos = Long.MAX_VALUE;
            }

            return;
        }

        if (scheduledTargetNanos == head.targetNanos) {
            return;
        }

        handler.removeCallbacks(schedulerRunnable);

        scheduledTargetNanos = head.targetNanos;

        long remaining =
                head.targetNanos -
                        SystemClock.elapsedRealtimeNanos();

        if (remaining <= 0) {
            handler.post(schedulerRunnable);
            return;
        }

        // Handler بر اساس میلی‌ثانیه زمان‌بندی می‌کند.
        // گرد کردن رو به بالا از ارسال زودتر از زمان هدف جلوگیری می‌کند.
        long delayMs = remaining / NANOS_PER_MS;

        if (remaining % NANOS_PER_MS != 0) {
            delayMs++;
        }

        handler.postDelayed(
                schedulerRunnable,
                delayMs
        );
    }

    // پردازش تمام درخواست‌های سررسیدشده

    private void processDueRequests() {
        scheduledTargetNanos = Long.MAX_VALUE;

        while (true) {
            ClickRequest request = pending.peekFirst();

            if (request == null) {
                break;
            }

            if (SystemClock.elapsedRealtimeNanos() <
                    request.targetNanos) {
                break;
            }

            pending.pollFirst();

            // به تکمیل ژست قبلی وابسته نیست.
            dispatchReadyRequest(request);
        }

        scheduleHead();
    }

    // بررسی و ارسال ژست

    private void dispatchReadyRequest(ClickRequest request) {
        if (SystemClock.elapsedRealtimeNanos() >=
                request.deadlineNanos) {

            notifyCompletion(
                    request,
                    false,
                    true
            );

            return;
        }

        if (!dispatchRequest(request)) {
            notifyCompletion(
                    request,
                    false,
                    false
            );
        }
    }

    private boolean dispatchRequest(ClickRequest request) {
        try {
            Path path = new Path();

            path.moveTo(request.x, request.y);
            path.lineTo(request.x, request.y);

            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(
                                    new GestureDescription.StrokeDescription(
                                            path,
                                            0,
                                            request.touchDurationMs
                                    )
                            )
                            .build();

            activeRequests.add(request);

            boolean accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {
                            finishRequest(
                                    request,
                                    true,
                                    false
                            );
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {
                            finishRequest(
                                    request,
                                    false,
                                    false
                            );
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

    // پایان ژست و گزارش نتیجه

    private void finishRequest(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
        if (!activeRequests.remove(request)) {
            return;
        }

        notifyCompletion(
                request,
                success,
                expired
        );
    }

    private void notifyCompletion(
            ClickRequest request,
            boolean success,
            boolean expired
    ) {
        if (request == null) {
            return;
        }

        GestureCompletion callback = request.completion;

        // جلوگیری از فراخوانی دوباره Callback
        request.completion = null;

        if (callback != null) {
            try {
                callback.onFinished(success, expired);
            } catch (Exception ignored) {
            }
        }
    }

    // لغو درخواست‌های در انتظار

    public void cancelPendingScheduledClick() {
        runOnMain(this::cancelPendingInternal);
    }

    private void cancelPendingInternal() {
        handler.removeCallbacks(schedulerRunnable);
        scheduledTargetNanos = Long.MAX_VALUE;

        while (!pending.isEmpty()) {
            notifyCompletion(
                    pending.pollFirst(),
                    false,
                    true
            );
        }
    }

    // اجرای عملیات روی نخ اصلی

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
