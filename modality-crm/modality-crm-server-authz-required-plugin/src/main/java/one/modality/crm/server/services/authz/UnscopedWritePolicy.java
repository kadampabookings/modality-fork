package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;

/**
 * A client may not send an UPDATE or a DELETE that names no bound on the rows it touches.
 *
 * <p>This is the server half of removing {@code ChangeSet.execute()}. That method let a browser compose
 * an arbitrary DQL statement and put it in the batch; deleting it closes the route for THIS client, and
 * a client is not something we control, so the most destructive shape is refused here too.
 *
 * <p><b>Be clear about what this is worth.</b> It is a floor, not a wall. It refuses a statement that
 * names no bound at all; it does not refuse a bound one that happens to match nearly everything —
 * {@code where removed = $1} passes, as does {@code delete from Attendance where documentLine=$1} with
 * an id the caller has no business naming. Whose rows a caller may touch is answered by the per-entity
 * authorizer, and for {@code person} by closing the table at step 2b. A rule here that tried to answer
 * it would be guessing from a statement with no caller in hand.
 *
 * <p><b>Two earlier versions of the test were unsound</b>, and the second is the one worth remembering:
 * "the WHERE mentions no column" sounds like "the WHERE constrains nothing" and is not, because
 * {@code delete from Person where id = id} mentions one. See {@code isUnboundedWrite} for the test that
 * replaced it, and for what a review had to catch to get there.
 *
 * <h3>Why only the whole-table shape, when the method allowed worse</h3>
 *
 * <p>The rule first written here was stronger still — every update and delete had to name a row id, so that
 * {@code update Document set person=$1 where person=$2}, the deleted method's own documented example,
 * would be refused. <b>That rule is false about this system, and enforcing it would have broken the
 * back office on the first deploy.</b> Three statements disprove it, all legitimate, all in use:
 *
 * <pre>
 *   delete from ListItem where list = $1                 (deleting a list deletes its items)
 *   delete from ListItem where list = $1 and person = $2 (taking one person off a list)
 * </pre>
 *
 * <p>A set-based delete is an ordinary thing for a client to send, and the reason it looks dangerous to
 * a rule like this is not that it is dangerous but that {@code targetId} is null for it — and null
 * there means "could not be read", not "unconstrained". Building a refusal on the absence of a reading
 * refuses the honest statements along with the rest; it also refused
 * {@code update Person set occasional=false where id=$1 and frontendAccount=$2}, which is the most
 * careful shape any client in this codebase sends, until {@code targetIdOf} learned to look inside a
 * conjunction.
 *
 * <p>So what is refused here is the one shape that is certainly unbounded and that nothing legitimate
 * sends: no WHERE at all. Bounding {@code where person=$2} to the caller's own people is a question
 * about ownership, which this cannot answer and the per-entity authorizer can — and for {@code person}
 * itself it stops mattering at step 2b, when the table closes to clients entirely.
 *
 * @author Claude Code
 */
final class UnscopedWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** Told to the caller, and deliberately about the statement rather than about them. */
    static final String UNBOUNDED_REFUSED = "A client update or delete must say which rows it changes";

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        if (write == null)
            return Future.succeededFuture(ClientSubmitGuard.UNCHECKABLE_REFUSED);
        // unbounded is already false for an insert, which has no WHERE to be missing — the verb does not
        // need testing again here, and testing it would only invite the two to drift apart.
        if (write.unbounded())
            return Future.succeededFuture(UNBOUNDED_REFUSED);
        return Future.succeededFuture(null);
    }
}
