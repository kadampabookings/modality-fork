package one.modality.crm.server.person;

import one.modality.crm.server.authn.gateway.shared.CallerRateLimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Check the two doors anyone may knock on about an email address: "does it have an account?" and "send it a
 * recovery mail" (2026-10-19).
 *
 * <p>What must hold: the lookup answers yes/no from {@code frontend_account} and nothing else; both doors count
 * every call against {@link CallerRateLimit} on the caller's thread, keyed on the address the bridge stamped; the
 * recovery resolves a household member's address on the server, where the browser used to; and a caller can
 * neither escape the count by hiding its address nor spend everyone else's budget from one machine.
 */
public class EmailLookupCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A clock the check moves by hand. */
    static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    public static void main(String[] args) {
        System.out.println("EmailLookupCheck");

        // --- the argument ---
        check("[email] is read", "a@b.co".equals(EmailHasAccountEndpoint.emailOf(new Object[]{"a@b.co"})));
        check("and trimmed", "a@b.co".equals(EmailHasAccountEndpoint.emailOf(new Object[]{"  a@b.co "})));
        check("a bare string is refused", EmailHasAccountEndpoint.emailOf("a@b.co") == null);
        check("two elements are refused", EmailHasAccountEndpoint.emailOf(new Object[]{"a@b.co", "c@d.co"}) == null);
        check("a number is refused", EmailHasAccountEndpoint.emailOf(new Object[]{42}) == null);
        check("null is refused", EmailHasAccountEndpoint.emailOf(null) == null);
        check("blank is refused", EmailHasAccountEndpoint.emailOf(new Object[]{"   "}) == null);
        check("no @ is refused", EmailHasAccountEndpoint.emailOf(new Object[]{"ab.co"}) == null);
        check("a leading @ is refused", EmailHasAccountEndpoint.emailOf(new Object[]{"@b.co"}) == null);
        check("longer than an address can be is refused",
            EmailHasAccountEndpoint.emailOf(new Object[]{"a".repeat(250) + "@b.co"}) == null);

        // --- the limiter: per address ---
        ManualClock clock = new ManualClock();
        CallerRateLimit limit = new CallerRateLimit("check", Duration.ofMinutes(10), 3, 5, clock);
        check("1st call from A admitted", limit.tryAcquire("10.0.0.1"));
        check("2nd call from A admitted", limit.tryAcquire("10.0.0.1"));
        check("3rd call from A admitted", limit.tryAcquire("10.0.0.1"));
        check("4th call from A refused", !limit.tryAcquire("10.0.0.1"));
        check("B still has its own budget", limit.tryAcquire("10.0.0.2"));
        // A's refused calls must not have spent the global budget: 3 (A) + 1 (B) admitted, so one more fits.
        for (int i = 0; i < 20; i++)
            limit.tryAcquire("10.0.0.1");
        check("refusals on A's address do not spend the global budget", limit.tryAcquire("10.0.0.3"));
        check("the global budget then refuses a new address", !limit.tryAcquire("10.0.0.4"));
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        check("a new window admits A again", limit.tryAcquire("10.0.0.1"));
        check("and a new address", limit.tryAcquire("10.0.0.4"));

        // --- an unknown address is counted, not exempt ---
        CallerRateLimit unknown = new CallerRateLimit("check", Duration.ofMinutes(10), 2, 100, clock);
        unknown.tryAcquire(null);
        unknown.tryAcquire(null);
        check("callers with no address share one budget rather than escaping the count", !unknown.tryAcquire(null));

        // --- IPv6: one machine owns a /64, so it is counted as one ---
        check("an IPv4 address is its own key", "203.0.113.7".equals(CallerRateLimit.keyOf("203.0.113.7")));
        check("two addresses in one /64 share a key",
            CallerRateLimit.keyOf("2001:db8:1:2:aaaa::1").equals(CallerRateLimit.keyOf("2001:db8:1:2:bbbb:cccc:dddd:eeee")));
        check("compressed and full forms of one /64 share a key",
            CallerRateLimit.keyOf("2001:db8::1").equals(CallerRateLimit.keyOf("2001:0db8:0000:0000:ffff::2")));
        check("case, brackets and zone do not change the key",
            CallerRateLimit.keyOf("[2001:DB8:1:2::9%eth0]").equals(CallerRateLimit.keyOf("2001:db8:1:2::1")));
        check("different /64s keep different keys",
            !CallerRateLimit.keyOf("2001:db8:1:2::1").equals(CallerRateLimit.keyOf("2001:db8:1:3::1")));
        CallerRateLimit v6 = new CallerRateLimit("check", Duration.ofMinutes(10), 2, 100, clock);
        v6.tryAcquire("2001:db8:1:2::1");
        v6.tryAcquire("2001:db8:1:2::2");
        check("a fresh address in the same /64 does not refill the budget", !v6.tryAcquire("2001:db8:1:2::3"));

        // --- the production budgets exist and are not wide open ---
        check("the lookup has a budget", CallerRateLimit.EMAIL_LOOKUP != null);
        check("recovery mail has a budget", CallerRateLimit.RECOVERY_MAIL != null);

        // --- the doors call the limiter, on the caller's thread, keyed on the bridge's stamp ---
        String endpoint = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/java/"
            + "one/modality/crm/server/person/EmailHasAccountEndpoint.java");
        check("the endpoint source was found", !endpoint.isEmpty());
        check("the lookup is counted against EMAIL_LOOKUP by the stamped address",
            endpoint.contains("CallerRateLimit.EMAIL_LOOKUP.tryAcquire(ThreadLocalStateHolder.getClientAddress())"));
        check("the lookup reads the sign-in address, frontend_account.username",
            endpoint.contains("from frontend_account where corporation_id = 1 and lower(username) = lower($1)"));
        check("and runs as the server", endpoint.contains("ServerWrite.asServer("));
        check("its reply is a yes/no", endpoint.contains(".map(result -> result != null && result.getRowCount() > 0)"));

        String gateway = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-authn-gateway-magiclink-plugin/src/main/java/"
            + "one/modality/crm/server/authn/gateway/magiclink/ModalityMagicLinkAuthenticationGateway.java");
        check("the gateway source was found", !gateway.isEmpty());
        check("recovery mail is counted against RECOVERY_MAIL by the stamped address",
            gateway.contains("CallerRateLimit.RECOVERY_MAIL.tryAcquire(ThreadLocalStateHolder.getClientAddress())"));
        check("recovery resolves the typed address on the server",
            gateway.contains("RecoveryAddressResolver.accountUsernameFor(request.getEmail(), dataSourceModel)"));
        check("and mails the resolved account, not the typed address",
            gateway.contains("sendRecoveryOrUnknownAccountMail(resolved, unknown, loginRunId, lang)"));

        String resolver = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-authn-gateway-magiclink-plugin/src/main/java/"
            + "one/modality/crm/server/authn/gateway/magiclink/RecoveryAddressResolver.java");
        check("the resolver source was found", !resolver.isEmpty());
        check("the resolver walks a member's person.email to its account, owners first",
            resolver.contains("from Person where lower(email)=lower($1) and removed!=true and frontendAccount!=null")
            && resolver.contains("order by owner desc, id desc limit 1"));

        // --- the stamp the limiter keys on ---
        String stateManager = AccountOwnerCheck.readSource(
            "webfx-stack-fork/webfx-stack-com-bus-json-server/src/main/java/dev/webfx/stack/com/bus/spi/impl/json/server/"
            + "ServerJsonBusStateManager.java");
        check("the bridge stamps the caller's address on every inbound state",
            stateManager.contains("StateAccessor.setClientAddress(originalState, clientAddress);"));
        String vertxBus = AccountOwnerCheck.readSource(
            "webfx-stack-fork/webfx-stack-com-bus-json-vertx/src/main/java/dev/webfx/stack/com/bus/spi/impl/json/vertx/"
            + "VertxBus.java");
        check("from the socket, for incoming messages only",
            vertxBus.contains("isIncomingMessage ? clientAddressOf(socket) : null"));
        check("taking the LAST X-Forwarded-For entry, the one the load balancer wrote",
            vertxBus.contains("forwardedFor.lastIndexOf(',')"));
        check("of the LAST header line, should a client send its own",
            vertxBus.contains("socket.headers().getAll(\"X-Forwarded-For\")")
            && vertxBus.contains("forwardedFor.get(forwardedFor.size() - 1)"));

        // --- registered, or it is not a door at all ---
        String services = AccountOwnerCheck.readSource(
            "modality-fork/modality-crm/modality-crm-server-person-plugin/src/main/resources/META-INF/services/"
            + "dev.webfx.stack.com.bus.call.spi.BusCallEndpoint");
        check("the endpoint is registered", services.contains("one.modality.crm.server.person.EmailHasAccountEndpoint"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
