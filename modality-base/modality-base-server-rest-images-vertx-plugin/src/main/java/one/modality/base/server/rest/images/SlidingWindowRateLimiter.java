package one.modality.base.server.rest.images;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A small per-key sliding-window counter, used to bound the two upload paths that do not
 * require a logged-in caller to be staff: anonymous volunteer portraits (keyed by client IP)
 * and support-chat attachments (keyed by person id).
 *
 * <p>Deliberately in-memory and deliberately simple. It resets on deploy and is not shared
 * between server tasks, so it is a brake on casual abuse, not a security boundary — the
 * boundary is the authentication, the server-chosen object name and the byte validation.
 * Making it durable would mean a shared store and a lot more machinery for a control whose
 * job is to stop somebody filling the picture store from a laptop.
 *
 * <p>Not thread-safe, and does not need to be: every caller runs on the Vert.x event loop.
 */
final class SlidingWindowRateLimiter {

    private final int maxEvents;
    private final long windowMillis;
    /** Key -> timestamps of the accepted events still inside the window, oldest first. */
    private final Map<String, ArrayDeque<Long>> events = new HashMap<>();
    /** Keys are pruned wholesale now and then so an idle server does not retain every IP seen. */
    private long nextSweepEpochMillis;

    SlidingWindowRateLimiter(int maxEvents, long windowMillis) {
        this.maxEvents = maxEvents;
        this.windowMillis = windowMillis;
        this.nextSweepEpochMillis = System.currentTimeMillis() + windowMillis;
    }

    /**
     * Record an attempt and report whether it is within the limit.
     *
     * @return true when the caller may proceed, false when it has exhausted its window
     */
    boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        sweepIfDue(now);
        ArrayDeque<Long> timestamps = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        long cutoff = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff)
            timestamps.pollFirst();
        if (timestamps.size() >= maxEvents)
            return false;
        timestamps.addLast(now);
        return true;
    }

    /** Drop keys whose events have all aged out, so the map tracks only active callers. */
    private void sweepIfDue(long now) {
        if (now < nextSweepEpochMillis)
            return;
        nextSweepEpochMillis = now + windowMillis;
        long cutoff = now - windowMillis;
        for (Iterator<Map.Entry<String, ArrayDeque<Long>>> it = events.entrySet().iterator(); it.hasNext(); ) {
            ArrayDeque<Long> timestamps = it.next().getValue();
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff)
                timestamps.pollFirst();
            if (timestamps.isEmpty())
                it.remove();
        }
    }
}
