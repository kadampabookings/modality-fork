package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientWriteDenyList;

/**
 * What no client may write: the sign-in links, and the account columns that decide who can sign in as whom and what
 * an account is allowed to be.
 *
 * <p>Every control on sessions and passwords sits on top of these: the password change proves the current
 * password, the email change proves it too, "stop my password working" closes the password. A client able to write
 * {@code frontend_account.password} or {@code username} directly would step round all of them in one statement —
 * the hash is MD5 salted with the email, so computing one to write is trivial — and one able to write
 * {@code disabled} could undo the control that stops a stolen session. So no client writes them; the server does,
 * through the flows that prove who is asking.
 *
 * <p>{@code invitation}'s authorship is closed for the same reason one step removed: an invitation is
 * the evidence PersonService accepts that somebody asked for a link to their bookings, and evidence a
 * client can author is not evidence. Denying {@code token} — which is NOT NULL — is what stops a client
 * creating one at all.
 *
 * <p>{@code magic_link} is closed entirely: a client able to insert a row there could mint a sign-in link for any
 * account and follow it. No client writes it; the server creates, stamps and retires every row.
 *
 * <p><b>What this does not cover yet.</b> A person's email reaches {@code frontend_account.username} through a
 * database trigger when that person is the account's owner (V0062), and {@code person} rows are not yet guarded, so
 * that road to the username stays open until person writes are. Nor is {@code backoffice} here: the back office's
 * super-admin toggle writes it, and its own rule in {@link ProtectedEntityWritesJob} is still observe-only.
 *
 * <p>Enforced on every client write regardless of the observe-only switch in {@link ProtectedEntityWritesJob}:
 * that switch protects legitimate screens from a rule nobody has watched yet, and none exists here. Checked
 * against both apps before being listed: the React apps write only {@code lang} (a member's language) and
 * {@code backoffice} (a super admin's toggle, governed by its own rule) on this table, and create accounts through
 * the server. The legacy JavaFX sign-up form did write a username and password, and is no longer used.
 *
 * @author Claude Code
 */
final class ClientWriteSecrets {

    private ClientWriteSecrets() {}

    static void declare() {
        // Sign-in links: a client that could write one could sign in as anybody
        ClientWriteDenyList.denyTable("magic_link");
        // Signing in: the password, its salt, the name it is checked against, and the reset route
        for (String column : new String[] { "password", "salt", "username", "pwdreset_token", "pwdreset_expires" })
            ClientWriteDenyList.denyColumn("frontend_account", column);
        // Whether the account works at all, and which organisation's sign-in it answers to
        ClientWriteDenyList.denyColumn("frontend_account", "disabled");
        ClientWriteDenyList.denyColumn("frontend_account", "corporation_id");
        // Privilege and legacy flags no member sets for themselves — KBS2 reads some of them
        for (String column : new String[] { "admin", "developer", "security", "tester", "translator",
                                            "trigger_send_password", "trigger_send_password_event_id" })
            ClientWriteDenyList.denyColumn("frontend_account", column);
        // An invitation is what PersonService accepts as evidence that somebody asked for a link, and
        // approving one gives an account sight of another person's bookings and recordings. It is only
        // evidence if the client cannot author it: a client able to set `inviter_id` could name anybody
        // as the asker and approve it as itself. `token` is the capability the email carries, and it is
        // NOT NULL — denying it is therefore what stops a client inserting an invitation at all, which
        // is the point rather than a side effect. Invitations are created by
        // modality/service/person/createInvitation, which takes the inviter from the session.
        //
        // `pending` and `accepted` are deliberately NOT denied: declining still happens client-side, and
        // setting them establishes no link on its own.
        //
        // Checked against both apps before being listed, as the account columns were: the React front
        // office creates invitations through the endpoint, and the React back office writes none. The
        // legacy JavaFX front office DOES still insert them (InvitationLinkService), and would be
        // refused — but only the back-office GWT app is built and deployed, so nothing live reaches it.
        for (String column : new String[] { "token", "inviter_id", "invitee_id" })
            ClientWriteDenyList.denyColumn("invitation", column);
        Console.log("🛡 Client writes may not touch sign-in links or invitation authorship, nor set the"
                    + " account's sign-in, status or privilege columns (1 table, 17 columns)");
    }
}
