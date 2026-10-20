package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Booleans;
import dev.webfx.platform.util.Arrays;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.regex.Pattern;

/**
 * What the four audio-storage endpoints share: reading their argument, and the guard they all apply.
 *
 * <p>A bus call carries ONE argument, so the React client sends a flat array of scalars
 * ({@code [eventId, folder, pattern]}, {@code [eventId, slotId, name, size, …]}) — the shape
 * {@code MergeDuplicatePersons} and {@code UpdatePersonDetails} already take — and a lone scalar when there is
 * one value.
 */
final class AudioBusArguments {

    /** The screen these endpoints serve; the caller must be able to reach it for the target's organisation. */
    static final String MEDIAS_ROUTE = "/medias";

    private AudioBusArguments() {
    }

    /** The argument as an array, whichever shape it arrived in. */
    static Object[] array(Object argument) {
        if (argument instanceof Object[] array)
            return array;
        return new Object[] { argument };
    }

    static Object get(Object[] arguments, int index) {
        return index < Arrays.length(arguments) ? arguments[index] : null;
    }

    static String string(Object[] arguments, int index) {
        Object value = get(arguments, index);
        return value == null ? null : value.toString();
    }

    static boolean bool(Object[] arguments, int index) {
        return Booleans.isTrue(get(arguments, index));
    }

    /**
     * The caller's state, read on THIS thread, or null when the caller cannot pass the guard whatever the
     * target — refused before anything reads the database for them.
     */
    static Object callerState() {
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        return RouteAccessGuard.backOfficeCallerPersonId(state) == null ? null : state;
    }

    /** {@code RouteAccessGuard.whenCallerMayReach("/medias", …)}, run with the caller's state restored. */
    static AudioStorageService.Authorizer authorizer(Object state) {
        return (organizationId, eventId, work) -> ThreadLocalStateHolder.runWithState(state,
            () -> RouteAccessGuard.whenCallerMayReach(MEDIAS_ROUTE, organizationId, eventId, work));
    }

    static Future<String> refused() {
        return RouteAccessGuard.refused();
    }

    /** RouteAccessGuard's refusal, word for word: the client recognises it. */
    private static final String REFUSAL = RouteAccessGuard.refused().cause().getMessage();
    /** A code the back office translates ({@code audio.noFolder}, {@code bunny.badApiKey}, …). */
    private static final Pattern CODE = Pattern.compile("(audio|bunny)\\.[A-Za-z]+");
    /** Anything without a code of its own: the screen says "something went wrong", nothing more specific. */
    static final String UNEXPECTED = "audio.unexpected";

    /**
     * What a failure may say on the bus, which carries only its message: a storage failure's code (its message
     * is for the log), a code as itself, the refusal as RouteAccessGuard words it — and anything else, a database
     * error or a bug, as {@code audio.unexpected}, its detail logged here rather than shown to the caller.
     */
    static String publicCode(Throwable err) {
        String storageCode = AudioStorageService.codeOf(err, null);
        if (storageCode != null)
            return storageCode;
        String message = err == null ? null : err.getMessage();
        if (message != null && (CODE.matcher(message).matches() || message.equals(REFUSAL)))
            return message;
        Console.error("[BUNNY-AUDIO] request failed", err);
        return UNEXPECTED;
    }

    /** {@code future}, its failure replaced by {@link #publicCode}. */
    static Future<String> withPublicErrors(Future<String> future) {
        return future.recover(err -> Future.failedFuture(publicCode(err)));
    }
}
