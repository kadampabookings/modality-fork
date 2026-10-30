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
        // DENIED 2026-10-30, 29 days after it was ready — see the note below for what it waited on and why
        // that wait was already over. A flat deny, NOT an equality exception: nothing looks an organisation
        // up BY its API key, and a testable secret is a per-character oracle.
        ClientReadDenyList.denyColumn("organization", "bunny_api_key");
        // Capability tokens: a client may look a row up BY one and may not read one. Each is a link somebody was
        // emailed, and the way a capability like this is defeated is not by guessing a token but by asking for
        // all of them - `select token from Invitation` returned every live one to anybody who could reach the
        // bus. Equality against a bound parameter is what presenting a link does, and it is all that is left.
        ClientReadDenyList.denyColumnExceptEqualityMatch("volunteering_date_proposal", "action_token");
        ClientReadDenyList.denyColumnExceptEqualityMatch("volunteering_application", "arrival_confirmation_token");
        // RESTORED 2026-10-30, after 36 days paused.
        //
        // The watch it replaces is DELETED, not left beside it. The note here used to say the deny
        // "SUPERSEDES the watch, so the observe line above does not have to be removed in the same change,
        // and leaving it is not a trap". That is false and it is the expensive kind of false: declaring both
        // throws IllegalStateException("A column already denied to clients cannot also be merely observed")
        // out of declare(), which runs at boot — so following that advice would have aborted the server on
        // deploy rather than printing a warning. Caught by scripts/checks/client-read-guard, which is the
        // only reason it is a sentence here instead of an outage.
        ClientReadDenyList.denyColumnExceptEqualityMatch("invitation", "token");
        //
        // WHY IT WAS PAUSED, AND WHY THAT REASON WAS WRONG — worth the lines, because the wrong reason cost
        // five weeks and a production deploy that did nothing.
        //
        // It refused real invitees: four in the ten hours after it shipped, each one somebody told their
        // invitation was invalid. The diagnosis written here was that the client had stopped selecting the
        // column and only CACHED bundles still sent the old statement, so the cure was to wait for them to
        // age out. On that theory kbs3-react/API_VERSION was bumped 2→3 on 2026-10-25 to banner stale
        // clients into updating. It changed nothing: 5, 11 and 2 reads on the three days after, against a
        // mean of 8.1 before.
        //
        // The client had never stopped. use-invitations-data.ts selected `token` on every load of /members,
        // matching the logged statement character for character — a live screen, no cache involved. It was
        // mapped onto InvitationRow and read by NOTHING: the email sender had already been migrated and
        // composes the approve/decline links server-side. Removing the field broke seven object literals,
        // every one a test fixture and no production code, which is the proof it was dead.
        //
        // Removed in kbs3-react ad85747c9, deployed 2026-10-28 evening. CAPABILITY-READ went to zero on the
        // 29th and stayed there — the first clean 24 hours since the watch began, against 5-11 a day for the
        // whole week before. That is what this restore rests on, and it is a different fact from the one the
        // original note was waiting for.
        //
        // The lesson, since it generalises: "a cached client still sends it" and "the current client still
        // sends it" look identical in a log, and only one of them heals by waiting. Check what the deployed
        // source selects before blaming a cache.
        //
        // The two tokens above stay declared because nothing has been observed sending their old shape, and
        // they are the pair that still AUTHORISE an action — reading one lets somebody confirm or cancel a
        // volunteer's arrival, where an invitation token no longer accepts anything (ApproveInvitationEndpoint
        // requires the caller to BE the invitee). So the five weeks of exposure reopened here were disclosure
        // of pending invitations, not account takeover.
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
        // The deny is declared INSTEAD of the watch, never beside it: declaring both throws at boot. (This
        // paragraph said the opposite until 2026-10-30, when the check refused the pair.)
        //
        // Faster than waiting: bump kbs3-react/API_VERSION, which banners stale clients into updating instead of
        // waiting for them to drift. That is the lever, and it is why this is a review date and not a deadline.
        // DENIED 2026-10-30:
        //   organization.bunny_api_key — ready since 2026-10-01, denied today.
        //       The back-office Organizations page loaded the real key for every organisation (three live keys
        //       in prod, 2026-09-24) purely to choose between a masked placeholder and an empty one. V0111 adds
        //       `bunny_api_key_set`, a generated column that answers that question without the key, and the page
        //       reads it: organization-columns.ts selects bunnyApiKeySet, and the edit dialog derives
        //       hasStoredApiKey from that boolean. The secret no longer reaches the browser at all.
        //       What it waited on was stale bundles — the back office is a PWA with registerType 'autoUpdate',
        //       so a cached shell keeps asking for the old field until it updates, and a deny breaks the page
        //       for whoever holds that bundle. This note said to bump kbs3-react/API_VERSION in the same change
        //       as the deny, as the lever for exactly that. The bump already happened: 2→3 on 2026-10-25, for
        //       the invitation.token theory, where it turned out to be the wrong lever because that column was
        //       being read by the LIVE client. API_VERSION is one root file read by BOTH apps, so back-office
        //       bundles have been bannered into updating since then. The prerequisite was satisfied early and
        //       by accident, which is worth saying plainly rather than letting the dates look deliberate.
        //       NOT measured first, unlike invitation.token, and the reason is a gap: ClientReadDenyList has no
        //       plain observeColumn — only the equality-exception variant built for capability tokens — so a
        //       flat-denied column has no instrument and there was no zero to wait for. If this breaks a screen,
        //       that gap is the thing to close before the next flat deny.
        //       NOTE the deny closes the KBS3 path only, because KBS2 runs its own server and bus and this list
        //       cannot refuse anything there. KBS2's DbExplorerActivity used to list bunnyApiKey in its
        //       Organization node-fields; that has been removed, so the KBS2 side closes with its next backend
        //       deploy — independently of this deny, which never bound it. The caveat still stands for the
        //       gateway_parameter and magic_link columns denied above: that screen reads those today.
        //   (the three capability tokens that used to be listed here are now declared above: they did not need
        //       an endpoint after all, only a rule saying they may be tested and not read.)
        Console.log("🛡 Client queries may not touch the sign-in, credential and key columns (2 tables, 7 columns),"
                    + " and may test but not read 3 capability tokens (none paused)");
    }
}
