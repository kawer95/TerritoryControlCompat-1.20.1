package com.arxyt.territorycontrolcompat.compat;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Owns bounded background campaign jobs and publishes immutable results for server-thread commit. */
final class CampaignPlanningService {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final Map<JobKey, JobState> JOBS = new ConcurrentHashMap<>();
    private static final LongAdder COMPLETED = new LongAdder();
    private static final LongAdder CANCELLED = new LongAdder();
    private static final LongAdder EXPIRED = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder BACKGROUND_NANOS = new LongAdder();
    private static volatile ThreadPoolExecutor executor;

    private CampaignPlanningService() {
    }

    static boolean submit(JobKey key, long generation, CampaignStrategicPlanner.Request request) {
        if (JOBS.putIfAbsent(key, new JobState(generation)) != null) return false;
        JobState state = JOBS.get(key);
        try {
            ensureExecutor().execute(() -> {
                long started = System.nanoTime();
                try {
                    state.result = CampaignStrategicPlanner.plan(request);
                } catch (Throwable failure) {
                    state.failure = failure;
                } finally {
                    state.backgroundNanos = System.nanoTime() - started;
                    state.complete = true;
                    BACKGROUND_NANOS.add(state.backgroundNanos);
                    COMPLETED.increment();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            JOBS.remove(key, state);
            REJECTED.increment();
            return false;
        }
    }

    static Completion poll(JobKey key, long expectedGeneration) {
        JobState state = JOBS.get(key);
        if (state == null || !state.complete) return null;
        if (!JOBS.remove(key, state)) return null;
        if (state.generation != expectedGeneration) {
            EXPIRED.increment();
            return new Completion(null, null, state.backgroundNanos, true);
        }
        return new Completion(state.result, state.failure, state.backgroundNanos, false);
    }

    static void cancelDimension(String dimension) {
        JOBS.entrySet().removeIf(entry -> {
            if (!entry.getKey().dimension().equals(dimension)) return false;
            CANCELLED.increment();
            return true;
        });
    }

    static void cancelDirector(String director) {
        JOBS.entrySet().removeIf(entry -> {
            if (!entry.getKey().director().equals(director)) return false;
            CANCELLED.increment();
            return true;
        });
    }

    static synchronized void shutdown() {
        CANCELLED.add(JOBS.size());
        JOBS.clear();
        ThreadPoolExecutor current = executor;
        if (current != null) current.shutdownNow();
        executor = null;
    }

    static Metrics metrics() {
        ThreadPoolExecutor current = executor;
        return new Metrics(JOBS.size(), current == null ? 0 : current.getQueue().size(), COMPLETED.sum(), CANCELLED.sum(),
                EXPIRED.sum(), REJECTED.sum(), BACKGROUND_NANOS.sum());
    }

    private static ThreadPoolExecutor createExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "territory-campaign-planner-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    private static synchronized ThreadPoolExecutor ensureExecutor() {
        if (executor == null || executor.isShutdown()) executor = createExecutor();
        return executor;
    }

    record JobKey(String dimension, String director, String scope) { }
    record Completion(CampaignStrategicPlanner.Result result, Throwable failure, long backgroundNanos, boolean expired) { }
    record Metrics(int running, int queued, long completed, long cancelled, long expired,
                   long rejected, long backgroundNanos) { }

    private static final class JobState {
        private final long generation;
        private volatile CampaignStrategicPlanner.Result result;
        private volatile Throwable failure;
        private volatile long backgroundNanos;
        private volatile boolean complete;
        private JobState(long generation) { this.generation = generation; }
    }
}
