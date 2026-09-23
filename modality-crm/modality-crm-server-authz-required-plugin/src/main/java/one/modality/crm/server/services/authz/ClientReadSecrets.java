package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.ClientReadDenyList;

/**
 * The columns no client query may touch, because reading them is a way in rather than merely a leak.
 *
 * <h3>Why these, and why only these</h3>
 *
 * <p>Any client can read any table through the query endpoint today — read authorisation is the later half of the
 * same item as {@link ProtectedEntityWritesJob}. Most of what that exposes is serious but is a disclosure: a name,
 * a booking, a note. The columns below are different in kind. Each one, read, lets whoever read it act as somebody
 * or as us, and every other control on sessions and passwords sits on top of them:
 *
 * <ul>
 *   <li>{@code magic_link} — every column: the emailed sign-in token and code, and the run id that binds a
 *       redeemed recovery to the tab that redeemed it. Reading a live code is signing in as its owner.</li>
 *   <li>{@code frontend_account} password, salt and reset token — a hash that is MD5 salted with the email, so
 *       close to the password itself, and a live reset token.</li>
 *   <li>{@code gateway_parameter} — every column. The provider credentials live in {@code value}, flagged by the
 *       {@code secret} boolean rather than kept apart, so no single column can be denied; no client reads the
 *       table at all.</li>
 *   <li>{@code smtp_account.password} — sending mail as the organisation, from its own domain.</li>
 *   <li>{@code push_subscription} auth and p256dh keys — with them, anyone can push notifications to a member's
 *       devices. Clients write them and never read them back.</li>
 * </ul>
 *
 * <p>Each was checked against what the React apps actually query before being listed: none reads any of them, and
 * the one legacy back-office screen that sent raw SQL is no longer used. A column a legitimate screen does read
 * cannot simply be denied — the screen breaks — and the ones found in that state are NOT here yet: see the note at
 * the end of {@link #declare()}.
 *
 * @author Claude Code
 */
final class ClientReadSecrets {

    private ClientReadSecrets() {}

    static void declare() {
        ClientReadDenyList.denyTable("magic_link");
        ClientReadDenyList.denyTable("gateway_parameter");
        ClientReadDenyList.denyColumn("frontend_account", "password");
        ClientReadDenyList.denyColumn("frontend_account", "salt");
        ClientReadDenyList.denyColumn("frontend_account", "pwdreset_token");
        ClientReadDenyList.denyColumn("smtp_account", "password");
        ClientReadDenyList.denyColumn("push_subscription", "auth_key");
        ClientReadDenyList.denyColumn("push_subscription", "p256dh_key");
        // Capability tokens: a client may look a row up BY one and may not read one. Each is a link somebody was
        // emailed, and the way a capability like this is defeated is not by guessing a token but by asking for
        // all of them - `select token from Invitation` returned every live one to anybody who could reach the
        // bus. Equality against a bound parameter is what presenting a link does, and it is all that is left.
        ClientReadDenyList.denyColumnExceptEqualityMatch("invitation", "token");
        ClientReadDenyList.denyColumnExceptEqualityMatch("volunteering_date_proposal", "action_token");
        ClientReadDenyList.denyColumnExceptEqualityMatch("volunteering_application", "arrival_confirmation_token");
        // NOT yet denied, because a legitimate screen reads each of them and would simply break. Each needs the
        // server to stop handing the value out rather than the client to stop asking:
        //   organization.bunny_api_key — the back-office Organizations page loads the real key into the browser
        //       only to decide whether to show it masked; the server should say "a key is set" instead.
        //   (the three capability tokens that used to be listed here are now declared above: they did not need
        //       an endpoint after all, only a rule saying they may be tested and not read.)
        Console.log("🛡 Client queries may not touch the sign-in, credential and key columns (2 tables, 6 columns),"
                    + " and may test but not read 3 capability tokens");
    }
}
