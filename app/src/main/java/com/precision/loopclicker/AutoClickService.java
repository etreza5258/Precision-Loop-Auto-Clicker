
package com.precision.loopclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.*;
import android.view.accessibility.AccessibilityEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class AutoClickService extends AccessibilityService {
    private static final long NS_MS=1_000_000L,LEAD_MS=12,LEAD_NS=LEAD_MS*NS_MS,SPIN_NS=1_000_000L,MAX_DURATION_MS=GestureDescription.getMaxGestureDuration();
    private static final int MAX_STROKES=GestureDescription.getMaxStrokeCount(),MAX_PREPARED=20;
    private static final String PREFS="auto_click_target",KEY_X="target_x",KEY_Y="target_y",LOG_FILE="PrecisionClickTiming_v5.txt",ATTEMPT_LOG_FILE="PrecisionClickAttempts_v1.csv";
    private static final int DEFAULT_X=350,DEFAULT_Y=700;
    private static final AtomicLong ATTEMPT_IDS=new AtomicLong();
    private static volatile AutoClickService instance;
    public interface GestureCompletion { void onFinished(boolean success,boolean expired); }
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile HandlerThread callbackThread;
    private volatile Handler callbackHandler;
    private final Object lock=new Object();
    private final ArrayDeque<Request> pending=new ArrayDeque<>(128);
    private final ArrayList<Request> building=new ArrayList<>();
    private final ArrayList<Request> active=new ArrayList<>(64);
    private final ArrayList<Request> callbackPending=new ArrayList<>(64);
    private final LinkedBlockingQueue<Timing> logs=new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Attempt> attemptLogs=new LinkedBlockingQueue<>();
    private final PriorityQueue<PreparedBatch> prepared=new PriorityQueue<>(MAX_PREPARED,Comparator.comparingLong(b->b.baseTarget));
    private volatile boolean ready,stopping,writerStopping;
    private volatile int x=DEFAULT_X,y=DEFAULT_Y;
    private volatile long generation,lastCallbackElapsed,lastSchedulerUnparkElapsed=-1;
    private volatile Thread scheduler,worker,writerThread;
    private volatile ExecutorService preparer;
    private volatile String logPath="فایل گزارش هنوز ساخته نشده است",attemptLogPath="فایل گزارش تلاش‌ها هنوز ساخته نشده است";
    private boolean dispatching;
    private PreparedBatch workerCurrentBatch;
    private long nextDispatchAllowedElapsedNs;
    private SharedPreferences preferences;

    private static final class Request {
        final long target,cycleStart,deadline
