package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteRequest;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry.WriteVerb;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteGrantMembership;
import one.modality.crm.server.authn.gateway.shared.SuperAdminMembership;

import java.util.Set;

/**
 * Outside the back office, a Label is translated, by a translator, and nothing else.
 *
 * <p><b>The hole (2026-11-12).</b> The front office's translation page ({@code /translate-event/:eventId},
 * use-translation-edits.ts) updates labels through the generic submit, so Label is on
 * {@link FrontOfficeTablesWritePolicy}'s list — and nothing else asked who was writing. Any signed-in member, or an
 * anonymous page, could rewrite any label: an event's name, its booking form's text, its terms. Labels are shown to
 * every member in every language, and some are rendered as HTML.
 *
 * <p><b>The line.</b> Two front-office editors write labels, and both only ever UPDATE one language column of a label
 * named by id: the translation page, reached by a route grant ({@code /translate-event*}, operation
 * RouteToTranslateEvent — held by no role on staging, 2026-11-12, so in practice by super administrators), and the
 * in-place label editor of the booking pages (use-save-label.ts, LabelEditPopover), shown to testers
 * ({@code FrontendAccount.tester}, useIsTester). So a client without a verified back-office token may update a
 * label's language columns, by id, if it is a super administrator, a tester, or holds a grant covering the route.
 * Inserting or deleting a label, or writing its {@code ref}, organisation, alert or livestream flags, is the back
 * office's.
 *
 * <p><b>What it does not close.</b> The grant is asked "anywhere" ({@link RouteGrantMembership#mayReachRouteAnywhere}):
 * a translator for one event can translate any label. The page shows one event's labels, but a label does not say
 * which event it belongs to without a walk across the tables that use it. For the same reason a grant scoped to one
 * event or event type does not count, so such a translator is refused (security review, 2026-11-12).
 *
 * <p><b>Observed first</b> ("WOULD REFUSE a label write"); its REFUSED line will need a CloudWatch filter of its own.
 *
 * @author Claude Code
 */
final class LabelWritePolicy implements ClientSubmitGuard.WritePolicy {

    /** False while it observes: decisions are logged but nothing is refused. */
    static final boolean ENFORCING = false;

    /** The language columns, as the domain model names them (the translation page writes one per label). */
    static final Set<String> LANGUAGE_FIELDS = Set.of("de", "el", "en", "es", "fr", "it", "pt", "vi", "zhs", "zht");

    /** The route the translation page is granted by. */
    static final String TRANSLATION_ROUTE = "/translate-event";

    /** Told to the caller. */
    static final String REFUSED = "Labels are translated by translators, and otherwise changed in the back office";

    /** Who may translate. An interface so the check can answer it without a database. */
    interface TranslatorLookup {
        Future<Boolean> mayTranslate(Object personId, Object accountId);
    }

    static final TranslatorLookup DATABASE_LOOKUP = (personId, accountId) -> {
        EntityStore store = EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
        return SuperAdminMembership.isSuperAdminPerson(personId, store)
            .compose(superAdmin -> Boolean.TRUE.equals(superAdmin) ? Future.succeededFuture(true) : isTester(accountId, store))
            .compose(tester -> Boolean.TRUE.equals(tester) ? Future.succeededFuture(true)
                : RouteGrantMembership.mayReachRouteAnywhere(personId, TRANSLATION_ROUTE, store));
    };

    private static Future<Boolean> isTester(Object accountId, EntityStore store) {
        if (accountId == null)
            return Future.succeededFuture(false);
        return store.<dev.webfx.stack.orm.entity.Entity>executeQuery("select tester from FrontendAccount where id=$1",
                ProtectedEntityWritesJob.primaryKeyOf(accountId))
            .map(rows -> !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).getBooleanFieldValue("tester")));
    }

    @Override
    public Future<String> refusalReason(WriteRequest write) {
        if (write != null && !"Label".equals(write.entityName()))
            return Future.succeededFuture(null);
        // Read on the caller's thread: the guard asks every row rule synchronously, and the state is gone after the
        // first async hop.
        Object state = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = StateAccessor.getUserId(state);
        boolean backoffice = Boolean.TRUE.equals(StateAccessor.getVerifiedBackoffice(state));
        return judge(write, backoffice, GrantTableWritePolicy.callerPersonId(userId),
                MediaConsumptionWritePolicy.callerAccountId(userId), DATABASE_LOOKUP)
            .recover(failure -> Future.succeededFuture("could not check it: " + failure.getMessage()))
            .map(reason -> {
                if (reason == null)
                    return null;
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " a label write: "
                            + (write == null ? "?" : write.verb() + " Label") + " by " + CallerLabel.of(userId) + " — " + reason);
                return ENFORCING ? REFUSED : null;
            });
    }

    /** The decision, apart from the thread, the switch and the database. */
    static Future<String> judge(WriteRequest write, boolean backoffice, Object callerPersonId, Object callerAccountId,
                                TranslatorLookup lookup) {
        if (write == null)
            return Future.succeededFuture(null); // refused already, by BackofficeOnlyWritePolicy
        if (!"Label".equals(write.entityName()) || backoffice)
            return Future.succeededFuture(null);
        if (write.verb() != WriteVerb.UPDATE)
            return Future.succeededFuture(write.verb() == WriteVerb.INSERT ? "creates a label" : "deletes a label");
        if (!onlyLanguages(write))
            return Future.succeededFuture("changes a label field other than its translations");
        if (!isId(write.targetId()))
            return Future.succeededFuture("names no label it can be judged on");
        if (callerPersonId == null)
            return Future.succeededFuture("the caller has no person to translate as");
        return lookup.mayTranslate(callerPersonId, callerAccountId)
            .map(translator -> Boolean.TRUE.equals(translator) ? null : "translates without being a tester or holding the translation page's grant");
    }

    private static boolean onlyLanguages(WriteRequest write) {
        if (write.writtenFields() == null || write.writtenFields().length == 0)
            return false;
        for (String field : write.writtenFields())
            if (!LANGUAGE_FIELDS.contains(field))
                return false;
        return true;
    }

    private static boolean isId(Object value) {
        return value != null && value != ProtectedEntityWriteRegistry.UNRESOLVED_VALUE
               && !(value instanceof GeneratedKeyReference);
    }
}
