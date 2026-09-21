package one.modality.crm.server.services.authz;

import dev.webfx.extras.operation.HasOperationCode;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.authz.server.AuthorizationServerService;
import dev.webfx.stack.authz.server.spi.AuthorizationServerServiceProvider;
import dev.webfx.stack.authz.server.spi.impl.AuthorizationServerServiceProviderBase;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Says which entities the server will not write on a caller's say-so, and what authorizes each.
 *
 * <p>The authorization tables are where privilege escalation lives: a caller able to write them can
 * grant themselves anything, at which point every other control in the system is decorative. So they
 * are the first entities the server refuses to change on a caller's say-so, and the reason item 6
 * starts here rather than with the data that merely matters.
 *
 * <p><b>The operation code is satisfied by super admins with no data migration.</b> A super admin's
 * grants are the literal string {@code grant operation:*}, and the wildcard matches any code that does
 * not begin with {@code RouteTo} — so no Operation row has to exist for this to work today, and adding
 * one later is how the right becomes delegable to somebody who is not a super admin. Which is the right
 * default: /operations and /authorizations sit under the Super Admin tile precisely because
 * administering privilege is not something a route grant should be able to hand out.
 *
 * <p>Note this deliberately does NOT check the route the back office uses to open that screen. Route
 * rules are a client concern — they decide which screen opens — and the server does not register their
 * parser at all. Asking the server whether a screen may open would be answering a different question
 * from the one that matters here, which is whether these rows may change.
 */
public final class ProtectedEntityWritesJob implements ApplicationJob {

    private static final String MANAGE_AUTHORIZATIONS = "ManageAuthorizations";

    /**
     * The raw statements a client may still send: the transaction prefixes the legacy JavaFX/GWT clients put at the
     * head of a batch (Triggers.backOfficeTransaction / frontOfficeTransaction). Matched exactly, with no parameters.
     * The React apps send none — they ask for the server's preamble instead.
     */
    private static final java.util.Set<String> LEGACY_RAW_STATEMENTS = java.util.Set.of(
        "select set_transaction_parameters(true)",
        "select set_transaction_parameters(false)");

    /**
     * Which operation codes authorize writing each protected entity — ANY of them is enough.
     *
     * <p>Any-of, not all-of, because this seam sees an entity and a verb but not which FIELDS a
     * statement writes. A letter's content and its properties are gated separately in the back office,
     * and both live on the same row: demanding one code would refuse somebody who legitimately holds
     * only the other. Any-of never denies a legitimate holder, while still refusing the caller who
     * holds neither — which is the escalation this is here to stop. Telling the two apart needs
     * field-level policy, which the spec sequences later; the loss is recorded here rather than left
     * to be discovered.
     *
     * <p>Named as they appear in DQL, because that is what the registry matches against.
     */
    private static final Map<String, String[]> REQUIRED_OPERATIONS = Map.ofEntries(
        // Privilege-bearing: a caller able to write these can grant themselves anything, at which point
        // every other control is decorative. Operation and OperationGroup are included because
        // rewriting an operation's definition is the same power reached by a different road.
        Map.entry("AuthorizationRule",                  new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationRole",                  new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationRoleOperation",         new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationOrganizationAdmin",     new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationOrganizationUserAccess",new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("AuthorizationSuperAdmin",            new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("Operation",                          new String[] { MANAGE_AUTHORIZATIONS }),
        Map.entry("OperationGroup",                     new String[] { MANAGE_AUTHORIZATIONS }),
        // Content surfaces whose back-office gates currently read `hasPermission(...) || true` — the
        // operation is NAMED and then unconditionally allowed, so today they are decoration. The server
        // check is what makes the name mean something; removing the `|| true` afterwards is what makes
        // the UI agree with it, and must not happen before the grants exist or the buttons vanish for
        // everyone.
        Map.entry("Letter",       new String[] { "EditLetterContent", "EditLetterProperties" }),
        Map.entry("PassTemplate", new String[] { "EditPassTemplate" }),
        // Administrative entities no ordinary member writes: zero front-office write sites, so an
        // operation code is the right shape of control here. Contrast Person (17 front-office write
        // sites), Document, DocumentLine and Attendance, where a member's right to write rests on the
        // row being THEIRS — an operation check there would either refuse every member or grant every
        // member, and neither is a boundary. Those need target-id resolution, not a longer list.
        //
        // NOT the RouteTo codes the back office uses to open these screens: `operation:*` deliberately
        // does not match a code beginning with RouteTo, so requiring RouteToOrganizations here would
        // refuse even a super admin. A code that means "may change these rows" is the right question
        // anyway — opening a screen and writing through it are not the same right.
        Map.entry("Organization", new String[] { "ManageOrganizations" }),
        Map.entry("MoneyAccount", new String[] { "ManageMoneyAccounts" })
    );

    /**
     * The privilege-bearing entities — those gated on {@link #MANAGE_AUTHORIZATIONS} above. The one list: the client
     * rule that enforces on them now, ahead of the switch below ({@link GrantTableWritePolicy}), reads it from here.
     */
    static java.util.Set<String> privilegeBearingEntities() {
        java.util.Set<String> entities = new java.util.HashSet<>();
        REQUIRED_OPERATIONS.forEach((entity, codes) -> {
            if (java.util.Arrays.asList(codes).contains(MANAGE_AUTHORIZATIONS))
                entities.add(entity);
        });
        return java.util.Set.copyOf(entities);
    }

    /**
     * Fields that are privileged on rows that otherwise are not — keyed "Entity.field".
     *
     * <p>FrontendAccount is the case that makes this necessary. Signup creates the row, and a member
     * changing their language on the booking page updates it, so the row must stay writable by ordinary
     * users; but `backoffice` decides whether an account may reach the back office at all, and that is
     * not the account holder's to set. Protecting the entity would refuse the signup; protecting nothing
     * leaves the flag writable by whoever holds the row. Only the field distinction fits.
     *
     * <p>What this buys is smaller than it looks, and worth stating so nobody over-trusts it: the flag
     * is a NECESSARY condition for back-office login, not a sufficient one. Grants come from roles, so
     * an account that sets it reaches an empty dashboard. This closes an unauthorized write, not an
     * escalation.
     */
    private static final Map<String, String[]> REQUIRED_OPERATIONS_BY_FIELD = Map.of(
        "FrontendAccount.backoffice", new String[] { "ManageBackofficeAccess" }
    );

    /** The languages EntityHasI18nFields carries; a bare language code IS a body field. */
    private static final java.util.Set<String> I18N_LANGUAGES =
        java.util.Set.of("de", "el", "en", "es", "fr", "it", "pt", "vi", "zhs", "zht");

    /**
     * Whether a Letter field holds CONTENT — what a member actually receives — rather than a property.
     *
     * <p>Matched by pattern rather than enumerated, and deliberately this way round. The content fields
     * are language-suffixed and multiply whenever a language is added, while the property fields are a
     * finite set that is nonetheless NOT fully declared in the Letter interface: the back office edits
     * an Active toggle and an automation code that have no field constant there. Enumerating properties
     * would therefore have silently misfiled whichever ones I failed to find, and misfiled them as
     * content — refusing an editor who holds only the properties right. Enumerating content instead
     * makes an unrecognised field a PROPERTY, which is both the larger set and the less consequential
     * mistake: letter content is what lands in members' inboxes.
     */
    // Package-private, not private: the decision logic is what a check can actually verify without a
    // running stack, and two bugs in it reached production before anything tested it.
    static boolean isLetterContentField(String field) {
        return I18N_LANGUAGES.contains(field)
               || field.startsWith("subject_")
               || field.startsWith("push_title_")
               || field.startsWith("push_body_");
    }

    /**
     * OBSERVE FIRST. While this is false the decision is computed and logged but never acted on, so a
     * deploy cannot lock anyone out of the screen they would need to fix a lockout — the failure mode
     * that makes authorization administration the worst possible place to enforce something untested.
     * Flip it once the log has been quiet on a real environment for long enough to believe it.
     *
     * <p>Not a config value yet, deliberately: a switch that can be flipped without a code review is
     * one that can be flipped without anyone reading the log it depends on.
     */
    private static final boolean ENFORCING = false;

    @Override
    public void onInit() {
        // The pre-filter needs every name this policy can react to, including the entities that are
        // protected only at field level — a statement naming one of those must still be parsed, or the
        // field rule would never be reached.
        java.util.Set<String> preFilterNames = new java.util.LinkedHashSet<>(REQUIRED_OPERATIONS.keySet());
        REQUIRED_OPERATIONS_BY_FIELD.keySet().forEach(key -> preFilterNames.add(key.substring(0, key.indexOf('.'))));
        ProtectedEntityWriteRegistry.registerWriteAuthorizer(this::isWriteAuthorized,
            preFilterNames.toArray(String[]::new));
        ProtectedEntityWriteRegistry.registerWriteObserver(ProtectedEntityWritesJob::onProtectedWriteSucceeded);
        ProtectedEntityWriteRegistry.registerRawStatementObserver(ProtectedEntityWritesJob::onNonDqlSubmit);
        // Step 0 of the front-office write plan: learn what the clients write before writing the rule
        // that refuses the rest. Registered here rather than as its own ApplicationJob so that the
        // module's generated service declarations stay untouched — one registration line is a smaller
        // thing to own than a regenerated module-info.
        ProtectedEntityWriteRegistry.registerWriteInspector(new ClientWriteInventory());
        // The read half of the same item, narrowly: not read authorisation, which is later, but the columns whose
        // disclosure is a way in. Registered here for the same reason as the inventory above.
        ClientReadSecrets.declare();
        // And the write half of that: the account columns no client may set, enforced now rather than observed —
        // see ClientWriteSecrets for why the observe-only switch below does not apply to them.
        ClientWriteSecrets.declare();
        // Raw statements from a client skip every check that reads what a statement touches, the one above
        // included, so they are refused — except the two fixed transaction prefixes the legacy clients still send,
        // which keep those clients working exactly as before. Not decided by WHO is asking: a grant-based
        // exception would be only as strong as the weakest way to obtain the grant. Consequence, accepted: the
        // legacy scheduled-item generator (ScheduledItemGenerationView), which sends generated SQL, no longer runs.
        ClientSubmitGuard.registerRawStatementPolicy(ProtectedEntityWritesJob::isAllowedLegacyRawStatement);
        // And the row rules that cannot wait for the switch below, asked together because the guard holds one:
        // - an owner's email is their login (V0062 trigger), so a client may not change it, nor make somebody an
        //   owner. See OwnerLoginWritePolicy.
        // - the grant tables themselves: while the rule on them above only observes, any client could insert a
        //   super-admin row naming its own person. See GrantTableWritePolicy.
        // - and the person a sign-in resolves to: a client may not move somebody's person into an account it controls,
        //   which would make its sign-in resolve to them - their bookings, and their grants if any. Staff only, and
        //   a grant holder by a super admin only. See PersonAccountMovePolicy.
        // - and the statements that name no bound on the rows they touch: no where clause, or one with no
        //   equality tying a column to a value. The server half of removing ChangeSet.execute().
        //   Deliberately NOT "must name a row id" - the back office legitimately sends set-based
        //   deletes, and a rule that refused them would be walked back on the first deploy.
        //   See UnscopedWritePolicy.
        ClientSubmitGuard.registerWritePolicy(new ClientWritePolicies(
            new OwnerLoginWritePolicy(), new GrantTableWritePolicy(), new PersonAccountMovePolicy(),
            new UnscopedWritePolicy()));
        Console.log("🛡 Write authorization active on " + REQUIRED_OPERATIONS.size() + " entities and "
                    + REQUIRED_OPERATIONS_BY_FIELD.size() + " fields"
                    + (ENFORCING ? " — ENFORCING" : " — observing only, nothing is refused yet"));
        Console.log("🛡 Client write inventory recording — shapes only, no values, nothing refused");
        // Said separately, and said even though the line above has just said "nothing is refused yet":
        // that sentence is about the per-entity operation rule and the switch that gates it, and the row
        // rules below it are NOT gated by that switch. An operator reading only the line above would
        // triage a write that started failing on this deploy by looking anywhere but here.
        Console.log("🛡 Client write row rules ENFORCING — owner login, grant tables, person account move,"
                    + " unbounded writes");
    }

    /**
     * Throws away the cached grants when the rows they were computed from change.
     *
     * <p>Only the privilege-bearing entities matter here: editing a letter does not change what anyone
     * may do, so flushing on that would empty the cache constantly for nothing.
     *
     * <p>This is the fast path, not the guarantee. It fixes the instance that served the write; another
     * instance behind the same load balancer saw nothing and still holds its own copy, which is why the
     * registry cache also has a TTL. Without both, a revocation was not slow — it was indefinite,
     * lasting until the process restarted.
     */
    private static void onProtectedWriteSucceeded(String entityName, ProtectedEntityWriteRegistry.WriteVerb verb) {
        if (!entityName.startsWith("Authorization") && !entityName.startsWith("Operation"))
            return;
        AuthorizationServerServiceProvider provider = AuthorizationServerService.getProvider();
        if (provider instanceof AuthorizationServerServiceProviderBase base) {
            base.invalidateAllRuleRegistries();
            Console.log("🛡 " + verb + " on " + entityName + " — cached authorizations discarded on this instance");
        }
    }

    /**
     * Whether a client's raw statement is one of the legacy transaction prefixes — exactly, with nothing appended and
     * no parameters. Package-private for the same reason as isLetterContentField: it is the decision a check can
     * verify without a running stack (scripts/checks/client-write-guard).
     */
    static boolean isAllowedLegacyRawStatement(dev.webfx.stack.db.submit.SubmitArgument argument) {
        return argument != null
               && argument.getStatement() != null
               && LEGACY_RAW_STATEMENTS.contains(argument.getStatement().trim())
               && (argument.getParameters() == null || argument.getParameters().length == 0);
    }

    /**
     * Item 7's instrument: a statement that skips DQL translation reaches the database as written, and
     * therefore passes none of the checks above — they read an entity and a verb that only the DQL layer
     * can see. So the write authorization built this week protects against callers who ask in DQL, and
     * this line measures how much that qualification is worth.
     *
     * <p>Only CLIENT-originated ones are reported. Server code composing SQL is ordinary; a client doing
     * it is the door. The distinction is available for the first time because the SockJS bridge now
     * stamps everything arriving from outside, and a caller cannot un-stamp itself.
     *
     * <p>Truncated, and the statement is logged rather than its parameters: a raw statement is written
     * by whoever sent it, so the text is theirs, but its parameter values may be anybody's data.
     */
    private static void onNonDqlSubmit(String language, String statement) {
        if (!ThreadLocalStateHolder.isClientOrigin())
            return;
        String shortened = statement == null ? "(none)"
            : statement.length() <= 200 ? statement : statement.substring(0, 200) + "…";
        Console.log("🛡 RAW STATEMENT from a client (language=" + language + ") — bypasses write"
                    + " authorization entirely: " + shortened);
    }

    private Future<Boolean> isWriteAuthorized(ProtectedEntityWriteRegistry.WriteRequest request) {
        String entityName = request.entityName();
        ProtectedEntityWriteRegistry.WriteVerb verb = request.verb();
        java.util.List<String[]> groups = requiredCodeGroups(entityName, request.writtenFields());
        if (groups.isEmpty()) // the textual pre-filter matched a name this policy does not actually cover
            return Future.succeededFuture(true);
        // Capture the WHOLE state, not just the user id, and on THIS thread. The thread local is
        // restored the moment the synchronous portion returns, so anything read after the first async
        // hop sees nothing — and every authorization call below is asynchronous. Reading only the user
        // id here was not enough: it made the log line right while every check after the first still
        // ran with no principal at all, was answered with the PUBLIC grants, and refused a super admin.
        Object capturedState = ThreadLocalStateHolder.getThreadLocalState();
        Object userId = ThreadLocalStateHolder.getUserId();
        return holdsAllGroups(groups, capturedState)
            .map(authorized -> {
                if (Boolean.TRUE.equals(authorized))
                    return true;
                // The principal is logged because this line exists to be acted on: knowing that SOMETHING
                // was refused is not enough to tell whether a real administrator is about to be locked
                // out. It is an account identity, not personal data about a data subject.
                Console.log("🛡 " + (ENFORCING ? "REFUSED" : "WOULD REFUSE") + " " + verb + " on "
                            + entityName + " by " + userId + " (needs "
                            + groups.stream().map(g -> String.join(" or ", g)).collect(java.util.stream.Collectors.joining(" and "))
                            + ")");
                return !ENFORCING;
            });
    }

    /**
     * Every requirement this write must satisfy: ALL groups, any code within a group.
     *
     * <p>All-of across groups is what makes field rules mean anything. Pooling them into one any-of
     * list — which is what an earlier version did — would have let somebody holding only
     * EditLetterProperties rewrite a letter's content, because the pooled list contained a code they
     * held. A statement touching both content and properties needs both rights, and that is only
     * expressible as separate groups.
     *
     * <p>The entity rule stays as its own group even where field rules cover the same entity. It is the
     * backstop for a statement whose assignments could not be read: a computed left-hand side yields no
     * field name, so no field rule fires, and without the entity group such a write would pass
     * unexamined on a technicality.
     */
    static java.util.List<String[]> requiredCodeGroups(String entityName, String[] writtenFields) {
        java.util.List<String[]> groups = new ArrayList<>();
        String[] entityCodes = REQUIRED_OPERATIONS.get(entityName);
        if (entityCodes != null)
            groups.add(entityCodes);
        boolean letterContent = false, letterProperties = false;
        for (String field : writtenFields) {
            String[] fieldCodes = REQUIRED_OPERATIONS_BY_FIELD.get(entityName + "." + field);
            if (fieldCodes != null)
                groups.add(fieldCodes);
            if ("Letter".equals(entityName)) {
                if (isLetterContentField(field)) letterContent = true;
                else letterProperties = true;
            }
        }
        if (letterContent)
            groups.add(new String[] { "EditLetterContent" });
        if (letterProperties)
            groups.add(new String[] { "EditLetterProperties" });
        return groups;
    }

    /**
     * Every group must be satisfied.
     *
     * <p>All groups are asked AT ONCE rather than in sequence, which is not an optimisation. Chaining
     * them with compose() put every question after the first into an async callback, where the thread
     * local carrying the principal has already been restored — so the second group onwards was judged
     * with no principal, answered from the public grants, and refused. Asking them together keeps every
     * call on the synchronous side of the first hop; the state is passed explicitly as well, so a future
     * change of shape cannot quietly reintroduce the same thing.
     *
     * <p>The cost of not short-circuiting is one extra cached lookup per group. The cost of
     * short-circuiting was a super admin being refused.
     */
    private Future<Boolean> holdsAllGroups(java.util.List<String[]> groups, Object capturedState) {
        List<Future<Boolean>> answers = new ArrayList<>(groups.size());
        for (String[] group : groups)
            answers.add(holdsAnyOf(group, capturedState));
        return Future.all(new ArrayList<>(answers))
            .map(composite -> {
                for (int i = 0; i < answers.size(); i++)
                    if (!Boolean.TRUE.equals(composite.resultAt(i)))
                        return false;
                return true;
            })
            .otherwise(false);
    }

    /**
     * True if any code authorizes. All are asked at once, and each inside the captured state.
     *
     * <p>isAuthorized() reads the principal from the thread local itself, so restoring that state
     * around the call is what makes the answer about this caller rather than about the public.
     */
    private Future<Boolean> holdsAnyOf(String[] codes, Object capturedState) {
        List<Future<Boolean>> answers = new ArrayList<>(codes.length);
        for (String code : codes)
            answers.add(ThreadLocalStateHolder.runWithState(capturedState,
                () -> AuthorizationServerService.isAuthorized(new OperationRequest(code)).otherwise(false)));
        return Future.all(new ArrayList<>(answers))
            .map(composite -> {
                for (int i = 0; i < answers.size(); i++)
                    if (Boolean.TRUE.equals(composite.resultAt(i)))
                        return true;
                return false;
            })
            .otherwise(false);
    }

    /** The question put to the rule registry. Only {@link HasOperationCode} requests match operation rules. */
    private record OperationRequest(String operationCode) implements HasOperationCode {
        @Override
        public Object getOperationCode() {
            return operationCode;
        }
    }
}
