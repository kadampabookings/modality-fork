package one.modality.crm.server.person;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.shared.services.authn.ModalityGuestPrincipal;
import one.modality.crm.shared.services.authn.ModalityUserPrincipal;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * What the userSettings endpoints accept, whom they refuse, and that they are registered (V0158).
 *
 * <p>Everything here runs without a database. The patch rules are pure; the refusals all happen before any SQL,
 * so a refused call that reached the database would show up here as an exception from a QueryService with no
 * provider rather than as the refusal. The SQL itself is exercised against a real database separately — see the
 * plan's step 2 notes.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and throws on failure.
 *
 * @author Claude Code
 */
public class UserSettingsCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        // ── What a patch may contain ──────────────────────────────────────────────────────────────────────
        check("a boolean", "{\"experimental.news\":true}", json("experimental.news", true));
        check("false is kept as false, so a later merge cannot turn it back on",
              "{\"experimental.news\":false}", json("experimental.news", false));
        check("null removes the key", "{\"experimental.news\":null}", json("experimental.news", null));
        check("an integer", "{\"audio.volume\":7}", json("audio.volume", 7));
        check("a long", "{\"audio.volume\":7}", json("audio.volume", 7L));
        // JavaScript has one number type: an integer may arrive as a Double.
        check("an integral double becomes an integer", "{\"audio.volume\":7}", json("audio.volume", 7.0));
        check("a short token", "{\"ui.theme\":\"dark\"}", json("ui.theme", "dark"));
        check("a token with the allowed punctuation", "{\"ui.lang\":\"en-GB_x.1:2\"}", json("ui.lang", "en-GB_x.1:2"));
        check("an empty token", "{\"ui.theme\":\"\"}", json("ui.theme", ""));
        check("several pairs, in order", "{\"a\":true,\"b.c\":1,\"d\":null}",
              UserSettingsRules.patchJsonOf(new Object[] {"a", true, "b.c", 1, "d", null}));
        check("a single-segment key", "{\"news\":true}", json("news", true));
        check("four segments", "{\"a.b.c.d\":true}", json("a.b.c.d", true));

        // ── What it refuses: the shape ────────────────────────────────────────────────────────────────────
        refused("null", null);
        refused("not an array", "experimental.news");
        refused("an empty array", new Object[0]);
        refused("a key without a value", new Object[] {"experimental.news"});
        refused("an odd number of elements", new Object[] {"a", true, "b"});
        Object[] atCap = pairs(UserSettingsRules.MAX_KEYS);
        check("at the key cap", UserSettingsRules.patchJsonOf(atCap) != null);
        refused("one key over the cap", pairs(UserSettingsRules.MAX_KEYS + 1));
        refused("the same key twice, rather than letting the last one win",
                new Object[] {"a", true, "a", false});

        // ── What it refuses: keys ─────────────────────────────────────────────────────────────────────────
        refused("a non-string key", new Object[] {7, true});
        refused("an empty key", new Object[] {"", true});
        refused("a key starting with a capital", new Object[] {"News", true});
        refused("a key with a space", new Object[] {"experimental news", true});
        refused("a key with a quote, which would break the hand-built JSON",
                new Object[] {"a\"b", true});
        refused("a key with a backslash", new Object[] {"a\\b", true});
        refused("__proto__", new Object[] {"__proto__", true});
        refused("a leading dot", new Object[] {".news", true});
        refused("a trailing dot", new Object[] {"news.", true});
        refused("an empty segment", new Object[] {"a..b", true});
        refused("five segments", new Object[] {"a.b.c.d.e", true});
        refused("a segment starting with a digit", new Object[] {"a.1b", true});
        refused("a key over the length limit",
                new Object[] {"a" + "b".repeat(UserSettingsRules.MAX_KEY_LENGTH), true});

        // ── What it refuses: values — the rules that keep personal data out ───────────────────────────────
        refused("free text with a space", new Object[] {"ui.note", "call me later"});
        refused("an email address", new Object[] {"ui.contact", "someone@example.com"});
        refused("a phone number with a plus", new Object[] {"ui.phone", "+447700900123"});
        refused("a quote, which would break the hand-built JSON", new Object[] {"ui.theme", "da\"rk"});
        refused("a backslash", new Object[] {"ui.theme", "da\\rk"});
        refused("a newline", new Object[] {"ui.theme", "da\nrk"});
        refused("non-ASCII", new Object[] {"ui.theme", "sombré"});
        refused("a token over the length limit",
                new Object[] {"ui.theme", "x".repeat(UserSettingsRules.MAX_TOKEN_LENGTH + 1)});
        check("a token at the length limit",
              UserSettingsRules.patchJsonOf(new Object[] {"ui.theme", "x".repeat(UserSettingsRules.MAX_TOKEN_LENGTH)}) != null);
        refused("a fraction", new Object[] {"audio.volume", 1.5});
        refused("NaN", new Object[] {"audio.volume", Double.NaN});
        refused("infinity", new Object[] {"audio.volume", Double.POSITIVE_INFINITY});
        refused("an integer over the bound", new Object[] {"audio.volume", UserSettingsRules.MAX_INTEGER + 1});
        refused("a negative integer over the bound", new Object[] {"audio.volume", -UserSettingsRules.MAX_INTEGER - 1});
        check("the bound itself",
              UserSettingsRules.patchJsonOf(new Object[] {"audio.volume", UserSettingsRules.MAX_INTEGER}) != null);
        refused("a nested array", new Object[] {"ui.list", new Object[] {"a"}});
        refused("some other object", new Object[] {"ui.thing", new Object()});
        refused("one bad pair among good ones refuses the whole patch",
                new Object[] {"a", true, "b", "not ok", "c", false});

        // ── Whom the endpoints refuse ─────────────────────────────────────────────────────────────────────
        // Every one of these must be refused BEFORE any SQL. MemberSessionGuard's sentence is the same whatever
        // the reason, so the message is asserted exactly: anything else means the call went further than it
        // should have.
        AsyncFunctionBusCallEndpoint<Object, String> get = new GetUserSettingsEndpoint();
        AsyncFunctionBusCallEndpoint<Object, String> patch = new PatchUserSettingsEndpoint();
        Object goodPatch = new Object[] {"experimental.news", true};
        Object member = new ModalityUserPrincipal(11L, 22L);

        Object anonymous = StateAccessor.createEmptyState();
        Object guest = verified(new ModalityGuestPrincipal("guest@example.com"));
        Object supportView = verified(new ModalityUserPrincipal(11L, 22L, 33L));
        Object unverifiedMember = StateAccessor.setUserId(StateAccessor.createEmptyState(), member);
        Object noAccount = verified(new ModalityUserPrincipal(11L, null));

        for (Object[] who : new Object[][] {
            {"anonymous", anonymous},
            {"a guest (no account)", guest},
            {"a support view (would act on the VIEWED member's settings)", supportView},
            {"a member whose session this server never verified", unverifiedMember},
            {"a principal without an account", noAccount},
        }) {
            check("get refuses " + who[0], isGuardRefusal(call(get, who[1], null)));
            check("patch refuses " + who[0], isGuardRefusal(call(patch, who[1], goodPatch)));
        }

        // A malformed patch is refused for everybody, verified member included, and before the guard.
        Future<String> badForMember = call(patch, verified(member), new Object[] {"a", "not ok"});
        check("a malformed patch from a verified member is a bad request",
              badForMember.failed() && messageOf(badForMember).contains(PatchUserSettingsEndpoint.BAD_REQUEST_KEY));
        Future<String> badForAnonymous = call(patch, anonymous, new Object[] {"a", "not ok"});
        check("and the same for an anonymous caller",
              badForAnonymous.failed() && messageOf(badForAnonymous).contains(PatchUserSettingsEndpoint.BAD_REQUEST_KEY));

        // ── The addresses, which the React client mirrors ─────────────────────────────────────────────────
        check("get address", "modality/service/userSettings/get", GetUserSettingsEndpoint.GET_USER_SETTINGS_ADDRESS);
        check("patch address", "modality/service/userSettings/patch", PatchUserSettingsEndpoint.PATCH_USER_SETTINGS_ADDRESS);
        check("get endpoint answers on it", GetUserSettingsEndpoint.GET_USER_SETTINGS_ADDRESS.equals(get.getAddress()));
        check("patch endpoint answers on it", PatchUserSettingsEndpoint.PATCH_USER_SETTINGS_ADDRESS.equals(patch.getAddress()));

        // ── Registration: an endpoint that is not in the services file is never started ────────────────────
        String services;
        try (InputStream in = UserSettingsCheck.class.getClassLoader()
                .getResourceAsStream("META-INF/services/dev.webfx.stack.com.bus.call.spi.BusCallEndpoint")) {
            services = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        check("the services file was found", !services.isEmpty());
        check("GetUserSettingsEndpoint IS registered", services.contains("GetUserSettingsEndpoint"));
        check("PatchUserSettingsEndpoint IS registered", services.contains("PatchUserSettingsEndpoint"));

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " user settings checks failed");
    }

    private static String json(String key, Object value) {
        return UserSettingsRules.patchJsonOf(new Object[] {key, value});
    }

    private static Object[] pairs(int count) {
        Object[] pairs = new Object[count * 2];
        for (int i = 0; i < count; i++) {
            pairs[2 * i] = "k" + i;
            pairs[2 * i + 1] = true;
        }
        return pairs;
    }

    /** A state carrying the principal AND a session family, which is what a verified sign-in leaves. */
    private static Object verified(Object principal) {
        Object state = StateAccessor.setUserId(StateAccessor.createEmptyState(), principal);
        return StateAccessor.setSessionFamilyId(state, "family-1");
    }

    private static Future<String> call(AsyncFunctionBusCallEndpoint<Object, String> endpoint, Object state, Object argument) {
        return ThreadLocalStateHolder.runWithState(state, () -> endpoint.toAsyncFunction().apply(argument));
    }

    private static boolean isGuardRefusal(Future<String> future) {
        return future.failed() && "This operation is not available to you".equals(messageOf(future));
    }

    private static String messageOf(Future<?> future) {
        Throwable cause = future.cause();
        return cause == null ? "" : String.valueOf(cause.getMessage());
    }

    private static void refused(String what, Object argument) {
        check("refuses " + what, UserSettingsRules.patchJsonOf(argument) == null);
    }

    private static void check(String what, String expected, String actual) {
        check(what + " (" + actual + ")", expected.equals(actual));
    }

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else    { fail++; System.out.println("  FAIL " + what); }
    }
}
