
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickService extends AccessibilityService {

    private static final String TAG = "PrecisionAutoClick";

    // مدت هر لمس؛ اندروید برای Stroke مدت مثبت می‌خواهد.
    private static final long TAP_DURATION_MS = 1L;

    // فاصله صفر به معنی سریع‌ترین فاصله متوالی قابل زمان‌بندی است.
    private static final long MIN_SEQUENTIAL_INTERVAL_MS = 1L;

    private static volatile AutoClickService instance;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private volatile int targetX = 500;
    private volatile int targetY = 500;

    private volatile boolean gestureInProgress = false;
    private volatile boolean serviceReady = false;

    private Runnable activeCompletion;

    private long activeGestureId = 0;
    private long dispatchStartNanos = 0;

    // تعداد لمس‌های موجود در Gesture فعال
    private int activeBatchClickCount = 0;

    private long activeBatchIntervalMs = 0;

    // زمان مجاز شروع کلیک بعدی بر اساس زمان‌بندی Strokeهای Gesture قبلی
    private long nextAllowedClickNanos = 0;

    // زمان‌بندی تطبیقی تک‌کلیک‌ها. این تخمین از زمان dispatch تا callback
    // ساخته می‌شود و «زمان دقیق شروع لمس» نیست؛ فقط یک پیش‌بینی محافظه‌کارانه است.
    private Runnable pendingScheduledClick;
    private long scheduledClickGeneration = 0;
    private long activeScheduledTargetNanos = 0;
    private boolean activeIsScheduledSingleClick = false;
    private volatile long averageCompletionOverheadNanos = 0;
    private volatile long adaptiveDispatchLeadNanos = 0;
    private volatile long adaptiveSamples = 0;

    private static final long MAX_ADAPTIVE_LEAD_NANOS = 5_000_000L;
    private static final long MIN_ADAPTIVE_LEAD_NANOS = 0L;

    // نتیجه آخرین تلاش برای ارسال دسته؛ FloatingService از این مقادیر
    // برای ثبت وضعیت زرد گزارش استفاده خواهد کرد.
    private volatile boolean lastBatchRejectedForInterval = false;
    private volatile int lastBatchSkippedByDeadline = 0;
    private volatile int lastBatchRejectedClickCount = 0;

    private volatile long successfulClicks = 0;
    private volatile long failedClicks = 0;

    // این مقادیر زمان پاسخ کل Gesture هستند، نه زمان تماس انگشت.
    private volatile long lastGestureLatencyNanos = 0;
    private volatile long totalGestureLatencyNanos = 0;
    private volatile long maxGestureLatencyNanos = 0;
    private volatile long measuredGestures = 0;

    private volatile String lastError =
            "هنوز کلیکی ارسال نشده";

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
        serviceReady = true;

        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // رویدادهای صفحه موردنیاز نیستند.
    }

    @Override
    public void onInterrupt() {

        cancelPendingScheduledClick();
        handler.post(() -> {

            if (gestureInProgress) {
                finishGesture(
                        activeGestureId,
                        false,
                        "Gesture توسط اندروید متوقف شد"
                );
            }
        });
    }

    @Override
    public void onDestroy() {

        cancelPendingScheduledClick();
        serviceReady = false;

        if (Looper.myLooper() == Looper.getMainLooper()) {

            if (gestureInProgress) {
                finishGesture(
                        activeGestureId,
                        false,
                        "سرویس بسته شد"
                );
            }

        } else {

            handler.post(() -> {

                if (gestureInProgress) {
                    finishGesture(
                            activeGestureId,
                            false,
                            "سرویس بسته شد"
                    );
                }
            });
        }

        if (instance == this) {
            instance = null;
        }

        Log.i(TAG, "Accessibility service destroyed");

        super.onDestroy();
    }

    // ---------------------------------------------------------
    // وضعیت سرویس و مختصات
    // ---------------------------------------------------------

    public boolean isServiceReady() {
        return serviceReady;
    }

    public void setTarget(int x, int y) {

        targetX = Math.max(0, x);
        targetY = Math.max(0, y);
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

    // ---------------------------------------------------------
    // شمارنده‌ها و گزارش‌ها
    // ---------------------------------------------------------

    public long getSuccessfulClicks() {
        return successfulClicks;
    }

    public long getFailedClicks() {
        return failedClicks;
    }

    public String getLastError() {
        return lastError;
    }

    public long getLastGestureLatencyNanos() {
        return lastGestureLatencyNanos;
    }

    public long getMaxGestureLatencyNanos() {
        return maxGestureLatencyNanos;
    }

    public long getAverageGestureLatencyNanos() {

        if (measuredGestures == 0) {
            return 0;
        }

        return totalGestureLatencyNanos / measuredGestures;
    }

    public long getMeasuredGestures() {
        return measuredGestures;
    }

    /** تخمین محافظه‌کارانه پیش‌ارسال؛ این مقدار زمان شروع واقعی لمس نیست. */
    public long getAdaptiveDispatchLeadNanos() {
        return adaptiveDispatchLeadNanos;
    }

    public long getAdaptiveSamples() {
        return adaptiveSamples;
    }

    /**
     * لغو درخواست تک‌کلیکی که هنوز dispatch نشده است.
     * Gestureای که قبلاً به اندروید تحویل شده باشد با این متد لغو نمی‌شود.
     */
    public void cancelPendingScheduledClick() {
        scheduledClickGeneration++;
        if (pendingScheduledClick != null) {
            handler.removeCallbacks(pendingScheduledClick);
            pendingScheduledClick = null;
        }
    }

    public boolean wasLastBatchRejectedForInterval() {
        return lastBatchRejectedForInterval;
    }

    public int getLastBatchSkippedByDeadline() {
        return lastBatchSkippedByDeadline;
    }

    public int getLastBatchRejectedClickCount() {
        return lastBatchRejectedClickCount;
    }

    public void resetClickCounters() {

        successfulClicks = 0;
        failedClicks = 0;

        lastGestureLatencyNanos = 0;
        totalGestureLatencyNanos = 0;
        maxGestureLatencyNanos = 0;
        measuredGestures = 0;

        nextAllowedClickNanos = 0;
        lastBatchRejectedForInterval = false;
        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedClickCount = 0;

        lastError = "آماده";
    }

    // ---------------------------------------------------------
    // محاسبه ظرفیت اجرای دسته‌ای
    // ---------------------------------------------------------

    public long getEffectiveClickIntervalMs(long requestedIntervalMs) {

        if (requestedIntervalMs < 0) {
            return -1;
        }

        return Math.max(
                MIN_SEQUENTIAL_INTERVAL_MS,
                requestedIntervalMs
        );
    }

    public int getMaxBatchClickCount(long intervalMs) {

        long effectiveInterval =
                getEffectiveClickIntervalMs(intervalMs);

        if (effectiveInterval <= 0) {
            return 0;
        }

        int maxStrokes =
                GestureDescription.getMaxStrokeCount();

        long maxDuration =
                GestureDescription.getMaxGestureDuration();

        if (maxStrokes <= 0
                || maxDuration < TAP_DURATION_MS) {
            return 0;
        }

        long maxByDuration =
                ((maxDuration - TAP_DURATION_MS)
                        / effectiveInterval) + 1L;

        long result = Math.min(
                (long) maxStrokes,
                maxByDuration
        );

        return (int) Math.max(
                1L,
                Math.min(result, Integer.MAX_VALUE)
        );
    }

    // ---------------------------------------------------------
    // اجرای یک کلیک
    // ---------------------------------------------------------

    public void performClick(Runnable completion) {

        performClickBatch(
                1,
                0,
                completion
        );
    }

    // ---------------------------------------------------------
    // اجرای چند کلیک در یک Gesture
    // ---------------------------------------------------------

    public int performClickBatch(
            int requestedCount,
            long requestedIntervalMs,
            Runnable completion
    ) {
        return performClickBatch(
                requestedCount,
                requestedIntervalMs,
                Long.MAX_VALUE,
                completion
        );
    }

    /**
     * ارسال دسته کلیک با مهلت پایان چرخه.
     *
     * deadlineElapsedRealtimeMs زمان مطلق پایان چرخه بر اساس
     * SystemClock.elapsedRealtime() است. برای استفاده بدون محدودیت،
     * مقدار Long.MAX_VALUE ارسال می‌شود.
     */
    public int performClickBatch(
            int requestedCount,
            long requestedIntervalMs,
            long deadlineElapsedRealtimeMs,
            Runnable completion
    ) {

        if (Looper.myLooper() != Looper.getMainLooper()) {

            handler.post(() ->
                    performClickBatch(
                            requestedCount,
                            requestedIntervalMs,
                            deadlineElapsedRealtimeMs,
                            completion
                    )
            );

            return 0;
        }

        lastBatchRejectedForInterval = false;
        lastBatchSkippedByDeadline = 0;
        lastBatchRejectedClickCount = 0;

        if (!serviceReady) {

            lastError =
                    "سرویس دسترسی‌پذیری آماده نیست";

            Log.e(TAG, lastError);

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        if (gestureInProgress) {

            lastError =
                    "Gesture قبلی هنوز تمام نشده است";

            lastBatchRejectedClickCount = Math.max(0, requestedCount);

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        if (requestedCount <= 0
                || requestedIntervalMs < 0) {

            lastError =
                    "تعداد یا فاصله کلیک معتبر نیست";

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        final long effectiveInterval =
                getEffectiveClickIntervalMs(requestedIntervalMs);

        if (effectiveInterval <= 0) {

            lastError = "فاصله مؤثر کلیک معتبر نیست";

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        final long intervalNanos =
                effectiveInterval * 1_000_000L;

        final long nowNanos =
                SystemClock.elapsedRealtimeNanos();

        // کنترل فاصله واقعی بین شروع Stroke آخر دسته قبلی
        // و شروع اولین Stroke دسته جدید.
        if (nextAllowedClickNanos > 0
                && nowNanos < nextAllowedClickNanos) {

            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = 1;
            lastError =
                    "کلیک به علت کمتر بودن فاصله واقعی از مقدار تنظیم‌شده لغو شد";

            Log.w(
                    TAG,
                    lastError
                            + " | remainingNanos="
                            + (nextAllowedClickNanos - nowNanos)
            );

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        final int maxBatchCount =
                getMaxBatchClickCount(requestedIntervalMs);

        if (maxBatchCount <= 0) {

            lastError =
                    "ظرفیت اجرای Gesture معتبر نیست";

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        // ابتدا تعداد Strokeهایی را محاسبه می‌کنیم که با مهلت چرخه
        // کاملاً جا می‌شوند. شرط پایان شامل مدت خود لمس نیز هست.
        int deadlineAllowedCount = requestedCount;

        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE) {

            long deadlineNanos;

            if (deadlineElapsedRealtimeMs > Long.MAX_VALUE / 1_000_000L) {
                deadlineNanos = Long.MAX_VALUE;
            } else if (deadlineElapsedRealtimeMs < 0) {
                deadlineNanos = 0;
            } else {
                deadlineNanos =
                        deadlineElapsedRealtimeMs * 1_000_000L;
            }

            long remainingNanos = deadlineNanos - nowNanos;
            long tapNanos = TAP_DURATION_MS * 1_000_000L;

            if (remainingNanos < tapNanos) {

                deadlineAllowedCount = 0;

            } else {

                long countByDeadline =
                        ((remainingNanos - tapNanos) / intervalNanos) + 1L;

                deadlineAllowedCount = (int) Math.min(
                        (long) requestedCount,
                        Math.max(0L, countByDeadline)
                );
            }
        }

        if (deadlineAllowedCount < requestedCount) {
            lastBatchSkippedByDeadline =
                    requestedCount - deadlineAllowedCount;
        }

        final int batchCount = Math.min(
                Math.min(requestedCount, maxBatchCount),
                deadlineAllowedCount
        );

        if (batchCount <= 0) {

            lastError =
                    "کلیک به دلیل نرسیدن زمان کافی تا پایان چرخه ارسال نشد";

            Log.w(
                    TAG,
                    lastError
                            + " | skipped="
                            + lastBatchSkippedByDeadline
            );

            if (completion != null) {
                completion.run();
            }

            return 0;
        }

        final int clickX = targetX;
        final int clickY = targetY;

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        try {

            for (int i = 0; i < batchCount; i++) {

                long startOffset =
                        (long) i * effectiveInterval;

                Path path = new Path();
                path.moveTo(clickX, clickY);

                GestureDescription.StrokeDescription stroke =
                        new GestureDescription.StrokeDescription(
                                path,
                                startOffset,
                                TAP_DURATION_MS
                        );

                builder.addStroke(stroke);
            }

        } catch (Exception e) {

            lastError =
                    "خطا در ساخت Gesture: " + e.getMessage();

            Log.e(TAG, lastError, e);

            failedClicks += batchCount;

            if (completion != null) {
                completion.run();
            }

            return batchCount;
        }

        final GestureDescription gesture;

        try {

            gesture = builder.build();

        } catch (Exception e) {

            lastError =
                    "خطا در آماده‌سازی Gesture: "
                            + e.getMessage();

            Log.e(TAG, lastError, e);

            failedClicks += batchCount;

            if (completion != null) {
                completion.run();
            }

            return batchCount;
        }

        // کنترل نهایی درست پیش از dispatch؛ اگر ساخت Gesture باعث
        // عبور از مهلت شده باشد، هیچ Strokeای از این دسته ارسال نمی‌شود.
        final long dispatchCheckNanos =
                SystemClock.elapsedRealtimeNanos();

        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE) {

            long deadlineNanos =
                    deadlineElapsedRealtimeMs > Long.MAX_VALUE / 1_000_000L
                            ? Long.MAX_VALUE
                            : Math.max(0L, deadlineElapsedRealtimeMs)
                                    * 1_000_000L;

            long lastStrokeEndNanos =
                    dispatchCheckNanos
                            + ((long) (batchCount - 1)
                            * intervalNanos)
                            + (TAP_DURATION_MS * 1_000_000L);

            if (lastStrokeEndNanos > deadlineNanos) {

                lastBatchSkippedByDeadline =
                        Math.max(
                                lastBatchSkippedByDeadline,
                                requestedCount
                        );

                lastError =
                        "دسته کلیک به دلیل عبور احتمالی از مهلت چرخه ارسال نشد";

                Log.w(TAG, lastError);

                if (completion != null) {
                    completion.run();
                }

                return 0;
            }
        }

        gestureInProgress = true;

        activeCompletion = completion;
        activeBatchClickCount = batchCount;
        activeBatchIntervalMs = effectiveInterval;

        final long gestureId = ++activeGestureId;

        dispatchStartNanos =
                SystemClock.elapsedRealtimeNanos();

        // آخرین کنترل مهلت، بلافاصله پیش از فراخوانی dispatchGesture.
        // در صورت تمام شدن زمان، هیچ کلیکی از این دسته ارسال نمی‌شود.
        if (deadlineElapsedRealtimeMs != Long.MAX_VALUE) {

            long deadlineNanos =
                    deadlineElapsedRealtimeMs > Long.MAX_VALUE / 1_000_000L
                            ? Long.MAX_VALUE
                            : Math.max(0L, deadlineElapsedRealtimeMs)
                                    * 1_000_000L;

            long lastStrokeEndNanos =
                    dispatchStartNanos
                            + ((long) (batchCount - 1) * intervalNanos)
                            + (TAP_DURATION_MS * 1_000_000L);

            if (lastStrokeEndNanos > deadlineNanos) {

                gestureInProgress = false;
                activeCompletion = null;
                activeBatchClickCount = 0;
                activeBatchIntervalMs = 0;

                lastBatchSkippedByDeadline =
                        Math.max(lastBatchSkippedByDeadline, requestedCount);

                lastError =
                        "دسته کلیک به دلیل عبور از مهلت چرخه ارسال نشد";

                Log.w(TAG, lastError);

                if (completion != null) {
                    completion.run();
                }

                return 0;
            }
        }

        // زمان مجاز کلیک بعدی پس از آخرین Stroke این دسته.
        nextAllowedClickNanos =
                dispatchStartNanos + ((long) batchCount * intervalNanos);

        Log.d(
                TAG,
                "Dispatch batch"
                        + " | count=" + batchCount
                        + " | requestedInterval="
                        + requestedIntervalMs
                        + " | effectiveInterval="
                        + effectiveInterval
                        + " | deadline="
                        + deadlineElapsedRealtimeMs
                        + " | x=" + clickX
                        + " | y=" + clickY
        );

        boolean accepted = false;

        try {

            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {

                        @Override
                        public void onCompleted(
                                GestureDescription description
                        ) {

                            super.onCompleted(description);

                            finishGesture(
                                    gestureId,
                                    true,
                                    "دسته کلیک با موفقیت تکمیل شد"
                            );
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription description
                        ) {

                            super.onCancelled(description);

                            finishGesture(
                                    gestureId,
                                    false,
                                    "اندروید دسته کلیک را لغو کرد"
                            );
                        }
                    },
                    handler
            );

        } catch (Exception e) {

            lastError =
                    "خطای ارسال Gesture: " + e.getMessage();

            Log.e(TAG, lastError, e);
        }

        if (!accepted) {

            finishGesture(
                    gestureId,
                    false,
                    "dispatchGesture درخواست را نپذیرفت"
            );
        }

        return batchCount;
    }

    // ---------------------------------------------------------
    // زمان‌بندی تطبیقی هر کلیک به‌صورت مستقل
    // ---------------------------------------------------------

    /**
     * زمان‌بندی یک کلیک مستقل بر اساس timestamp مطلق elapsedRealtimeNanos.
     *
     * targetElapsedRealtimeNanos: زمان هدف کلیک.
     * cycleStartElapsedRealtimeNanos: ابتدای مجاز چرخه؛ هیچ dispatchای
     * قبل از این زمان انجام نمی‌شود.
     * cycleDeadlineElapsedRealtimeNanos: پایان چرخه؛ dispatchای که حتی
     * مدت لمس 1ms آن از deadline عبور کند ارسال نخواهد شد.
     *
     * برای کلیک‌های بعد از کلیک اول، درخواست با lead تطبیقی و محدودشده
     * زودتر ارسال می‌شود. کلیک اول در ابتدای چرخه فوراً dispatch می‌شود.
     *
     * مقدار بازگشتی فقط نشان می‌دهد درخواست زمان‌بندی پذیرفته شد؛ تکمیل
     * واقعی از طریق completion اعلام می‌شود.
     */
    public boolean performClickAtTarget(
            long targetElapsedRealtimeNanos,
            long cycleStartElapsedRealtimeNanos,
            long cycleDeadlineElapsedRealtimeNanos,
            Runnable completion
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> performClickAtTarget(
                    targetElapsedRealtimeNanos,
                    cycleStartElapsedRealtimeNanos,
                    cycleDeadlineElapsedRealtimeNanos,
                    completion
            ));
            return true;
        }

        if (!serviceReady) {
            lastError = "سرویس دسترسی‌پذیری آماده نیست";
            if (completion != null) completion.run();
            return false;
        }

        if (gestureInProgress || pendingScheduledClick != null) {
            lastError = "کلیک قبلی هنوز در حال اجرا یا زمان‌بندی است";
            lastBatchRejectedForInterval = true;
            lastBatchRejectedClickCount = 1;
            if (completion != null) completion.run();
            return false;
        }

        if (targetElapsedRealtimeNanos < cycleStartElapsedRealtimeNanos
                || cycleDeadlineElapsedRealtimeNanos <= cycleStartElapsedRealtimeNanos
                || targetElapsedRealtimeNanos >= cycleDeadlineElapsedRealtimeNanos) {
            lastError = "زمان هدف کلیک خارج از محدوده چرخه است";
            lastBatchSkippedByDeadline = 1;
            if (completion != null) completion.run();
            return false;
        }

        final long generation = ++scheduledClickGeneration;
        final boolean isFirstCycleClick =
                targetElapsedRealtimeNanos == cycleStartElapsedRealtimeNanos;

        final long leadNanos = isFirstCycleClick
                ? 0L
                : Math.max(MIN_ADAPTIVE_LEAD_NANOS,
                        Math.min(MAX_ADAPTIVE_LEAD_NANOS,
                                adaptiveDispatchLeadNanos));

        final long desiredDispatchNanos = Math.max(
                cycleStartElapsedRealtimeNanos,
                targetElapsedRealtimeNanos - leadNanos
        );

        pendingScheduledClick = new Runnable() {
            @Override
            public void run() {
                if (generation != scheduledClickGeneration) return;

                long now = SystemClock.elapsedRealtimeNanos();
                if (now < cycleStartElapsedRealtimeNanos) {
                    postAgain(Math.max(1L,
                            (cycleStartElapsedRealtimeNanos - now + 999_999L) / 1_000_000L));
                    return;
                }

                if (now < desiredDispatchNanos) {
                    postAgain(Math.max(1L,
                            (desiredDispatchNanos - now + 999_999L) / 1_000_000L));
                    return;
                }

                // اگر کلیک بعد از زمان هدف رسیده باشد، آن را به‌زور و با تأخیر
                // اجرا نمی‌کنیم؛ چنین کلیکی از دست‌رفته محسوب می‌شود.
                if (!isFirstCycleClick && now > targetElapsedRealtimeNanos) {
                    pendingScheduledClick = null;
                    lastBatchSkippedByDeadline = 1;
                    lastError = "زمان هدف کلیک سپری شد؛ کلیک ارسال نشد";
                    if (completion != null) completion.run();
                    return;
                }

                long tapEnd = now + TAP_DURATION_MS * 1_000_000L;
                if (now >= cycleDeadlineElapsedRealtimeNanos
                        || tapEnd > cycleDeadlineElapsedRealtimeNanos) {
                    pendingScheduledClick = null;
                    lastBatchSkippedByDeadline = 1;
                    lastError = "زمان کافی تا پایان چرخه باقی نمانده است";
                    if (completion != null) completion.run();
                    return;
                }

                pendingScheduledClick = null;
                dispatchScheduledSingleClick(
                        targetElapsedRealtimeNanos,
                        completion
                );
            }

            private void postAgain(long delayMs) {
                handler.postDelayed(this, delayMs);
            }
        };

        long now = SystemClock.elapsedRealtimeNanos();
        long delayMs = desiredDispatchNanos <= now
                ? 0L
                : (desiredDispatchNanos - now + 999_999L) / 1_000_000L;

        if (delayMs == 0L) {
            pendingScheduledClick.run();
        } else {
            handler.postDelayed(pendingScheduledClick, delayMs);
        }

        return true;
    }

    private void dispatchScheduledSingleClick(
            long targetElapsedRealtimeNanos,
            Runnable completion
    ) {
        if (!serviceReady || gestureInProgress) {
            lastError = "سرویس برای ارسال کلیک آماده نیست";
            if (completion != null) completion.run();
            return;
        }

        final long now = SystemClock.elapsedRealtimeNanos();
        final int clickX = targetX;
        final int clickY = targetY;

        Path path = new Path();
        path.moveTo(clickX, clickY);

        final GestureDescription gesture;
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(
                    path, 0L, TAP_DURATION_MS));
            gesture = builder.build();
        } catch (Exception e) {
            lastError = "خطا در ساخت تک‌کلیک: " + e.getMessage();
            failedClicks++;
            if (completion != null) completion.run();
            return;
        }

        gestureInProgress = true;
        activeCompletion = completion;
        activeBatchClickCount = 1;
        activeBatchIntervalMs = 0L;
        activeScheduledTargetNanos = targetElapsedRealtimeNanos;
        activeIsScheduledSingleClick = true;
        dispatchStartNanos = now;
        nextAllowedClickNanos = now + TAP_DURATION_MS * 1_000_000L;
        final long gestureId = ++activeGestureId;

        Log.d(TAG, "Dispatch adaptive single click"
                + " | targetNanos=" + targetElapsedRealtimeNanos
                + " | dispatchNanos=" + now
                + " | leadNanos=" + adaptiveDispatchLeadNanos
                + " | x=" + clickX + " | y=" + clickY);

        boolean accepted = false;
        try {
            accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription description) {
                            super.onCompleted(description);
                            finishGesture(gestureId, true,
                                    "تک‌کلیک تطبیقی تکمیل شد");
                        }

                        @Override
                        public void onCancelled(GestureDescription description) {
                            super.onCancelled(description);
                            finishGesture(gestureId, false,
                                    "اندروید تک‌کلیک را لغو کرد");
                        }
                    },
                    handler
            );
        } catch (Exception e) {
            lastError = "خطای ارسال تک‌کلیک: " + e.getMessage();
            Log.e(TAG, lastError, e);
        }

        if (!accepted) {
            finishGesture(gestureId, false,
                    "اندروید درخواست تک‌کلیک را نپذیرفت");
        }
    }

    private void updateAdaptiveTimingEstimate(long gestureLatencyNanos) {
        // callback پس از پایان Gesture می‌رسد؛ بنابراین این نمونه شامل
        // زمان اجرا و بازگشت callback است و زمان شروع تماس را جدا نمی‌کند.
        long overhead = Math.max(0L,
                gestureLatencyNanos - TAP_DURATION_MS * 1_000_000L);

        if (adaptiveSamples == 0) {
            averageCompletionOverheadNanos = overhead;
        } else {
            averageCompletionOverheadNanos =
                    (averageCompletionOverheadNanos * 3L + overhead) / 4L;
        }
        adaptiveSamples++;

        // فقط بخش کوچکی از overhead به‌عنوان lead استفاده می‌شود تا
        // خطر اجرای زودهنگام لمس محدود بماند. سقف lead برابر 5ms است.
        adaptiveDispatchLeadNanos = Math.max(
                MIN_ADAPTIVE_LEAD_NANOS,
                Math.min(MAX_ADAPTIVE_LEAD_NANOS,
                        averageCompletionOverheadNanos / 8L)
        );
    }

    // ---------------------------------------------------------
    // پایان Gesture
    // ---------------------------------------------------------

    private void finishGesture(
            long gestureId,
            boolean success,
            String message
    ) {

        if (Looper.myLooper() != Looper.getMainLooper()) {

            handler.post(() ->
                    finishGesture(
                            gestureId,
                            success,
                            message
                    )
            );

            return;
        }

        if (!gestureInProgress
                || gestureId != activeGestureId) {
            return;
        }

        long latency = Math.max(
                0,
                SystemClock.elapsedRealtimeNanos()
                        - dispatchStartNanos
        );

        lastGestureLatencyNanos = latency;
        measuredGestures++;

        if (activeIsScheduledSingleClick) {
            updateAdaptiveTimingEstimate(latency);
        }

        if (totalGestureLatencyNanos
                <= Long.MAX_VALUE - latency) {

            totalGestureLatencyNanos += latency;
        }

        maxGestureLatencyNanos =
                Math.max(
                        maxGestureLatencyNanos,
                        latency
                );

        int completedBatchCount =
                activeBatchClickCount;

        if (success) {

            successfulClicks += completedBatchCount;
            lastError = message;

            Log.d(
                    TAG,
                    message
                            + " | batchClicks="
                            + completedBatchCount
                            + " | interval="
                            + activeBatchIntervalMs
                            + " | latencyNanos="
                            + latency
            );

        } else {

            failedClicks += completedBatchCount;
            lastError = message;

            Log.e(
                    TAG,
                    message
                            + " | batchClicks="
                            + completedBatchCount
                            + " | latencyNanos="
                            + latency
            );
        }

        gestureInProgress = false;

        activeBatchClickCount = 0;
        activeBatchIntervalMs = 0;
        activeIsScheduledSingleClick = false;
        activeScheduledTargetNanos = 0L;

        Runnable callback = activeCompletion;
        activeCompletion = null;

        if (callback != null) {
            callback.run();
        }
    }
}
