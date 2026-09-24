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
 * cannot simply be denied — the screen breaks — so a column can also be waiting here for a reason other than the
 * screen: see the note at the end of {@link #declare()}.
 *
 * <p><b>This list binds KBS3 clients only.</b> It is enforced in this server's query guard, and KBS2 runs its own
 * server and its own bus, so a KBS2 screen reading one of these columns is not refused by anything here — KBS2's
 * DB Explorer reads several of them today. Read as an estate-wide guarantee this list is simply wrong, and that
 * is the kind of reference that is trusted precisely where it misleads.
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
        ClientReadDenyList.denyColumnExceptEqualityMatch("volunteering_date_proposal", "action_token");
        ClientReadDenyList.denyColumnExceptEqualityMatch("volunteering_application", "arrival_confirmation_token");
        // PAUSED 2026-09-24 — and WATCHED instead, so that restoring it can be a measurement:
        //   ClientReadDenyList.denyColumnExceptEqualityMatch("invitation", "token");
        ClientReadDenyList.observeColumnExceptEqualityMatch("invitation", "token");
        //
        // It refused real invitees. The client stopped selecting this column and that change is live, but a
        // cached front-office bundle still sends the old statement, and an emailed invitation link is a FIRST
        // page load — where the service worker serves the cached shell before it updates. Four refusals in the
        // ten hours after the deploy, each one somebody told their invitation was invalid.
        //
        // The sequencing was wrong, not the rule: a server rule that refuses what a cached client still sends
        // has to wait for the bundles to age out, not merely for the client change to ship. The two tokens
        // above stay declared because nothing has been observed sending their old shape, and they are the
        // pair that still AUTHORISE an action — reading one lets somebody confirm or cancel a volunteer's
        // arrival, where an invitation token no longer accepts anything (ApproveInvitationEndpoint requires
        // the caller to BE the invitee). So the week of exposure reopened here is disclosure of pending
        // invitations, not account takeover.
        //
        // REVIEW FROM 2026-10-08, and restore on evidence rather than on the date. The refusal used to be the
        // only detector, so pausing removed it; the watch above restores the signal without refusing anybody.
        //   filter @message like /CAPABILITY-READ/
        // Every occurrence is logged and carries its running total, so the latest line answers "how many". Zero
        // over a week means the old bundles are gone. The rate to beat is the one the refusals showed while the
        // rule was live — 8 in the 15 hours it ran, about twelve a day — so a quiet afternoon proves nothing.
        //
        // Two ways a zero can lie, both worth one query each before acting on it:
        //   - the inventory was not running. Confirm it emitted OTHER read shapes over the same window
        //     (filter @message like /client read shape/); zero of those means the instrument, not the traffic.
        //   - CAPABILITY-UNREADABLE lines. Those are "the walk met a construct it cannot read", not a read —
        //     but they are also statements nobody has confirmed are safe, so read them before concluding.
        // The deny may simply be declared when the time comes: it SUPERSEDES the watch, so the observe line
        // above does not have to be removed in the same change, and leaving it is not a trap.
        //
        // Faster than waiting: bump kbs3-react/API_VERSION, which banners stale clients into updating instead of
        // waiting for them to drift. That is the lever, and it is why this is a review date and not a deadline.
        // NOT yet denied — no longer because a current screen needs it, only because old ones still ask:
        //   organization.bunny_api_key — READY TO DENY FROM 2026-10-01, waiting only on stale bundles.
        //       The back-office Organizations page loaded the real key for every organisation (three live keys
        //       in prod, 2026-09-24) purely to choose between a masked placeholder and an empty one. V0111 adds
        //       `bunny_api_key_set`, a generated column that answers that question without the key, and the page
        //       now reads it. The reason to hold the deny is the reason invitation.token had to be paused: the
        //       back office is a PWA with registerType 'autoUpdate', so a cached shell keeps asking for the old
        //       field until it updates, and a deny would break the page for whoever holds that bundle.
        //       An equality rule would NOT have worked here: nothing looks an organisation up BY its API key,
        //       and a testable secret is a per-character oracle. Once denied, add a line to the Console.log below.
        //       When denying, bump kbs3-react/API_VERSION in the same change: that is the lever built for exactly
        //       this, and it turns "wait for bundles to age out" into a banner telling stale clients to update.
        //       NOT bumped now — nothing is broken yet (an old bundle asking for bunnyApiKey still works, the
        //       column is still served), and API_VERSION is one root file read by BOTH apps, so bumping it early
        //       would nag every front-office user for a back-office change. It becomes true at the deny, not here.
        //       NOTE the deny closes the KBS3 path only, because KBS2 runs its own server and bus and this list
        //       cannot refuse anything there. KBS2's DbExplorerActivity used to list bunnyApiKey in its
        //       Organization node-fields; that has been removed, so the KBS2 side closes with its next backend
        //       deploy — independently of this deny, which never bound it. The caveat still stands for the
        //       gateway_parameter and magic_link columns denied above: that screen reads those today.
        //   (the three capability tokens that used to be listed here are now declared above: they did not need
        //       an endpoint after all, only a rule saying they may be tested and not read.)
        Console.log("🛡 Client queries may not touch the sign-in, credential and key columns (2 tables, 6 columns),"
                    + " and may test but not read 2 capability tokens"
                    + " (invitation.token paused, WATCHED — see CAPABILITY-READ in the read inventory)");
    }
}
