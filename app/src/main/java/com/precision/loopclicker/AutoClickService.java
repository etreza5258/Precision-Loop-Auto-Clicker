
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

    public interface GestureCompletion {
        void onFinished(boolean success,boolean expired);
    }

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
        final long target,cycleStart,deadline,durationMs,generation;
        final int x,y;
        final AtomicBoolean recorded=new AtomicBoolean(),delivered=new AtomicBoolean();
        volatile Path path;
        volatile long offsetMs;
        volatile long prepareStart=-1,prepareEnd=-1,batchBuildStart=-1,batchReady=-1,workerStart=-1,dispatchStart=-1;
        volatile long dispatchDuration=-1,completed=-1,callbackElapsed=-1,previousCallbackElapsed=-1;
        volatile long handoffDelay=-1,lastStrokeEndElapsed=-1,schedulerParkStart=-1,schedulerWakeTarget=-1;
        volatile long schedulerParkReturn=-1,schedulerWakeLateness=-1,schedulerSpinDuration=-1;
        volatile long schedulerLoopEntry=-1,schedulerWakeElapsed=-1,schedulerUnparkElapsed=-1,schedulerDecisionElapsed=-1;
        volatile long schedulerDispatchEntry=-1,lockWaitStart=-1,lockAcquired=-1,dispatchDecision=-1,apiCallStart=-1,apiCallReturn=-1;
        volatile long workerParkStart=-1,workerWakeTarget=-1,workerParkReturn=-1,workerWakeLateness=-1,workerSpinStart=-1,workerSpinEnd=-1;
        volatile int workerQueueDepth=-1;
        volatile boolean accepted;
        volatile String callbackResult="NOT_DISPATCHED";
        volatile GestureCompletion completion;

        Request(long t,long cs,long d,long ms,int px,int py,long gen,GestureCompletion c) {
            target=t;cycleStart=cs;deadline=d;durationMs=ms;x=px;y=py;generation=gen;completion=c;
        }

        void prepare() {
            if(path!=null)return;
            prepareStart=SystemClock.elapsedRealtimeNanos();
            Path p=new Path();
            p.moveTo(x,y);
            p.lineTo(x,y);
            path=p;
            prepareEnd=SystemClock.elapsedRealtimeNanos();
        }
    }

    private static final class PreparedBatch {
        final ArrayList<Request> requests;
        final GestureDescription[] gestures;
        final long baseTarget,generation,cycleStart,deadline;

        PreparedBatch(ArrayList<Request> r,GestureDescription[] g) {
            requests=r;gestures=g;baseTarget=r.get(0).target;generation=r.get(0).generation;
            cycleStart=r.get(0).cycleStart;deadline=r.get(0).deadline;
        }
    }

    private static final class Batch extends GestureResultCallback {
        final AutoClickService owner;
        final ArrayList<Request> requests;
        private boolean returned,accepted,received,success,handled;
        private long callbackTime;

        Batch(AutoClickService o,ArrayList<Request> r) {
            owner=o;requests=r;
        }

        @Override
        public void onCompleted(GestureDescription g) {
            receive(true);
        }

        @Override
        public void onCancelled(GestureDescription g) {
            receive(false);
        }

        private void receive(boolean result) {
            boolean process;
            synchronized(this) {
                if(handled||received)return;
                received=true;success=result;callbackTime=SystemClock.elapsedRealtimeNanos();
                process=returned&&accepted;
                if(process)handled=true;
            }
            if(process)owner.finishBatch(this,result,callbackTime);
        }

        void onDispatchReturned(boolean result) {
            boolean process,value;
            long time;
            synchronized(this) {
                returned=true;accepted=result;process=result&&received&&!handled;
                if(process)handled=true;
                value=success;time=callbackTime;
            }
            if(process)owner.finishBatch(this,value,time);
        }
    }

    private static final class Timing {
        final String row;

        Timing(Request r,boolean success,boolean expired) {
            row=createRow(r,success,expired);
        }

        private static long diff(long a,long b) {
            return a<0||b<0?-1:b-a;
        }

        private static String createRow(Request r,boolean success,boolean expired) {
            long prepare=diff(r.prepareStart,r.prepareEnd),build=diff(r.batchBuildStart,r.batchReady);
            long readyWorker=diff(r.batchReady,r.workerStart);
            long targetWait=r.batchReady<0?-1:Math.max(0,r.target-r.batchReady);
            long previousWait=r.batchReady<0||r.previousCallbackElapsed<0?-1:Math.max(0,r.previousCallbackElapsed-r.batchReady);
            long delta=r.dispatchStart<0?-1:r.dispatchStart-r.target;
            long pipeline=diff(r.workerStart,r.dispatchStart);
            long completionDelay=r.completed<0?-1:r.completed-r.target;
            long dispatchCallback=diff(r.dispatchStart,r.callbackElapsed),strokeCallback=diff(r.lastStrokeEndElapsed,r.callbackElapsed);
            long wakeWorker=diff(r.schedulerParkReturn,r.workerStart),queueDelay=diff(r.schedulerDispatchEntry,r.workerStart);
            long lockWait=diff(r.lockWaitStart,r.lockAcquired),workerDecision=diff(r.workerStart,r.dispatchDecision);
            long decisionApi=diff(r.dispatchDecision,r.apiCallStart),apiDuration=diff(r.apiCallStart,r.apiCallReturn);
            long schedulerWakeDispatch=diff(r.schedulerWakeElapsed,r.schedulerDispatchEntry);
            long schedulerLoopDispatch=diff(r.schedulerLoopEntry,r.schedulerDispatchEntry);
            long unparkWake=diff(r.schedulerUnparkElapsed,r.schedulerWakeElapsed);
            long workerWakeStart=diff(r.workerParkReturn,r.workerStart),workerPark=diff(r.workerParkStart,r.workerParkReturn);
            long workerSpin=diff(r.workerSpinStart,r.workerSpinEnd);
            String result=expired?"EXPIRED":success?"SUCCESS":"FAILED";
            return r.target+","+r.prepareStart+","+r.prepareEnd+","+prepare+","+r.batchBuildStart+","+r.batchReady+","+build+","+r.workerStart+","+readyWorker+","+targetWait+","+previousWait+","+r.dispatchStart+","+delta+","+pipeline+","+r.dispatchDuration+","+r.completed+","+completionDelay+","+dispatchCallback+","+r.accepted+","+result+","+r.callbackResult+","+r.x+","+r.y+","+r.callbackElapsed+","+r.previousCallbackElapsed+","+r.handoffDelay+","+r.lastStrokeEndElapsed+","+strokeCallback+","+r.schedulerParkStart+","+r.schedulerWakeTarget+","+r.schedulerParkReturn+","+r.schedulerWakeLateness+","+r.schedulerSpinDuration+","+wakeWorker+","+queueDelay+","+r.lockWaitStart+","+r.lockAcquired+","+lockWait+","+r.dispatchDecision+","+r.apiCallStart+","+r.apiCallReturn+","+workerDecision+","+decisionApi+","+apiDuration+","+r.schedulerLoopEntry+","+r.schedulerWakeElapsed+","+r.schedulerUnparkElapsed+","+r.schedulerDecisionElapsed+","+r.schedulerDispatchEntry+","+schedulerWakeDispatch+","+schedulerLoopDispatch+","+unparkWake+","+r.workerParkStart+","+r.workerWakeTarget+","+r.workerParkReturn+","+r.workerWakeLateness+","+workerWakeStart+","+workerPark+","+r.workerQueueDepth+","+r.workerSpinStart+","+r.workerSpinEnd+","+workerSpin;
        }

        String csv() {
            return row;
        }
    }

    private static final class Attempt {
        final String row;

        Attempt(String type,long start,long end,List<Request> requests,long apiStart,long apiReturn,boolean accepted,int leadMs,String reason) {
            long id=ATTEMPT_IDS.incrementAndGet();
            long base=requests==null||requests.isEmpty()?-1:requests.get(0).target;
            long cycleStart=requests==null||requests.isEmpty()?-1:requests.get(0).cycleStart;
            long deadline=requests==null||requests.isEmpty()?-1:requests.get(0).deadline;
            int count=requests==null?0:requests.size();
            String safeReason=reason==null?"":reason.replace(',',';').replace('\n',' ').replace('\r',' ');
            row=id+","+type+","+start+","+end+","+(start<0||end<0?-1:end-start)+","+count+","+base+","+cycleStart+","+deadline+","+MAX_STROKES+","+MAX_DURATION_MS+","+apiStart+","+apiReturn+","+accepted+","+leadMs+","+safeReason;
        }
    }

    private void recordAttempt(String type,long start,long end,List<Request> requests,long apiStart,long apiReturn,boolean accepted,int leadMs,String reason) {
        attemptLogs.offer(new Attempt(type,start,end,requests,apiStart,apiReturn,accepted,leadMs,reason));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        preferences=getSharedPreferences(PREFS,MODE_PRIVATE);
        x=preferences.getInt(KEY_X,DEFAULT_X);
        y=preferences.getInt(KEY_Y,DEFAULT_Y);
        stopping=false;writerStopping=false;ready=true;instance=this;
        startWriter();startPreparer();startCallbackThread();startWorker();startScheduler();
    }

    private void startCallbackThread() {
        HandlerThread old=callbackThread;
        if(old!=null&&old.isAlive())return;
        HandlerThread t=new HandlerThread("PrecisionGestureCallback",android.os.Process.THREAD_PRIORITY_DISPLAY);
        t.start();callbackThread=t;callbackHandler=new Handler(t.getLooper());
    }

    private void startWriter() {
        if(writerThread!=null&&writerThread.isAlive())return;
        writerStopping=false;
        writerThread=new Thread(this::writerLoop,"PrecisionTimingFileWriter");
        writerThread.start();
    }

    private void writerLoop() {
        BufferedWriter timingOut=null,attemptOut=null;
        try {
            File dir=getExternalFilesDir(null);
            if(dir==null)dir=getFilesDir();
            if(!dir.exists()&&!dir.mkdirs()) {
                logPath="خطا: پوشه گزارش ساخته نشد";attemptLogPath="خطا: پوشه گزارش ساخته نشد";return;
            }
            File timingFile=new File(dir,LOG_FILE),attemptFile=new File(dir,ATTEMPT_LOG_FILE);
            boolean timingHeader=!timingFile.exists()||timingFile.length()==0;
            boolean attemptHeader=!attemptFile.exists()||attemptFile.length()==0;
            timingOut=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(timingFile,true),StandardCharsets.UTF_8));
            attemptOut=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(attemptFile,true),StandardCharsets.UTF_8));
            logPath=timingFile.getAbsolutePath();attemptLogPath=attemptFile.getAbsolutePath();
            if(timingHeader) {
                timingOut.write("target_elapsed_ns,prepare_start_elapsed_ns,prepare_end_elapsed_ns,prepare_duration_ns,batch_build_start_elapsed_ns,batch_ready_elapsed_ns,batch_build_duration_ns,dispatch_worker_start_elapsed_ns,batch_ready_to_worker_ns,target_wait_ns,previous_callback_wait_ns,dispatch_start_elapsed_ns,dispatch_start_delta_ns,dispatch_pipeline_delay_ns,dispatch_duration_ns,completion_elapsed_ns,completion_delay_ns,dispatch_to_callback_ns,accepted,result,callback_result,x,y,callback_elapsed_ns,previous_callback_elapsed_ns,handoff_delay_ns,last_stroke_end_elapsed_ns,last_stroke_to_callback_ns,scheduler_park_start_ns,scheduler_wake_target_ns,scheduler_park_return_ns,scheduler_wake_lateness_ns,scheduler_spin_duration_ns,scheduler_wake_to_worker_ns,dispatch_worker_queue_delay_ns,dispatch_lock_wait_start_elapsed_ns,dispatch_lock_acquired_elapsed_ns,dispatch_lock_wait_ns,dispatch_decision_elapsed_ns,dispatch_api_call_start_elapsed_ns,dispatch_api_call_return_elapsed_ns,dispatch_worker_to_decision_ns,dispatch_decision_to_api_ns,dispatch_api_duration_ns,scheduler_loop_entry_elapsed_ns,scheduler_wake_elapsed_ns,scheduler_last_unpark_elapsed_ns,scheduler_decision_elapsed_ns,scheduler_dispatch_entry_elapsed_ns,scheduler_wake_to_dispatch_ns,scheduler_loop_to_dispatch_ns,scheduler_unpark_to_wake_ns,worker_park_start_elapsed_ns,worker_wake_target_elapsed_ns,worker_park_return_elapsed_ns,worker_wake_lateness_ns,worker_wake_to_start_ns,worker_park_duration_ns,worker_queue_depth,worker_spin_start_elapsed_ns,worker_spin_end_elapsed_ns,worker_spin_duration_ns");
                timingOut.newLine();timingOut.flush();
            }
            if(attemptHeader) {
                attemptOut.write("attempt_id,attempt_type,start_elapsed_ns,end_elapsed_ns,duration_ns,batch_size,base_target_elapsed_ns,cycle_start_elapsed_ns,deadline_elapsed_ns,max_strokes,max_gesture_duration_ms,api_start_elapsed_ns,api_return_elapsed_ns,accepted,lead_ms,reason");
                attemptOut.newLine();attemptOut.flush();
            }
            while(!writerStopping||!logs.isEmpty()||!attemptLogs.isEmpty()) {
                boolean wrote=false;
                Timing timing=logs.poll();
                if(timing!=null) {
                    timingOut.write(timing.csv());timingOut.newLine();timingOut.flush();wrote=true;
                }
                Attempt attempt=attemptLogs.poll();
                if(attempt!=null) {
                    attemptOut.write(attempt.row);attemptOut.newLine();attemptOut.flush();wrote=true;
                }
                if(!wrote)try {
                    Thread.sleep(20);
                } catch(InterruptedException ignored) {
                }
            }
        } catch(IOException e) {
            logPath="خطای نوشتن گزارش: "+e.getMessage();
        } finally {
            closeWriter(timingOut);closeWriter(attemptOut);
        }
    }

    private static void closeWriter(BufferedWriter writer) {
        if(writer==null)return;
        try {
            writer.flush();writer.close();
        } catch(IOException ignored) {
        }
    }

    private void record(Request r,boolean success,boolean expired) {
        if(r!=null&&r.recorded.compareAndSet(false,true))logs.offer(new Timing(r,success,expired));
    }

    private void startPreparer() {
        if(preparer!=null&&!preparer.isShutdown())return;
        preparer=Executors.newSingleThreadExecutor(task->new Thread(()->{
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch(Exception ignored) {
            }
            task.run();
        },"PrecisionGesturePreparation"));
    }

    private void startWorker() {
        Thread old=worker;
        if(old!=null&&old.isAlive())return;
        worker=new Thread(this::workerLoop,"PrecisionClickDispatchWorker");worker.start();
    }

    private void startScheduler() {
        if(scheduler!=null&&scheduler.isAlive())return;
        scheduler=new Thread(this::schedulerLoop,"PrecisionClickNanoScheduler");scheduler.start();
    }

    private boolean current(Request r) {
        return r!=null&&r.generation==generation&&ready&&!stopping;
    }

    private void enqueueAll(List<Request> requests) {
        ArrayList<Request> sorted=new ArrayList<>(pending.size()+requests.size());
        sorted.addAll(pending);sorted.addAll(requests);
        sorted.sort(Comparator.comparingLong(r->r.target));
        pending.clear();pending.addAll(sorted);
    }

    private void prepareGroup(List<Request> requests) {
        for(Request r:requests) {
            if(!current(r))continue;
            try {
                r.prepare();
            } catch(Exception e) {
                boolean removed;
                synchronized(lock) {
                    removed=pending.remove(r);
                }
                if(removed) {
                    r.callbackResult="PREPARATION_FAILED:"+e.getClass().getSimpleName();
                    postFinish(r,false,false);
                }
            }
        }
        unparkScheduler();
    }

    private void schedulerLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch(Exception ignored) {
        }
        while(!stopping) {
            long loopEntry=SystemClock.elapsedRealtimeNanos();
            Request first;
            boolean canBuild;
            ArrayList<Request> stale=new ArrayList<>();
            synchronized(lock) {
                while(!pending.isEmpty()&&pending.peekFirst().generation!=generation)stale.add(pending.pollFirst());
                canBuild=prepared.size()<MAX_PREPARED;first=pending.peekFirst();
            }
            for(Request r:stale) {
                r.callbackResult="STALE_GENERATION";postFinish(r,false,true);
            }
            if(stopping)break;
            if(first==null||!canBuild) {
                LockSupport.park(this);continue;
            }
            if(!current(first)) {
                unparkScheduler();continue;
            }
            if(first.path==null||hasUnpreparedBatchMember(first)) {
                LockSupport.park(this);continue;
            }
            ArrayList<Request> requests=collect(first);
            if(requests.isEmpty()) {
                LockSupport.park(this);continue;
            }
            synchronized(lock) {
                building.addAll(requests);
            }
            long buildStart=SystemClock.elapsedRealtimeNanos();
            for(Request r:requests) {
                r.batchBuildStart=buildStart;r.schedulerLoopEntry=loopEntry;
                r.schedulerWakeElapsed=loopEntry;r.schedulerUnparkElapsed=lastSchedulerUnparkElapsed;
            }
            try {
                GestureDescription[] gestures=buildGestures(requests);
                long readyTime=SystemClock.elapsedRealtimeNanos();
                recordAttempt("INITIAL_BUILD",buildStart,readyTime,requests,-1,-1,false,-1,"BUILT");
                PreparedBatch batch=new PreparedBatch(requests,gestures);
                boolean queued=false;
                synchronized(lock) {
                    building.removeAll(requests);
                    if(!stopping&&current(requests.get(0))&&prepared.size()<MAX_PREPARED) {
                        for(Request r:requests) {
                            r.batchReady=readyTime;r.schedulerDecisionElapsed=readyTime;r.schedulerDispatchEntry=readyTime;
                        }
                        prepared.offer(batch);queued=true;
                    } else if(!stopping&&current(requests.get(0)))requeueCurrentInOrder(requests);
                }
                if(queued)unparkWorker();else unparkScheduler();
            } catch(Exception e) {
                long end=SystemClock.elapsedRealtimeNanos();
                recordAttempt("INITIAL_BUILD",buildStart,end,requests,-1,-1,false,-1,"BUILD_FAILED:"+e.getClass().getSimpleName());
                synchronized(lock) {
                    building.removeAll(requests);
                }
                finishWithoutDispatch(requests,"BATCH_BUILD_FAILED:"+e.getClass().getSimpleName(),false);
            }
        }
    }

    private boolean hasUnpreparedBatchMember(Request first) {
        synchronized(lock) {
            if(!pending.contains(first)||!current(first))return false;
            int count=0;
            for(Request r:pending) {
                if(r.generation!=first.generation||r.cycleStart!=first.cycleStart||r.deadline!=first.deadline||r.target<first.target)continue;
                if(count>=MAX_STROKES)break;
                long offset=ceilMs(r.target-first.target);
                if(offset>MAX_DURATION_MS-LEAD_MS||r.durationMs>MAX_DURATION_MS-LEAD_MS-offset)continue;
                if(r.path==null)return true;
                count++;
            }
        }
        return false;
    }

    private ArrayList<Request> collect(Request first) {
        ArrayList<Request> result=new ArrayList<>(MAX_STROKES);
        synchronized(lock) {
            if(!pending.contains(first)||!current(first))return result;
            Iterator<Request> iterator=pending.iterator();
            while(iterator.hasNext()&&result.size()<MAX_STROKES) {
                Request r=iterator.next();
                if(r.generation!=first.generation||r.cycleStart!=first.cycleStart||r.deadline!=first.deadline||r.target<first.target||r.path==null)continue;
                long offset=ceilMs(r.target-first.target);
                if(offset>MAX_DURATION_MS-LEAD_MS||r.durationMs>MAX_DURATION_MS-LEAD_MS-offset)continue;
                r.offsetMs=offset;result.add(r);iterator.remove();
            }
        }
        return result;
    }

    private static long ceilMs(long nanos) {
        if(nanos<=0)return 0;
        return nanos/NS_MS+(nanos%NS_MS==0?0:1);
    }

    private void requeueCurrentInOrder(List<Request> requests) {
        for(int i=requests.size()-1;i>=0;i--) {
            Request r=requests.get(i);
            if(current(r)&&!r.delivered.get())pending.addFirst(r);
        }
    }

    private GestureDescription[] buildGestures(List<Request> requests) {
        GestureDescription[] result=new GestureDescription[(int)LEAD_MS+1];
        for(int lead=0;lead<=LEAD_MS;lead++) {
            GestureDescription.Builder builder=new GestureDescription.Builder();
            for(Request r:requests) {
                if(r.path==null)throw new IllegalStateException("PATH_NOT_PREPARED");
                builder.addStroke(new GestureDescription.StrokeDescription(r.path,lead+r.offsetMs,r.durationMs));
            }
            result[lead]=builder.build();
        }
        return result;
    }

    private void purgeFinishedActive(long now) {
        for(int i=active.size()-1;i>=0;i--) {
            Request r=active.get(i);
            if(r.lastStrokeEndElapsed>0&&now>=r.lastStrokeEndElapsed)active.remove(i);
        }
    }

    private void workerLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch(Exception ignored) {
        }
        while(!stopping) {
            PreparedBatch batch=null,observed=null;
            long wakeTarget=-1,waitNanos=-1,now=SystemClock.elapsedRealtimeNanos();
            synchronized(lock) {
                if(stopping)break;
                purgeFinishedActive(now);
                while(!prepared.isEmpty()&&prepared.peek().generation!=generation) {
                    PreparedBatch stale=prepared.poll();
                    for(Request r:stale.requests) {
                        r.callbackResult="STALE_GENERATION";postFinish(r,false,true);
                    }
                }
                if(!dispatching) {
                    observed=prepared.peek();
                    if(observed!=null) {
                        long targetWake=observed.baseTarget-LEAD_NS;
                        wakeTarget=Math.max(targetWake,nextDispatchAllowedElapsedNs);
                        waitNanos=wakeTarget-now;
                        if(waitNanos<=0) {
                            batch=prepared.poll();workerCurrentBatch=batch;
                            int depth=prepared.size();
                            for(Request r:batch.requests) {
                                r.workerStart=now;r.workerQueueDepth=depth;
                            }
                        }
                    }
                }
            }
            if(stopping)break;
            if(batch!=null) {
                dispatchBatch(batch);
                synchronized(lock) {
                    if(workerCurrentBatch==batch)workerCurrentBatch=null;
                }
                unparkScheduler();continue;
            }
            if(waitNanos<0) {
                LockSupport.park(this);continue;
            }
            long parkStart=SystemClock.elapsedRealtimeNanos();
            if(observed!=null)for(Request r:observed.requests) {
                r.workerParkStart=parkStart;r.workerWakeTarget=wakeTarget;
                r.workerParkReturn=-1;r.workerWakeLateness=-1;r.workerSpinStart=-1;r.workerSpinEnd=-1;
            }
            if(waitNanos>SPIN_NS)LockSupport.parkNanos(this,waitNanos-SPIN_NS);
            long parkReturn=SystemClock.elapsedRealtimeNanos();
            if(observed!=null)for(Request r:observed.requests)r.workerParkReturn=parkReturn;
            long spinStart=-1,spinEnd=-1;
            if(observed!=null&&parkReturn<wakeTarget) {
                spinStart=parkReturn;
                long currentTime;
                while(!stopping&&(currentTime=SystemClock.elapsedRealtimeNanos())<wakeTarget) {
                    if(workerQueueChanged(observed))break;
                }
                spinEnd=SystemClock.elapsedRealtimeNanos();
            }
            if(observed!=null) {
                long actual=spinEnd>=0?spinEnd:parkReturn;
                for(Request r:observed.requests) {
                    r.workerWakeLateness=Math.max(0,actual-wakeTarget);
                    r.workerSpinStart=spinStart;r.workerSpinEnd=spinEnd;
                }
            }
            if(observed!=null&&workerQueueChanged(observed))continue;
        }
    }

    private boolean workerQueueChanged(PreparedBatch observed) {
        synchronized(lock) {
            if(stopping)return true;
            return prepared.peek()!=observed;
        }
    }

    private boolean requeueFutureRequests(PreparedBatch batch,long now) {
        ArrayList<Request> future=new ArrayList<>(),expired=new ArrayList<>();
        for(Request r:batch.requests) {
            if(current(r)&&r.target>now)future.add(r);else expired.add(r);
        }
        for(Request r:expired) {
            r.callbackResult="TARGET_MISSED_BEFORE_API";postFinish(r,false,true);
        }
        if(future.isEmpty())return false;
        long base=future.get(0).target;
        for(Request r:future)r.offsetMs=ceilMs(r.target-base);
        long buildStart=SystemClock.elapsedRealtimeNanos();
        try {
            GestureDescription[] gestures=buildGestures(future);
            long readyTime=SystemClock.elapsedRealtimeNanos();
            for(Request r:future) {
                r.batchBuildStart=buildStart;r.batchReady=readyTime;
            }
            boolean queued=false;
            synchronized(lock) {
                if(!stopping&&current(future.get(0))) {
                    prepared.offer(new PreparedBatch(future,gestures));queued=true;
                }
            }
            recordAttempt("REBUILD",buildStart,readyTime,future,-1,-1,false,-1,queued?"REBUILT":"STALE_GENERATION");
            if(!queued) {
                finishWithoutDispatch(future,"STALE_GENERATION",true);return false;
            }
            unparkWorker();return true;
        } catch(Exception e) {
            long end=SystemClock.elapsedRealtimeNanos();
            recordAttempt("REBUILD",buildStart,end,future,-1,-1,false,-1,"REBUILD_FAILED:"+e.getClass().getSimpleName());
            finishWithoutDispatch(future,"REBUILD_FAILED:"+e.getClass().getSimpleName(),false);return false;
        }
    }

    private void dispatchBatch(PreparedBatch batch) {
        long attemptStart=SystemClock.elapsedRealtimeNanos(),workerStart=attemptStart;
        for(Request r:batch.requests)if(r.workerStart<0)r.workerStart=workerStart;
        long lockStart=SystemClock.elapsedRealtimeNanos();
        for(Request r:batch.requests)r.lockWaitStart=lockStart;
        long previous=-1,maxEndOffsetMs=0;
        String failure="",error="";
        boolean accepted=false;
        long apiStart=-1,apiReturn=-1;
        int leadMs=-1;
        Batch callback=new Batch(this,batch.requests);
        synchronized(lock) {
            long acquired=SystemClock.elapsedRealtimeNanos();
            for(Request r:batch.requests)r.lockAcquired=acquired;
            if(stopping||!current(batch.requests.get(0))||dispatching)failure="SERVICE_OR_GENERATION_INVALID";
            else {
                dispatching=true;previous=lastCallbackElapsed>0?lastCallbackElapsed:-1;
                for(Request r:batch.requests) {
                    r.previousCallbackElapsed=previous;
                    r.handoffDelay=previous<0?-1:Math.max(0,workerStart-previous);
                    r.callbackResult="WAITING_FOR_CALLBACK";
                }
            }
            long decision=SystemClock.elapsedRealtimeNanos();
            for(Request r:batch.requests)r.dispatchDecision=decision;
        }
        if(!failure.isEmpty()) {
            for(Request r:batch.requests)r.callbackResult=failure;
            long end=SystemClock.elapsedRealtimeNanos();
            recordAttempt("DISPATCH",attemptStart,end,batch.requests,-1,-1,false,-1,failure);
            finishWithoutDispatch(batch.requests,failure,true);unparkWorker();return;
        }
        boolean missed=false;
        synchronized(lock) {
            apiStart=SystemClock.elapsedRealtimeNanos();
            if(stopping||!current(batch.requests.get(0)))failure="STALE_GENERATION";
            else {
                long delta=batch.baseTarget-apiStart;
                leadMs=delta<0?-1:(int)ceilMs(delta);
                if(leadMs<0)missed=true;
                else if(leadMs>LEAD_MS)failure="DISPATCH_LEAD_OUT_OF_RANGE";
                else {
                    long latestStart=Long.MAX_VALUE;
                    for(Request r:batch.requests) {
                        if(!current(r)) {
                            failure="STALE_GENERATION";break;
                        }
                        long endOffset=leadMs+r.offsetMs+r.durationMs;
                        if(endOffset>MAX_DURATION_MS) {
                            failure="GESTURE_DURATION_LIMIT";break;
                        }
                        long allowedStart=r.deadline-endOffset*NS_MS;
                        latestStart=Math.min(latestStart,allowedStart);
                        maxEndOffsetMs=Math.max(maxEndOffsetMs,endOffset);
                    }
                    if(failure.isEmpty()&&apiStart>latestStart)failure="DEADLINE_BEFORE_API";
                }
            }
            if(missed)dispatching=false;
            else if(failure.isEmpty()) {
                Handler target=callbackHandler;
                if(target==null||stopping)failure="CALLBACK_HANDLER_UNAVAILABLE";
                else {
                    for(Request r:batch.requests) {
                        r.dispatchStart=apiStart;r.apiCallStart=apiStart;
                    }
                    active.addAll(batch.requests);callbackPending.addAll(batch.requests);
                    try {
                        accepted=dispatchGesture(batch.gestures[leadMs],callback,target);
                    } catch(Exception e) {
                        error="DISPATCH_EXCEPTION:"+e.getClass().getSimpleName();
                    }
                }
            }
            apiReturn=SystemClock.elapsedRealtimeNanos();
            for(Request r:batch.requests) {
                r.apiCallReturn=apiReturn;r.dispatchDuration=apiReturn-apiStart;
                r.accepted=accepted;
                if(r.dispatchStart<0)r.dispatchStart=apiStart;
                if(r.apiCallStart<0)r.apiCallStart=apiStart;
            }
            if(accepted) {
                long expectedEnd=apiStart+maxEndOffsetMs*NS_MS;
                nextDispatchAllowedElapsedNs=Math.max(nextDispatchAllowedElapsedNs,expectedEnd);
                for(Request r:batch.requests)r.lastStrokeEndElapsed=expectedEnd;
            } else if(!missed) {
                active.removeAll(batch.requests);callbackPending.removeAll(batch.requests);
            }
            dispatching=false;
        }
        if(missed) {
            recordAttempt("DISPATCH",attemptStart,apiReturn,batch.requests,apiStart,apiReturn,false,leadMs,"TARGET_MISSED_BEFORE_API");
            requeueFutureRequests(batch,apiStart);unparkWorker();return;
        }
        if(accepted) {
            recordAttempt("DISPATCH",attemptStart,apiReturn,batch.requests,apiStart,apiReturn,true,leadMs,"ACCEPTED");
            callback.onDispatchReturned(true);
        } else {
            String reason=!failure.isEmpty()?failure:!error.isEmpty()?error:"API_RETURNED_FALSE";
            recordAttempt("DISPATCH",attemptStart,apiReturn,batch.requests,apiStart,apiReturn,false,leadMs,reason);
            for(Request r:batch.requests)r.callbackResult=reason;
            callback.onDispatchReturned(false);
            finishWithoutDispatch(batch.requests,reason,failure.contains("DEADLINE")||failure.contains("TARGET_MISSED")||failure.contains("STALE"));
        }
        unparkWorker();
    }

    private void finishWithoutDispatch(List<Request> requests,String reason,boolean expired) {
        for(Request r:requests) {
            r.callbackResult=reason;postFinish(r,false,expired);
        }
    }

    private void finishBatch(Batch batch,boolean success,long callbackTime) {
        synchronized(lock) {
            active.removeAll(batch.requests);callbackPending.removeAll(batch.requests);
        }
        lastCallbackElapsed=Math.max(lastCallbackElapsed,callbackTime);
        for(Request r:batch.requests) {
            r.completed=callbackTime;r.callbackElapsed=callbackTime;
            r.callbackResult=success?"ON_COMPLETED":"ON_CANCELLED";
        }
        for(Request r:batch.requests)postFinish(r,success,false);
    }

    private void postFinish(Request r,boolean success,boolean expired) {
        if(Looper.myLooper()==Looper.getMainLooper())finish(r,success,expired);
        else main.post(()->finish(r,success,expired));
    }

    private void finish(Request r,boolean success,boolean expired) {
        if(r==null||!r.delivered.compareAndSet(false,true))return;
        if(r.completed<0)r.completed=SystemClock.elapsedRealtimeNanos();
        record(r,success,expired);
        GestureCompletion c=r.completion;r.completion=null;
        if(c!=null)try {
            c.onFinished(success,expired);
        } catch(Exception ignored) {
        }
    }

    public boolean performClickAtTarget(long targetNanos,long cycleStartNanos,long deadlineNanos,long touchDurationNanos,GestureCompletion completion) {
        if(completion==null)return false;
        return performClickBatchAtTargets(new long[]{targetNanos},cycleStartNanos,deadlineNanos,touchDurationNanos,new GestureCompletion[]{completion});
    }

    public boolean performClickBatchAtTargets(long[] targets,long cycleStartNanos,long deadlineNanos,long touchDurationNanos,GestureCompletion[] completions) {
        if(!ready||stopping||targets==null||completions==null||targets.length==0||targets.length!=completions.length||touchDurationNanos<0||deadlineNanos<=cycleStartNanos)return false;
        long durationMs=toDurationMs(touchDurationNanos);
        if(durationMs>MAX_DURATION_MS)return false;
        final long durationNs;
        try {
            durationNs=Math.multiplyExact(durationMs,NS_MS);
        } catch(ArithmeticException e) {
            return false;
        }
        ArrayList<Request> requests=new ArrayList<>(targets.length);
        long previousTarget=Long.MIN_VALUE;
        int px=x,py=y;
        for(int i=0;i<targets.length;i++) {
            long target=targets[i];
            if(completions[i]==null||target<cycleStartNanos||target>=deadlineNanos||target<previousTarget||durationNs>deadlineNanos-target)return false;
            previousTarget=target;
        }
        synchronized(lock) {
            if(!ready||stopping||preparer==null||preparer.isShutdown())return false;
            long gen=generation;
            for(int i=0;i<targets.length;i++)requests.add(new Request(targets[i],cycleStartNanos,deadlineNanos,durationMs,px,py,gen,completions[i]));
            enqueueAll(requests);
            try {
                preparer.execute(()->prepareGroup(requests));
            } catch(Exception e) {
                pending.removeAll(requests);return false;
            }
        }
        unparkScheduler();return true;
    }

    private static long toDurationMs(long nanos) {
        if(nanos<=0)return 1;
        return Math.max(1,nanos/NS_MS+(nanos%NS_MS==0?0:1));
    }

    private void unparkScheduler() {
        lastSchedulerUnparkElapsed=SystemClock.elapsedRealtimeNanos();
        Thread t=scheduler;
        if(t!=null)LockSupport.unpark(t);
    }

    private void unparkWorker() {
        Thread t=worker;
        if(t!=null)LockSupport.unpark(t);
    }

    public void cancelPendingScheduledClick() {
        cancelPending();
    }

    private void cancelPending() {
        List<Request> cancelled=new ArrayList<>();
        synchronized(lock) {
            generation++;
            while(!pending.isEmpty())cancelled.add(pending.pollFirst());
            cancelled.addAll(building);building.clear();
            while(!prepared.isEmpty())cancelled.addAll(prepared.poll().requests);
        }
        unparkScheduler();unparkWorker();
        for(Request r:cancelled) {
            r.callbackResult="CANCELLED_BEFORE_DISPATCH";postFinish(r,false,true);
        }
    }

    @Override
    public void onInterrupt() {
        cancelPending();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onDestroy() {
        ready=false;stopping=true;
        Thread st=scheduler;scheduler=null;
        if(st!=null) {
            LockSupport.unpark(st);st.interrupt();
        }
        Thread wt=worker;worker=null;
        if(wt!=null) {
            LockSupport.unpark(wt);wt.interrupt();
        }
        ExecutorService ex=preparer;preparer=null;
        if(ex!=null)ex.shutdownNow();
        List<Request> waiting=new ArrayList<>(),running=new ArrayList<>();
        synchronized(lock) {
            generation++;
            while(!pending.isEmpty())waiting.add(pending.pollFirst());
            waiting.addAll(building);building.clear();
            while(!prepared.isEmpty())waiting.addAll(prepared.poll().requests);
            if(workerCurrentBatch!=null)waiting.addAll(workerCurrentBatch.requests);
            running.addAll(active);
            for(Request r:callbackPending)if(!running.contains(r))running.add(r);
            callbackPending.clear();active.clear();dispatching=false;workerCurrentBatch=null;
        }
        for(Request r:waiting) {
            r.callbackResult="SERVICE_DESTROYED_PENDING";finish(r,false,true);
        }
        for(Request r:running) {
            r.callbackResult="SERVICE_DESTROYED_ACTIVE";finish(r,false,false);
        }
        writerStopping=true;
        if(writerThread!=null)writerThread.interrupt();
        HandlerThread cb=callbackThread;callbackThread=null;callbackHandler=null;
        if(cb!=null)cb.quitSafely();
        if(instance==this)instance=null;
        preferences=null;
        super.onDestroy();
    }

    public static AutoClickService getInstance() {
        return instance;
    }

    public boolean isServiceReady() {
        return ready;
    }

    public boolean isGestureInProgress() {
        synchronized(lock) {
            purgeFinishedActive(SystemClock.elapsedRealtimeNanos());
            return !active.isEmpty();
        }
    }

    public int getTargetX() {
        return x;
    }

    public int getTargetY() {
        return y;
    }

    public String getTimingFilePath() {
        return logPath;
    }

    public String getAttemptTimingFilePath() {
        return attemptLogPath;
    }

    public void setTarget(int newX,int newY) {
        int nx=Math.max(0,newX),ny=Math.max(0,newY);
        runOnMain(()->{
            if(!ready||(x==nx&&y==ny))return;
            x=nx;y=ny;
            SharedPreferences p=preferences;
            if(p!=null)p.edit().putInt(KEY_X,nx).putInt(KEY_Y,ny).apply();
        });
    }

    private void runOnMain(Runnable action) {
        if(Looper.myLooper()==Looper.getMainLooper())action.run();
        else main.post(action);
    }
}
