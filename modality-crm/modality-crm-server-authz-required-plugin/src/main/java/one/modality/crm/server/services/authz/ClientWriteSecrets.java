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
 * <p>A mail's DELIVERY RECORD is closed for a third reason: it is the mailer's own state, and a client
 * able to write it can make the mailer act again on a mail that was already dealt with. What a client may
 * do to the mail itself — compose one, and nothing to anybody else's — is a row rule rather than a column
 * list, and lives in {@code MailWritePolicy}.
 *
 * <p>{@code magic_link} is closed entirely: a client able to insert a row there could mint a sign-in link for any
 * account and follow it. No client writes it; the server creates, stamps and retires every row.
 *
 * <p><b>{@code person} is CLOSED to clients as of 2026-09-24 — step 2b of the plan.</b> No client writes a
 * person row now: not a field, not an insert, not an update. The React apps reached this point first, by
 * moving every person write to an endpoint that takes the caller's identity from the session rather than
 * from the payload — UpdatePersonDetails, AddMember, UpdateCustomer, CreateAccountOwner, SetResident,
 * UpdateResident, RemoveCustomers, MergeDuplicatePersons, MergeIntoAccount, UpdateUser. What kept the table
 * open after that was the legacy JavaFX back office: its reception and registration modals INSERT people
 * with an address, and its customers view UPDATES an existing person's email. Those three screens are now
 * refused, which the one remaining user of that client accepted on 2026-09-24 — it is being retired, and
 * they do not use it to write people.
 *
 * <p><b>What closing it actually shuts, because the hole was subtle.</b> It did not take a raw or
 * hand-crafted statement: {@code OwnerLoginWritePolicy} guards only an OWNER's email, and every row a claim
 * targets is a non-owner — so an ORDINARY client write, the shape a change set produces, could point a
 * stranger's member row at the caller's own address, after which {@code claimMembers} would link it
 * legitimately. Two steps rather than one, and easy to miss precisely because each step looked lawful. The
 * table being shut is what ends it; the person-ownership rule the plan lists as item C is no longer the only
 * way there.
 *
 * <p>The two link columns stay listed below even though the table now covers them. That is deliberate
 * belt-and-braces: they are what the claim and the invitation depend on, and if somebody ever reopens the
 * table for one legitimate field they should not silently reopen those. {@code backoffice} is NOT here and
 * never was on this table — the super-admin toggle writes it on {@code frontend_account}, governed by its own
 * rule in {@link ProtectedEntityWritesJob}, still observe-only.
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
        // People. Every legitimate person write is an endpoint now, on both apps; see the class note.
        // This is step 2b of the front-office write-authorization plan, and it is what finally closes
        // the two-step claim: point a stranger's member row at your own address, then claim it.
        ClientWriteDenyList.denyTable("person");
        // The LINK between a person and an account, which says "this row, in somebody else's account,
        // IS this human". Setting it hands that account the row's bookings, and TAKES its recordings
        // (the media screens stop listing a linked row in its own account). Any client could set it on
        // any row until now, which is the single worst thing a client could still do to a person.
        //
        // The four legitimate writers are all server operations: approving an invitation, revoking a
        // link, the customer merge, and the claim. The one client that still wrote it — the sign-up
        // and /members claim — became `claimMembers`, which takes no arguments and derives the rows
        // from the caller's own verified sign-in address.
        //
        // The revoked date goes with it. Writable alone, it is an un-revoke: a withdrawn link reads as
        // live again the moment that column is cleared, without touching the link itself.
        for (String column : new String[] { "account_person_id", "account_person_revoked_date" })
            ClientWriteDenyList.denyColumn("person", column);
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
        // A mail's delivery record, and the sign-in link a mail can be pointed at.
        //
        // `transmitted` is what stops a mail being sent twice, so a client able to clear it can make the
        // mailer deliver an already-sent mail again. `transmission_date` and `error` are the same record
        // of what happened. `magic_link_id` is worse than a record: the auto_recipient trigger reads the
        // LINK's own address to address the mail, so setting it aims a mail the caller wrote at the owner
        // of a sign-in link, from a verified Kadampa address.
        //
        // Checked against both apps before being listed: every client reference to the three delivery
        // columns is a READ (the delivery badge, the booking mail column, the volunteering status field
        // lists), and no client sets a mail's magic link at all. The only writers are MailTransmitter and
        // WebPushMailTransmitter, both server-side, which never reach this guard.
        //
        // What a client may still do to a mail is bounded by MailWritePolicy, not by this list: compose
        // its own, and nothing to anybody else's.
        for (String column : new String[] { "transmitted", "transmission_date", "error", "magic_link_id" })
            ClientWriteDenyList.denyColumn("mail", column);
        Console.log("🛡 Client writes may not touch people, sign-in links or invitation authorship, a"
                    + " mail's delivery record, nor the account's sign-in, status or privilege columns"
                    + " (magic_link and person entirely, plus 23 columns across 4 tables)");
    }
}
