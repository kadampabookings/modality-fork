package one.modality.crm.shared.services.authn;

/**
 * The decision: mint a session for the device waiting on this code, as the person approving.
 *
 * <p>This is the step that creates something, and every check lives here rather than at the request, because
 * the request has no identity to check. In order:
 *
 * <ol>
 *   <li>the approving session must be a real one, and must not itself be borrowed — a support view
 *       ({@code sv}), a back-office view pass ({@code bov}) or another device session may not approve. A
 *       back-office view is a session as the TARGET person, so without this a super administrator viewing as
 *       somebody could mint that person a command-line session: the delegated approval the design note says
 *       must reopen the whole decision, arriving through a side door;</li>
 *   <li>where the account holds a second factor, it must have been proved in THIS tab within five minutes,
 *       so that an already-open back-office tab is not a one-click route to a second session;</li>
 *   <li>the account's {@code device_login} flag must be true (V0181), and the account must still be one
 *       person's — the flag is set per account and a shared account cannot say which person it means;</li>
 *   <li>the code must name a live pending entry, which is consumed atomically so one approval can mint
 *       exactly once.</li>
 * </ol>
 *
 * <p>What is minted is NOT this session copied. It is a separate, independently revocable session, read-only,
 * capped at thirty minutes, carrying {@code dev} as its own method and never the approver's factors — so it
 * cannot approve a device login in its turn, nor touch anybody's credentials.
 *
 * <p>The audience comes from this session and never from what the device asked for. <b>That is the rule that
 * makes this a login rather than a bypass</b>: a factored account on a recovery-only session approves a
 * front-office device session, exactly as the mailbox route gives that account a front-office session today.
 *
 * @param code the code the operator typed, as typed
 *
 * @author Claude Code
 */
public record ApproveDeviceLoginCredentials(String code) {
}
