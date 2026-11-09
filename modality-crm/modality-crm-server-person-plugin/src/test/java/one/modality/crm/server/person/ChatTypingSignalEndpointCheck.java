package one.modality.crm.server.person;

/**
 * Check for what {@link ChatTypingSignalEndpoint} accepts before it looks anything up. The membership check and the
 * relay need a database and a bus, and are exercised on staging (see docs/security/live-channel-plan.md).
 *
 * <p>No test framework: runs from main() and exits non-zero on failure, following the other checks here.
 */
public class ChatTypingSignalEndpointCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) {
        System.out.println("what the front office sends:");
        check("a draft", ChatTypingSignalEndpoint.isValidSignal(501, "viewerDraft", "hello"));
        check("a draft cleared", ChatTypingSignalEndpoint.isValidSignal(501, "viewerDraft", null));
        check("fullscreen on", ChatTypingSignalEndpoint.isValidSignal(501, "viewerFullscreen", true));
        check("fullscreen cleared", ChatTypingSignalEndpoint.isValidSignal(501, "viewerFullscreen", null));

        System.out.println("what it does not:");
        check("an agent's signal (agents publish their own, on the broadcast)",
            !ChatTypingSignalEndpoint.isValidSignal(501, "agentTyping", true));
        check("any other field", !ChatTypingSignalEndpoint.isValidSignal(501, "status", "closed"));
        check("a draft that is not text", !ChatTypingSignalEndpoint.isValidSignal(501, "viewerDraft", 42));
        check("a draft longer than any message",
            !ChatTypingSignalEndpoint.isValidSignal(501, "viewerDraft", "x".repeat(ChatTypingSignalEndpoint.MAX_DRAFT_LENGTH + 1)));
        check("fullscreen set to anything but true", !ChatTypingSignalEndpoint.isValidSignal(501, "viewerFullscreen", "yes"));
        check("no conversation", !ChatTypingSignalEndpoint.isValidSignal(null, "viewerDraft", "hello"));
        check("a conversation that is not an id", !ChatTypingSignalEndpoint.isValidSignal("501 or 1=1", "viewerDraft", "hello"));

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
