package one.modality.ecommerce.payment.server.gateway.impl.stripe;

import com.stripe.net.RequestOptions;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.async.Promise;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async helpers for the Stripe gateway. Two responsibilities:
 *
 * <ul>
 *   <li><b>Virtual-thread dispatcher</b> — every Stripe SDK call is synchronous and blocks for
 *       the HTTP round-trip (~200-600 ms). Rather than mapping each call onto a fixed-size
 *       platform-thread worker pool, we spawn a fresh JDK 21 virtual thread per call. Virtual
 *       threads cost ~600 bytes of heap each (no kernel stack) and unmount from their carrier
 *       thread while blocked on socket I/O, so 1 000+ concurrent Stripe calls become a non-issue
 *       — the bottleneck is purely Stripe's API rate limit, not local thread sizing.</li>
 *   <li><b>Retry options</b> — {@link #retryingRequestOptions(String)} builds a
 *       {@link RequestOptions} with {@code setMaxNetworkRetries(3)}. Stripe's SDK applies
 *       jittered exponential backoff internally and honours the {@code Retry-After} header on
 *       429 responses (which we can't replicate as cleanly ourselves).</li>
 * </ul>
 *
 * <p>The virtual-thread executor uses one platform "carrier" thread pool shared across the JVM
 * (managed by {@code ForkJoinPool#commonPool} by default; carrier count = available CPUs). The
 * executor itself is process-wide — no explicit shutdown is needed for a regular server lifetime.
 *
 * @author Bruno Salmon
 */
final class StripeAsync {

    // 3 retries = up to ~7s of internal SDK delay (Stripe documents jittered exponential
    // backoff starting at ~500ms). Idempotency keys on every create-call make retries safe.
    static final int MAX_NETWORK_RETRIES = 3;

    // newVirtualThreadPerTaskExecutor: starts a fresh, unnamed virtual thread per submitted task.
    // No pool sizing because virtual threads scale to millions.
    private static final ExecutorService VT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private StripeAsync() {}

    /**
     * Runs a synchronous Stripe SDK call on a virtual thread and bridges the result to a WebFX
     * Future. Completion is dispatched back to whichever WebFX context attached the continuation,
     * so the downstream {@code .map}/{@code .compose} callbacks run where the caller expects.
     */
    static <T> Future<T> executeBlocking(Callable<T> task) {
        Promise<T> promise = Promise.promise();
        VT_EXECUTOR.submit(() -> {
            try {
                promise.complete(task.call());
            } catch (Throwable e) {
                promise.fail(e);
            }
        });
        return promise.future();
    }

    /**
     * Builds a {@link RequestOptions} with the standard idempotency key + network-retries policy.
     * Pass {@code null} as the idempotency key to skip it (e.g. for read-only retrieve calls).
     */
    static RequestOptions retryingRequestOptions(String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder()
            .setMaxNetworkRetries(MAX_NETWORK_RETRIES);
        if (idempotencyKey != null)
            builder.setIdempotencyKey(idempotencyKey);
        return builder.build();
    }
}
