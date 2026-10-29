package one.modality.crm.server.services.authz;

import dev.webfx.extras.operation.HasOperationCode;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.authz.core.HasAuthorizationContext;
import dev.webfx.stack.authz.server.AuthorizationServerService;
import dev.webfx.stack.authz.server.spi.AuthorizationServerServiceProvider;
import dev.webfx.stack.authz.server.spi.impl.AuthorizationServerServiceProviderBase;
import dev.webfx.stack.db.query.ClientReadInspectionRegistry;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
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
     * Writing an organisation, split three ways (V0130) because three kinds of person do it: whoever
     * maintains a centre's description, whoever holds its media credentials, and whoever sets up its
     * residents. One code over the whole entity meant a centre's technical person had to be made a
     * super administrator to set a video key, which hands them every other centre's data too.
     */
    private static final String EDIT_ORGANIZATION_DETAILS = "EditOrganizationDetails",
        EDIT_ORGANIZATION_MEDIA = "EditOrganizationMedia",
        EDIT_ORGANIZATION_RESIDENTS_EVENT = "EditOrganizationResidentsEvent";

    /**
     * A booking's own tables, written by the back office through the generic API (2026-10-19, V0135). Only the
     * fields its screens actually write are granted, each to the act that writes it; every OTHER field of these
     * tables needs {@link #MANAGE_BOOKINGS}, which is granted to nobody, so it is a super administrator's.
     *
     * <p>Why the rest is closed rather than left to the entity's codes: the entity rule is any-of, so without
     * this a holder of RunEducationProgramRegistration — needed only to set a payer — could write `cancelled`
     * or a price on any booking of their centre. The back office never writes those fields generically: it
     * confirms, cancels and reprices through the document service. A future screen that does gets a code then.
     */
    static final String EDIT_BOOKING_DETAILS = "EditBookingDetails",
        ALLOCATE_ROOMS = "AllocateRooms",
        EDIT_MEDIA_ACCESS = "EditMediaAccess",
        RUN_EDUCATION_PROGRAM_REGISTRATION = "RunEducationProgramRegistration",
        MANAGE_BOOKINGS = "ManageBookings";

    /**
     * The tables whose unlisted fields need {@link #MANAGE_BOOKINGS} — and whose SERVER writes skip this rule
     * entirely. The document service writes them for every booking, line and attendance; a server write is
     * allowed whatever the rule says, so judging one would only cost owner lookups on the busiest path there is.
     */
    static final java.util.Set<String> BOOKING_ENTITIES = java.util.Set.of("Document", "DocumentLine", "Attendance");

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
        // Any of the three: the backstop for a statement whose assignments cannot be read field by
        // field, which the field rules below then narrow to the group actually written. ManageOrganizations
        // is retired rather than seeded — it covered all three and was held by nobody but super admins.
        Map.entry("Organization", new String[] { EDIT_ORGANIZATION_DETAILS, EDIT_ORGANIZATION_MEDIA,
                                                 EDIT_ORGANIZATION_RESIDENTS_EVENT }),
        Map.entry("MoneyAccount", new String[] { "ManageMoneyAccounts" }),
        // A booking's own tables (see EDIT_BOOKING_DETAILS). The entity rule is the backstop for a statement
        // whose fields cannot be read; the field rules below and MANAGE_BOOKINGS for the rest do the real work.
        Map.entry("Document",     new String[] { EDIT_BOOKING_DETAILS, RUN_EDUCATION_PROGRAM_REGISTRATION,
                                                 "ManageEducationProgramRegistrants" }),
        Map.entry("DocumentLine", new String[] { ALLOCATE_ROOMS }),
        Map.entry("Attendance",   new String[] { EDIT_MEDIA_ACCESS })
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
    private static final Map<String, String[]> REQUIRED_OPERATIONS_BY_FIELD = Map.ofEntries(
        Map.entry("FrontendAccount.backoffice", new String[] { "ManageBackofficeAccess" }),
        // The organisation's own description. The dialog writes these as one statement, separately from
        // the media credentials below, so the two groups are judged apart without anything being split here.
        Map.entry("Organization.name",         new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.type",         new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.country",      new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.closed",       new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.email",        new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.phone",        new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.street",       new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.cityName",     new String[] { EDIT_ORGANIZATION_DETAILS }),
        Map.entry("Organization.postCode",     new String[] { EDIT_ORGANIZATION_DETAILS }),
        // The media credentials. `bunnyApiKey` is a secret a browser can write and is on no deny list,
        // so this makes writing it a named act rather than a side effect of managing a centre. Closing
        // it means a server endpoint and a column deny, which is a separate change.
        Map.entry("Organization.bunnyApiKey",              new String[] { EDIT_ORGANIZATION_MEDIA }),
        Map.entry("Organization.audioBunnyPullZoneId",     new String[] { EDIT_ORGANIZATION_MEDIA }),
        Map.entry("Organization.audioBunnyStorageZoneId",  new String[] { EDIT_ORGANIZATION_MEDIA }),
        // The residents event, written from /residents rather than from the organisations page. It also
        // INSERTS the Event it points at, and Event is in no rule here, so this guards the pointer and
        // not the creation.
        Map.entry("Organization.residentEvent", new String[] { EDIT_ORGANIZATION_RESIDENTS_EVENT }),
        // A booking's personal details as booked — the registration drawer's form, and a new booking's details.
        Map.entry("Document.person_age", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_cityName", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_country", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_email", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_firstName", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_lastName", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_layName", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_male", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_nationality", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_ordained", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_organization", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_passport", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_phone", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_postCode", new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.person_street", new String[] { EDIT_BOOKING_DETAILS }),
        // Its comments. specialNeeds holds health and accessibility notes: special-category data.
        Map.entry("Document.request",      new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.comment",      new String[] { EDIT_BOOKING_DETAILS }),
        Map.entry("Document.specialNeeds", new String[] { EDIT_BOOKING_DETAILS }),
        // Who pays: set by education programmes — both when a run registers people and when a registrant's payer
        // is changed afterwards (use-registrant-mutations changes ListItem.payer and Document.payer together, as
        // managing registrants) — or by whoever edits the booking. The client's declaration is not what decides
        // this: the server reads the caller's GRANTS, so every code that legitimately writes the field is listed.
        Map.entry("Document.payer", new String[] {
            RUN_EDUCATION_PROGRAM_REGISTRATION, "ManageEducationProgramRegistrants", EDIT_BOOKING_DETAILS }),
        // Which room a line is in: the rooming tab, rooms setup and event room setup.
        Map.entry("DocumentLine.resourceConfiguration", new String[] { ALLOCATE_ROOMS }),
        // How long a booked recording stays watchable.
        Map.entry("Attendance.expirationDate", new String[] { EDIT_MEDIA_ACCESS })
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
     * OBSERVE FIRST — per entity. For an entity not listed here the decision is computed and logged but never
     * acted on, so a deploy cannot lock anyone out of the screen they would need to fix a lockout — the failure
     * mode that makes authorization administration the worst possible place to enforce something untested.
     * Add an entity once its census has been quiet on a real environment for long enough to believe it.
     *
     * <p>Per entity since 2026-10-19 (it was one switch for all): each entity's census runs on its own clock.
     * Letters and pass templates have been observed since the context fix; the booking tables join later and
     * must not hold them back, nor be flipped with them.
     *
     * <p>Not a config value, deliberately: a switch that can be flipped without a code review is one that can
     * be flipped without anyone reading the log it depends on.
     *
     * <p><b>FrontendAccount, 2026-10-23, ahead of the rest.</b> Its only rule is the field rule on {@code backoffice}
     * (ManageBackofficeAccess), so enforcing it refuses nothing but a client setting that flag without the operation:
     * signup and a language change write other fields and need nothing. It could not wait for the general flip.
     * The flag is the one thing a back-office login requires (the second factor is optional in production), and a
     * verified back-office session is unrestricted in the document service — it loads any booking and submits to
     * any, payments included, while BackofficeSubmitScope only observes. Nothing confines a client to its own
     * account row (the person row rules went with denyTable("person"), and UnscopedWritePolicy only asks for a
     * bound), so any caller able to submit DQL could set the flag on ANY account — its own being merely the easiest
     * — then sign in to the back office and read or change every booking. The 3-day census (2026-10-20/22) logged no
     * would-be refusal on it: the grant screen's writes come from holders of the operation.
     *
     * <p><b>Letter and PassTemplate, 2026-10-26.</b> Observed since the context fix (2026-10-18); the 8-day census
     * to 2026-10-25 logged no would-be refusal on either, while letters were being written. Their back-office
     * gates still read {@code hasPermission(...) || true}: removing that comes after this, never before, or the
     * buttons would vanish for everyone (see REQUIRED_OPERATIONS).
     *
     * <p><b>Document, DocumentLine, Attendance and Organization, 2026-10-29.</b> The CENTRE layer of the booking
     * tables: BackofficeOnlyWritePolicy (enforced 2026-10-28) decides that only a verified back office writes them,
     * and this decides which back-office user may write which booking — the right for the field, in the booking's
     * own organization and event. Without it a member of staff at one centre could change another centre's booking
     * details, rooms or recording access, or its organisation record, by direct writes. The censuses to 2026-10-28
     * (7 days) and 2026-10-29 logged no would-be refusal on any of the four; every back-office write to them was
     * checked against the field rules on 2026-10-29 (personal details, comments, payer, room, recording expiry, the
     * three organisation groups). Server writes, the document service's included, are never refused here.
     * MoneyAccount stays observing: clients are denied money_account outright, so there is nothing to enforce.
     */
    static final java.util.Set<String> ENFORCED_ENTITIES = java.util.Set.of("FrontendAccount", "Letter", "PassTemplate",
        "Document", "DocumentLine", "Attendance", "Organization");

    /** Whether this entity's rule refuses (true) or only observes (false). See {@link #ENFORCED_ENTITIES}. */
    static boolean isEnforced(String entityName) {
        return ENFORCED_ENTITIES.contains(entityName);
    }

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
        // And step 0 of the READ plan, the twin of the write inventory: learn what the clients read before
        // writing the rule that constrains the rest. Reads are the larger half of item 6 by exposure and the
        // half with no rule at all yet, so the inventory is what the rule will be written from.
        ClientReadInspectionRegistry.registerReadInspector(new ClientReadInventory());
        // And the half of the read plan that the framework cannot supply: who the caller is. The compiler
        // turns CALLER_PERSON into a bound parameter and has no notion of a person, so this answers it from
        // the principal — on the endpoint thread, which is the only place the principal is still there.
        // Registered here for the same reason as the two above.
        ModalityCallerParameterResolver.register();
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
        // - the grant tables themselves: while the rule on them above only observes, any client could insert a
        //   super-admin row naming its own person. See GrantTableWritePolicy.
        // - and the statements that name no bound on the rows they touch: no where clause, or one with no
        //   equality tying a column to a value. The server half of removing ChangeSet.execute().
        //   Deliberately NOT "must name a row id" - the back office legitimately sends set-based
        //   deletes, and a rule that refused them would be walked back on the first deploy.
        //   See UnscopedWritePolicy.
        // - and mail that is not the caller's own: a pending mail is a live sign-in link or somebody's
        //   booking, and until now any client could add its own address to one, or rewrite it. Clients
        //   still compose mail, which is the relay itself and is not closed here. See MailWritePolicy.
        //
        // THREE RULES USED TO STAND HERE AND NO LONGER DO: an owner's email is their login, an email on
        // somebody else's account's person, and moving a person into another account. All three asked
        // their question of "Person", and ClientWriteSecrets now denies that table outright — the deny
        // list is consulted before any row rule, so none of them could ever be reached again. They were
        // deleted on 2026-09-28 rather than left as dead code that reads like live defence.
        // ClientWriteGuardCheck asserts the deny rule and their absence TOGETHER: restoring the table
        // without restoring the rules is what would reopen the holes they covered. What they knew that
        // the deny rule does not say is written down in docs/security/frontoffice-write-authorization-plan.md.
        // - and a booking's own tables written by anything but the back office (2026-10-19), observing until
        //   its own switch flips. See BackofficeOnlyWritePolicy.
        ClientSubmitGuard.registerWritePolicy(new ClientWritePolicies(
            new GrantTableWritePolicy(), new UnscopedWritePolicy(), new MailWritePolicy(), new BackofficeOnlyWritePolicy()));
        Console.log("🛡 Write authorization active on " + REQUIRED_OPERATIONS.size() + " entities and "
                    + REQUIRED_OPERATIONS_BY_FIELD.size() + " fields"
                    + (ENFORCED_ENTITIES.isEmpty() ? " — observing only, nothing is refused yet"
                       : " — ENFORCING on " + String.join(", ", new java.util.TreeSet<>(ENFORCED_ENTITIES))
                         + ", observing the rest"));
        Console.log("🛡 Client write inventory recording — shapes only, no values, nothing refused");
        Console.log("🛡 Client read inventory recording — shapes only, no values, nothing refused");
        // Said separately, and said even though the line above has just said "nothing is refused yet":
        // that sentence is about the per-entity operation rule and the switch that gates it, and the row
        // rules below it are NOT gated by that switch. An operator reading only the line above would
        // triage a write that started failing on this deploy by looking anywhere but here.
        Console.log("🛡 Client write row rules ENFORCING — grant tables, unbounded writes, mail"
                    + " (person is closed outright by the deny list, not by a row rule); booking tables back-office only — "
                    + (BackofficeOnlyWritePolicy.ENFORCING ? "ENFORCING" : "observing only"));
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

    /**
     * Whether a write to a protected entity is refused for having nobody behind it — enforced whatever
     * {@link #ENFORCED_ENTITIES} says.
     *
     * <p>Found 2026-10-10: while this rule only observed, a client with no signed-in person could rewrite
     * letter content (emailed to members from the organisation), organisations and pass templates —
     * nothing else on the client path asked who was writing. Production logged 19 such letter writes in
     * 30 days, most likely back-office tabs whose session had lapsed; an anonymous connection to the
     * public front office is in exactly the same state.
     *
     * <p>Why not enforce everywhere ({@link #ENFORCED_ENTITIES}): the same 30 days show two SIGNED-IN members of staff whose roles
     * open the pass designer and the letter editor without holding EditPassTemplate or
     * EditLetterContent. Enforcing would lock them out; this refuses only the caller who has no person at
     * all — anonymous, a guest, a support view ({@link GrantTableWritePolicy#callerPersonId}) — which no
     * legitimate writer of these entities is.
     *
     * <p><b>Client writes only.</b> This rule also sees server writes, which run with no principal; they
     * are not what this closes and must not be refused by it.
     */
    static boolean refusesWithoutIdentity(Object userId, boolean clientOrigin) {
        return clientOrigin && GrantTableWritePolicy.callerPersonId(userId) == null;
    }

    /**
     * Whether a write that failed its authorization is actually refused, or only logged.
     *
     * <p><b>Enforcement is for CLIENT writes.</b> The server's own jobs write these entities with no
     * principal of their own, and judging them against the public grants refuses them: the KDM sync in
     * {@code OrganisationUpdateJob} updates {@code Organization} rows on every boot, reports failure to
     * {@code Console::error} and nothing else, so with enforcement on and no client-origin fence every
     * start would have silently stopped syncing centres. That is also what the census has been showing
     * all along — the {@code WOULD REFUSE … by null} rows, which since 2026-10-10 can only be server
     * writes, because a client write with no person is refused earlier and logged differently.
     *
     * <p>Gated on {@code clientOrigin} being TRUE, never on "no principal, therefore the server": a
     * client whose session has lapsed has no principal either, and that is the case this rule most
     * wants to refuse. The two have been conflated elsewhere in this system and the fix was the same.
     *
     * <p>{@code enforcing} is a parameter rather than a read of the constant so that the behaviour
     * after the flip can be asserted while the constant is still false — the alternative is a check
     * that can only exercise the half that is live today.
     */
    static boolean refusesOnFailedAuthorization(boolean enforcing, boolean clientOrigin) {
        return enforcing && clientOrigin;
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
        boolean clientOrigin = ThreadLocalStateHolder.isClientOrigin();
        if (!clientOrigin && BOOKING_ENTITIES.contains(entityName))
            return Future.succeededFuture(true); // see BOOKING_ENTITIES: allowed anyway, and the busiest path
        if (refusesWithoutIdentity(userId, clientOrigin)) {
            Console.log("🛡 REFUSED " + verb + " on " + entityName + " by " + userId
                        + " (no signed-in person behind this client write)");
            return Future.succeededFuture(false);
        }
        // Where the row lives decides which grants can answer (see authorizationContexts). The lookups are
        // asynchronous, which is why the state was captured above: every check below restores it explicitly.
        // Ends every AUTHORIZATION refusal line, so one log filter ("REFUSED" "(needs") catches all of them
        // (and nothing else: a failed owner lookup is worded differently, below).
        String needs = " (needs " + groups.stream().map(g -> String.join(" or ", g))
            .collect(java.util.stream.Collectors.joining(" and ")) + ")";
        // Where the row lives decides which grants can answer (see authorizationContexts). The lookups are
        // asynchronous, which is why the state was captured above: every check below restores it explicitly.
        return authorizationContexts(request, DATABASE_LOOKUP)
            .compose(contexts -> holdsInEveryContext(groups, contexts, capturedState)
                .map(authorized -> {
                    if (Boolean.TRUE.equals(authorized))
                        return true;
                    boolean refusing = refusesOnFailedAuthorization(isEnforced(entityName), clientOrigin);
                    // The principal is logged because this line exists to be acted on: knowing that SOMETHING
                    // was refused is not enough to tell whether a real administrator is about to be locked
                    // out. It is an account identity, not personal data about a data subject. So is where it
                    // was refused: the organization and event ids say which grant is missing.
                    //
                    // A server write says so, so that a census reader is not left inferring it from a null
                    // principal — which is how the KDM sync sat in these rows unnoticed.
                    Console.log("🛡 " + (refusing ? "REFUSED" : "WOULD REFUSE") + " " + verb + " on " + entityName
                                + " by " + userId + (clientOrigin ? "" : " [server write, allowed]")
                                + " in " + describe(contexts) + needs);
                    return !refusing;
                }))
            // A lookup that fails has not said where the row is, and so cannot have authorized it. The refusal
            // still answers to the switch: while observing, nothing is refused for it either.
            .recover(failure -> {
                boolean refusing = refusesOnFailedAuthorization(isEnforced(entityName), clientOrigin);
                // Its own wording, deliberately NOT ending in "(needs …)": this is a failed read, not a missing
                // grant, and the production alarm filters match per message — a database blip that read like an
                // authorization refusal would be triaged as somebody lacking a grant.
                Console.log("🛡 " + (refusing ? "REFUSED" : "WOULD REFUSE") + " " + verb + " on " + entityName
                            + " by " + userId + (clientOrigin ? "" : " [server write, allowed]")
                            + " — OWNER LOOKUP FAILED, so where the row lives is unknown: " + failure.getMessage());
                return Future.succeededFuture(!refusing);
            });
    }

    /** Every group in every context: the write must be allowed wherever it lands. */
    private Future<Boolean> holdsInEveryContext(java.util.List<String[]> groups, List<Map<String, String>> contexts,
                                                Object capturedState) {
        List<Future<Boolean>> answers = new ArrayList<>(contexts.size());
        for (Map<String, String> context : contexts)
            answers.add(holdsAllGroups(groups, context, capturedState));
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
            else if (BOOKING_ENTITIES.contains(entityName))
                // A booking field no screen writes generically: a super administrator's (see MANAGE_BOOKINGS).
                groups.add(new String[] { MANAGE_BOOKINGS });
            if ("Letter".equals(entityName)) {
                if (isLetterContentField(field)) letterContent = true;
                else letterProperties = true;
            }
        }
        if (letterContent)
            groups.add(new String[] { "EditLetterContent" });
        if (letterProperties)
            groups.add(new String[] { "EditLetterProperties" });
        return distinct(groups);
    }

    /**
     * The same groups, each kept once, in the order they were first required.
     *
     * <p>Groups repeat because they are collected per FIELD: on a booking entity every field without a rule of
     * its own requires {@link #MANAGE_BOOKINGS}, so a six-field insert asked for it six times. Repetition never
     * changed the decision — every group must hold, and a group asked twice holds twice — but it cost a
     * duplicate authorization call each time, and it reached the reader: production's census carried
     * {@code (needs EditMediaAccess and ManageBookings and ManageBookings and ManageBookings and ManageBookings)}
     * for one attendance, and thirteen terms for one document. That line is the only evidence of somebody being
     * locked out, and after the flip it is also what the person refused is shown. A list that grows with the
     * batch describes the batch; this one describes the row.
     *
     * <p>Order-sensitive on purpose: {@code {A, B}} and {@code {B, A}} are the same requirement, but the groups
     * are written out literally in this file, so treating them as different costs nothing and keeps the
     * comparison obvious. Equality is by content, not identity — the same array is rarely the same object.
     */
    private static java.util.List<String[]> distinct(java.util.List<String[]> groups) {
        java.util.LinkedHashMap<java.util.List<String>, String[]> unique = new java.util.LinkedHashMap<>();
        for (String[] group : groups)
            unique.putIfAbsent(java.util.Arrays.asList(group), group);
        return new ArrayList<>(unique.values());
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
    private Future<Boolean> holdsAllGroups(java.util.List<String[]> groups, Map<String, String> context, Object capturedState) {
        List<Future<Boolean>> answers = new ArrayList<>(groups.size());
        for (String[] group : groups)
            answers.add(holdsAnyOf(group, context, capturedState));
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
    private Future<Boolean> holdsAnyOf(String[] codes, Map<String, String> context, Object capturedState) {
        List<Future<Boolean>> answers = new ArrayList<>(codes.length);
        for (String code : codes)
            answers.add(ThreadLocalStateHolder.runWithState(capturedState,
                () -> AuthorizationServerService.isAuthorized(new OperationRequest(code, context)).otherwise(false)));
        return Future.all(new ArrayList<>(answers))
            .map(composite -> {
                for (int i = 0; i < answers.size(); i++)
                    if (Boolean.TRUE.equals(composite.resultAt(i)))
                        return true;
                return false;
            })
            .otherwise(false);
    }

    /**
     * The question put to the rule registry: an operation code, in the context of the row being written.
     *
     * <p>Only {@link HasOperationCode} requests match operation rules. The context is what lets a role's grant
     * answer at all: a role reaches a person through an access row naming an organization (and sometimes an
     * event), so every role-derived grant sits under {@code context:organizationId=…}. The registry applies a
     * section only when the request's context matches it, and judges a request that reports none in no context
     * — where only unscoped grants (a super administrator's) apply. Until 2026-10-18 this record carried no
     * context, so the rule asked "held with no context" and no role grant could ever satisfy it.
     */
    record OperationRequest(String operationCode, Map<String, String> authorizationContext)
        implements HasOperationCode, HasAuthorizationContext {
        @Override
        public Object getOperationCode() {
            return operationCode;
        }

        @Override
        public Map<String, String> getAuthorizationContext() {
            return authorizationContext;
        }
    }

    // ── Where a protected row lives ─────────────────────────────────────────────────────────────────

    /** The context keys the grant push writes ({@code context:organizationId=…,eventId=…}). */
    static final String ORGANIZATION_KEY = "organizationId", EVENT_KEY = "eventId";

    /** No context: only unscoped grants apply — a super administrator's {@code operation:*}, nobody's role. */
    static final Map<String, String> NO_CONTEXT = Map.of();

    /**
     * The fields that say which organization (and event) owns a row, per entity scoped this way.
     *
     * <p>{@code Organization} is not listed because its owner is itself: the target id IS the organization.
     * The eight grant tables are not listed either and keep being asked in no context: they are walled to
     * super administrators by {@link GrantTableWritePolicy} whatever this rule says, so a context would change
     * nothing but the number of lookups.
     */
    static final Map<String, String[]> OWNER_FIELDS = Map.of(
        "Letter",       new String[] { "organization", "event" },
        "PassTemplate", new String[] { "organization" },
        // Defensive, not reached by clients: ClientWriteSecrets denies money_account to them outright, so this
        // entry is only ever consulted for server writes, which pass whatever it says. It is here so the
        // entity is placed correctly if that deny is ever narrowed.
        "MoneyAccount", new String[] { "organization", "event" },
        // A booking: document.organization is NOT NULL, and event names the event it books (2026-10-19).
        "Document", new String[] { "organization", "event" }
    );

    /**
     * Entities owned THROUGH a parent row: entity -> { the field naming the parent, the parent's entity }. Such
     * a row lives wherever its parent lives — a booking's line where its booking does, an attendance where its
     * line does — so its context is the parent's, resolved row by row up to an entity in {@link #OWNER_FIELDS}.
     *
     * <p>Resolved by following the parent's id rather than by a dotted DQL path, so that a write MOVING a row to
     * another parent (writing {@code document} on a line) is placed exactly as a letter moved to another event
     * is: judged where it is and where it lands.
     */
    static final Map<String, String[]> OWNER_VIA = Map.of(
        "DocumentLine", new String[] { "document", "Document" },
        "Attendance",   new String[] { "documentLine", "DocumentLine" }
    );

    /** A row's owner as stored: ids, either of which may be null. */
    record Owner(Object organizationId, Object eventId) {}

    /** The two reads the context needs. An interface so the check can answer them without a database. */
    interface OwnerLookup {
        /** The stored owner of this row, or a future of null when there is no such row. */
        Future<Owner> rowOwner(String entityName, String[] ownerFields, Object id);

        /** The organization an event belongs to, or a future of null when there is no such event. */
        Future<Object> eventOrganization(Object eventId);

        /** The id of the row's parent named by {@code parentField}, or a future of null when there is none. */
        Future<Object> parentId(String entityName, String parentField, Object id);
    }

    /** The production lookup: two indexed reads by primary key, made as the server. */
    static final OwnerLookup DATABASE_LOOKUP = new OwnerLookup() {
        @Override
        public Future<Owner> rowOwner(String entityName, String[] ownerFields, Object id) {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
                .<Entity>executeQuery("select " + String.join(",", ownerFields) + " from " + entityName + " where id=$1", id)
                .map(rows -> {
                    if (rows.isEmpty())
                        return null;
                    Entity row = rows.get(0);
                    return new Owner(Entities.getPrimaryKey(row.getForeignEntityId(ownerFields[0])),
                        ownerFields.length > 1 ? Entities.getPrimaryKey(row.getForeignEntityId(ownerFields[1])) : null);
                });
        }

        @Override
        public Future<Object> eventOrganization(Object eventId) {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
                .<Entity>executeQuery("select organization from Event where id=$1", eventId)
                .map(rows -> rows.isEmpty() ? null : Entities.getPrimaryKey(rows.get(0).getForeignEntityId("organization")));
        }

        @Override
        public Future<Object> parentId(String entityName, String parentField, Object id) {
            return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
                .<Entity>executeQuery("select " + parentField + " from " + entityName + " where id=$1", id)
                .map(rows -> rows.isEmpty() ? null : Entities.getPrimaryKey(rows.get(0).getForeignEntityId(parentField)));
        }
    };

    /**
     * Every context this write must be authorized in — ALL of them, never any.
     *
     * <p><b>An update or delete is judged where the row lives now</b>, read from the database by its target id:
     * the client's payload never says where a row is, because a caller granted in organization A would simply
     * claim A while writing a row of B. A target that cannot be read (no {@code id = …} the parser can find, or
     * no such row) has no owner and is judged in no context — exactly what it was judged in before this existed,
     * so no write that passed before is refused by a second route.
     *
     * <p><b>A write that sets an owner field is ALSO judged where the row will live.</b> Otherwise a holder in A
     * could move a letter into B's event by updating its {@code event}. For an insert that is the only context,
     * there being no row yet. Owner fields not written keep the row's stored value.
     *
     * <p><b>An event decides the organization.</b> When the row names an event, its context is the EVENT's
     * organization plus the event, read from the database: for {@code Letter} that is what the
     * {@code letter_organization_consistency} trigger will store whatever the client sent, and for every entity
     * it stops an organization field from vouching for an event elsewhere. A written organization that differs
     * from the event's is required as well, so an inconsistent pair is judged on both counts.
     *
     * <p><b>Where nothing names an organization, there is no context</b> and only unscoped grants apply. For a
     * letter that is the {@code eventTypeGlobal} scope — a letter for every organization's events of a type,
     * which no organization owns — and for {@code Organization} an insert, i.e. creating an organization. Both
     * are a super administrator's acts, so refusing them to role holders is the right answer, not a lost create.
     *
     * <p>Event-scoped grants follow from the keys: a row with an event carries {@code eventId}, which an
     * event-scoped section needs and an organization-wide one ignores; a row with no event carries none, and
     * an event-scoped section never matches it — the same direction as the client's rule that no event
     * selected does not satisfy an event-scoped section.
     */
    static Future<List<Map<String, String>>> authorizationContexts(ProtectedEntityWriteRegistry.WriteRequest request,
                                                                   OwnerLookup lookup) {
        String entityName = request.entityName();
        if ("Organization".equals(entityName))
            return Future.succeededFuture(List.of(request.targetId() == null ? NO_CONTEXT
                : Map.of(ORGANIZATION_KEY, String.valueOf(request.targetId()))));
        String[] via = OWNER_VIA.get(entityName);
        if (via != null)
            return parentContexts(request, via, lookup);
        String[] ownerFields = OWNER_FIELDS.get(entityName);
        if (ownerFields == null)
            return Future.succeededFuture(List.of(NO_CONTEXT));
        boolean insert = request.verb() == ProtectedEntityWriteRegistry.WriteVerb.INSERT;
        Future<Owner> stored = insert || request.targetId() == null ? Future.succeededFuture(null)
            : asServer(() -> lookup.rowOwner(entityName, ownerFields, primaryKeyOf(request.targetId())));
        return stored.compose(current -> {
            List<Future<List<Map<String, String>>>> parts = new ArrayList<>();
            if (!insert)
                parts.add(contextsOf(current, lookup));
            boolean movesOwner = false;
            for (String field : ownerFields)
                movesOwner |= request.writtenValues().containsKey(field);
            if (insert || movesOwner) {
                Object organization = ownerAfterWrite(request, ownerFields[0], current == null ? null : current.organizationId());
                Object event = ownerFields.length < 2 ? null
                    : ownerAfterWrite(request, ownerFields[1], current == null ? null : current.eventId());
                parts.add(contextsOf(new Owner(organization, event), lookup));
            }
            return union(parts);
        });
    }

    /**
     * The contexts of a write to an entity owned through a parent ({@link #OWNER_VIA}): where its stored parent
     * lives, and — for an insert, or a write that sets the parent field — where the new parent lives. The same
     * two questions {@link #authorizationContexts} asks of an entity's own owner fields, one hop up.
     */
    private static Future<List<Map<String, String>>> parentContexts(ProtectedEntityWriteRegistry.WriteRequest request,
                                                                    String[] via, OwnerLookup lookup) {
        String parentField = via[0], parentEntity = via[1];
        boolean insert = request.verb() == ProtectedEntityWriteRegistry.WriteVerb.INSERT;
        Future<Object> storedParent = insert || request.targetId() == null ? Future.succeededFuture(null)
            : asServer(() -> lookup.parentId(request.entityName(), parentField, primaryKeyOf(request.targetId())));
        return storedParent.compose(stored -> {
            List<Future<List<Map<String, String>>>> parts = new ArrayList<>();
            if (!insert)
                parts.add(contextsOfRow(parentEntity, stored, lookup, request, MAX_BATCH_HOPS));
            if (insert || request.writtenValues().containsKey(parentField))
                parts.add(contextsOfRow(parentEntity, ownerAfterWrite(request, parentField, stored), lookup,
                    request, MAX_BATCH_HOPS));
            return union(parts);
        });
    }

    /**
     * How far a chain of same-batch references is followed. A booking needs two hops (an attendance to its line,
     * the line to its document); the bound is what stops a batch that references itself in a circle, and the
     * batch is the caller's to compose. Beyond it the row has no context, as an unresolvable one always has.
     */
    private static final int MAX_BATCH_HOPS = 8;

    /**
     * The contexts a row lives in, given its entity and id: through its own owner fields, or through its
     * parent's, hop by hop. Anything that cannot be named — no id, an unreadable value, a row that does not
     * exist — has no context, as everywhere else.
     *
     * <p><b>A row this batch is creating is named, though not by an id.</b> It used to fall in with the
     * unnameable, and that was the single largest hole in the census: a booking is written as one batch, so its
     * line and attendances reference a document with no id yet and were judged in NO context, where no
     * organization-scoped grant can answer. Production, 2026-10-23: 23,380 of 24,048 would-be refusals were
     * exactly those rows — every line and attendance of 668 bookings — while the 668 documents beside them,
     * carrying their own event, placed correctly. So a {@link GeneratedKeyReference} is now followed to the
     * statement it names, and that statement's OWN written values place the row, recursively.
     *
     * <p>This widens no grant. The referenced statement is inspected the same way, it must insert the entity the
     * reference is being read as, and the context it yields is the context its own fields name — a caller
     * composing a batch cannot invent a context it could not have written directly into the parent row.
     */
    private static Future<List<Map<String, String>>> contextsOfRow(String entityName, Object id, OwnerLookup lookup,
                                                                   ProtectedEntityWriteRegistry.WriteRequest batch,
                                                                   int hopsLeft) {
        if (id instanceof GeneratedKeyReference reference)
            return contextsOfSameBatchRow(entityName, reference, lookup, batch, hopsLeft);
        String key = idOf(id);
        if (key == null)
            return Future.succeededFuture(List.of(NO_CONTEXT));
        String[] via = OWNER_VIA.get(entityName);
        if (via != null)
            return asServer(() -> lookup.parentId(entityName, via[0], primaryKeyOf(key)))
                .compose(parent -> contextsOfRow(via[1], parent, lookup, batch, hopsLeft));
        String[] ownerFields = OWNER_FIELDS.get(entityName);
        if (ownerFields == null)
            return Future.succeededFuture(List.of(NO_CONTEXT));
        return asServer(() -> lookup.rowOwner(entityName, ownerFields, primaryKeyOf(key)))
            .compose(owner -> contextsOf(owner, lookup));
    }

    /**
     * The contexts of a row another statement of this batch is creating, read from THAT statement's written
     * values — the same two questions {@link #authorizationContexts} asks of a stored row, asked of a row that
     * does not exist yet.
     *
     * <p>Four ways this yields no context, each of them the safe answer: the write was judged outside a batch
     * (nothing to point at), the index names no inserting statement, the statement inserts a DIFFERENT entity
     * from the one the reference is being read as (the caller composes the batch, so the target is theirs to
     * choose and must be checked rather than assumed), or the chain is longer than {@link #MAX_BATCH_HOPS}.
     */
    private static Future<List<Map<String, String>>> contextsOfSameBatchRow(String entityName,
                                                                            GeneratedKeyReference reference,
                                                                            OwnerLookup lookup,
                                                                            ProtectedEntityWriteRegistry.WriteRequest batch,
                                                                            int hopsLeft) {
        ProtectedEntityWriteRegistry.WriteRequest insert = batch == null ? null
            : batch.batchWriteAt(reference.getStatementBatchIndex());
        if (hopsLeft <= 0 || insert == null || !entityName.equals(insert.entityName()))
            return Future.succeededFuture(List.of(NO_CONTEXT));
        String[] via = OWNER_VIA.get(entityName);
        if (via != null) // its parent is created here too, most likely: a line's document, an attendance's line
            return contextsOfRow(via[1], insert.writtenValues().get(via[0]), lookup, batch, hopsLeft - 1);
        String[] ownerFields = OWNER_FIELDS.get(entityName);
        if (ownerFields == null)
            return Future.succeededFuture(List.of(NO_CONTEXT));
        // Exactly what an insert of this entity would be judged by on its own: contextsOf refuses to place an
        // owner it cannot name, so an owner that is itself a reference or an expression still yields no context.
        return contextsOf(new Owner(insert.writtenValues().get(ownerFields[0]),
            ownerFields.length > 1 ? insert.writtenValues().get(ownerFields[1]) : null), lookup);
    }

    /** Every context any part asks for, each once, in order. */
    private static Future<List<Map<String, String>>> union(List<Future<List<Map<String, String>>>> parts) {
        return Future.all(new ArrayList<>(parts)).map(all -> {
            java.util.LinkedHashSet<Map<String, String>> contexts = new java.util.LinkedHashSet<>();
            for (int i = 0; i < parts.size(); i++)
                contexts.addAll(all.<List<Map<String, String>>>resultAt(i));
            return new ArrayList<>(contexts);
        });
    }

    /**
     * Runs a lookup as the SERVER, not as the caller whose write is being judged.
     *
     * <p>The first lookup leaves on the caller's thread, before any async hop, so the caller's state — client
     * origin included — would still be on it. The read inventory records every query made under a client-origin
     * state as a client read, and its output is the evidence the read rule is written from: these two
     * statements, which no client ever sent, would be recorded among them and each compiled a second time by the
     * client read guard. An empty state is what the server's own reads run with (as ServerWrite.asServer).
     */
    static <T> T asServer(java.util.function.Supplier<T> lookup) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), lookup);
    }

    /** The owner field's value once the write is applied: the written value when written, else the stored one. */
    private static Object ownerAfterWrite(ProtectedEntityWriteRegistry.WriteRequest request, String field, Object stored) {
        return request.writtenValues().containsKey(field) ? request.writtenValues().get(field) : stored;
    }

    /** The contexts one owner is judged in (see {@link #authorizationContexts}). */
    private static Future<List<Map<String, String>>> contextsOf(Owner owner, OwnerLookup lookup) {
        if (owner == null)
            return Future.succeededFuture(List.of(NO_CONTEXT));
        String organization = idOf(owner.organizationId());
        String event = idOf(owner.eventId());
        // An owner we cannot name — written as an expression (UNRESOLVED_VALUE), or a row this batch creates —
        // cannot be placed. It must not read as "no event" or "no organization": that would let the caller pick
        // where the write is judged by choosing to write an expression.
        if (owner.eventId() != null && event == null || owner.organizationId() != null && organization == null)
            return Future.succeededFuture(List.of(NO_CONTEXT));
        if (event == null)
            return Future.succeededFuture(List.of(organization == null ? NO_CONTEXT : Map.of(ORGANIZATION_KEY, organization)));
        return asServer(() -> lookup.eventOrganization(primaryKeyOf(event))).map(eventOrganizationId -> {
            String eventOrganization = idOf(eventOrganizationId);
            if (eventOrganization == null) // no such event
                return List.of(NO_CONTEXT);
            Map<String, String> eventContext = Map.of(ORGANIZATION_KEY, eventOrganization, EVENT_KEY, event);
            return organization == null || organization.equals(eventOrganization) ? List.of(eventContext)
                : List.of(eventContext, Map.of(ORGANIZATION_KEY, organization));
        });
    }

    /**
     * An id as a query parameter: a number. The seam hands ids over as strings (targetIdOf and writtenValuesOf
     * turn every number into one), and the PostgreSQL driver refuses a string bound where the column is an
     * integer ("can not be coerced to the expected class = [java.lang.Number]"). That is how the first staging
     * run of this rule failed every owner lookup. A string that is not a number is passed on unchanged, and its
     * lookup fails — closed — rather than being guessed at.
     */
    static Object primaryKeyOf(Object id) {
        if (id instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException notAnInteger) {
                try {
                    return Long.valueOf(text.trim());
                } catch (NumberFormatException notANumber) {
                    return id;
                }
            }
        }
        return id;
    }

    /** An id as the grant push writes it, or null for anything that is not one (a same-batch reference, say). */
    private static String idOf(Object value) {
        if (value instanceof Number || value instanceof String)
            return String.valueOf(value);
        return null;
    }

    /** How a refusal line names where it was refused. */
    private static String describe(List<Map<String, String>> contexts) {
        List<String> parts = new ArrayList<>();
        for (Map<String, String> context : contexts)
            parts.add(context.isEmpty() ? "no organization"
                : "organization " + context.get(ORGANIZATION_KEY)
                  + (context.containsKey(EVENT_KEY) ? " event " + context.get(EVENT_KEY) : ""));
        return String.join(" and ", parts);
    }
}
