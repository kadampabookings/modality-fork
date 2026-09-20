package one.modality.crm.server.person;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every column in the database that names a person, and what a merge does with it.
 *
 * <p>This list is the whole of the merge's correctness, so it is here rather than in the browser — the
 * screen used to send one {@code update <entity> set <field>} per entry of a list it carried, which meant
 * the set of tables a client could rewrite was whatever that client said it was.
 *
 * <p><b>A foreign key is not a backstop, and on this table that is not theoretical.</b> Of the 36 keys
 * pointing at {@code person}, two are {@code ON DELETE SET NULL} — {@code document.person_id} and
 * {@code recipient.person_id} — so a person this list forgot would not make the delete fail. It would
 * quietly erase whose booking it was. Two more are {@code ON DELETE CASCADE}. The remaining thirty-two
 * refuse, which is why a forgotten one shows up as a failed merge rather than as damage; but the two that
 * do not refuse are the reason {@link #UNKNOWN_REFERENCE_KEY} exists and the merge asks the catalogue
 * every time rather than trusting this list to have stayed complete.
 *
 * @author Claude Code
 */
final class PersonReferences {

    private PersonReferences() {
    }

    /**
     * Columns whose rows follow the surviving person.
     *
     * <p>Keyed {@code table.column}, exactly as the catalogue spells them, so the completeness check can
     * compare the two without a translation step in between.
     */
    static final Map<String, String> REPOINTED = repointed();

    private static Map<String, String> repointed() {
        Map<String, String> m = new LinkedHashMap<>();
        // The booking and its audit trail — the identity a member reads on their own orders page.
        m.put("document", "person_id");
        m.put("document.carer1", "person_carer1_id");
        m.put("document.carer2", "person_carer2_id");
        m.put("history", "user_person_id");
        m.put("error", "user_person_id");
        m.put("recipient", "person_id"); // ON DELETE SET NULL — forgetting this one erases it silently
        // Who somebody is to an account, and who cares for them.
        m.put("person.accountPerson", "account_person_id");
        m.put("person.carer1", "carer1_id");
        m.put("person.carer2", "carer2_id");
        m.put("invitation.inviter", "inviter_id");
        m.put("invitation.invitee", "invitee_id");
        m.put("invitation.createdAlias", "created_alias_person_id");
        // Support, chat and the screens staff work in.
        m.put("chat_message", "person_id");
        m.put("conversation.assignee", "assignee_id");
        m.put("conversation.viewer", "viewer_person_id");
        m.put("activity_state", "owner_id");
        m.put("list", "created_by_id");
        m.put("list_item", "person_id");
        m.put("pass_template", "created_by");
        // Roles a person plays.
        m.put("driver", "person_id");
        m.put("teacher", "person_id");
        m.put("subscription", "person_id");
        m.put("role_attribution", "person_id");
        m.put("volunteering_application", "person_id");
        m.put("session_user", "user_id");
        return m;
    }

    /**
     * Columns repointed only where the surviving person has no row of its own, the rest being dropped.
     *
     * <p>Each of these carries a unique key that includes the person — one presence per person and agent,
     * one reaction per person, message and emoji, one participant per conversation. A plain repoint of a
     * duplicate who shares any of them with the kept person violates that key and rolls the whole merge
     * back. Nothing is lost by dropping them: two presences for one human, or the same emoji twice from
     * the same human, are the duplication being merged away.
     */
    static final Map<String, String[]> DEDUPLICATED = Map.of(
        "chat_presence", new String[] { "person_id", "agent, event_id" },
        "chat_message_reaction", new String[] { "person_id", "message_id, emoji" },
        "conversation_participant", new String[] { "person_id", "conversation_id" },
        // Two PARTIAL unique indexes, both on (person_id, scheduled_item_id, issue_id) and split by
        // self_resolved, so the same person cannot hold two open reports of one issue on one session.
        // Keyed on self_resolved too, to stay on the side of the split the indexes are drawn along.
        "support_report", new String[] { "person_id", "scheduled_item_id, issue_id, self_resolved" }
    );

    /**
     * Tables the database empties itself when the person goes, listed so the catalogue check knows they
     * were considered rather than missed. A duplicate's sessions and push registrations have no meaning
     * once the row naming them is gone.
     */
    static final Map<String, String> DATABASE_CASCADES = Map.of(
        "auth_session", "person_id",
        "push_subscription_recipient", "person_id"
    );

    /**
     * Rows whose presence refuses the merge rather than following it.
     *
     * <p>These say the duplicate holds PRIVILEGE. Repointing them would hand the surviving person whatever
     * the duplicate could do, which is an escalation wearing a data-tidying hat; dropping them would revoke
     * a real administrator without saying so. Either way it is a decision for whoever is merging, made
     * explicitly, so the merge stops and names what it found.
     */
    static final Map<String, String> REFUSING = Map.of(
        "authorization_super_admin", "super_admin_id",
        "authorization_organization_admin", "admin_id",
        "authorization_organization_user_access", "user_id",
        "authorization_management.manager", "manager_id",
        "authorization_management.user", "user_id"
    );

    /**
     * The audit trail of person changes, which has no foreign key at all.
     *
     * <p>Repointed for the same reason {@code history.user_person_id} is — the record follows the surviving
     * identity — but invisible to the catalogue check below, so it is named here or it is named nowhere.
     */
    static final Map<String, String[]> UNCONSTRAINED_AUDIT = Map.of(
        "person_account_move", new String[] { "person_id", "changed_by_person_id" },
        "person_link_change", new String[] { "person_id", "old_account_person_id", "new_account_person_id", "changed_by_person_id" }
    );

    /** Told to the caller when the catalogue holds a reference this file does not. */
    static final String UNKNOWN_REFERENCE_KEY = "PersonMergeUnknownReferenceError";

    /**
     * Every {@code table.column} this file accounts for, in the catalogue's own spelling.
     *
     * <p>Table names are taken from the map keys, which carry a suffix where one table is named twice
     * ({@code person.carer1}); everything after the first dot is a label for a reader, never a column.
     */
    static Set<String> accountedFor() {
        Set<String> names = new java.util.LinkedHashSet<>();
        REPOINTED.forEach((key, column) -> names.add(table(key) + "." + column));
        DEDUPLICATED.forEach((table, spec) -> names.add(table + "." + spec[0]));
        DATABASE_CASCADES.forEach((table, column) -> names.add(table + "." + column));
        REFUSING.forEach((key, column) -> names.add(table(key) + "." + column));
        return names;
    }

    /** The table a key names, dropping the disambiguating suffix a second column on the same table needs. */
    static String table(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(0, dot);
    }

    /** Strips the quoting the catalogue adds around a reserved word, so {@code "session_user"} compares. */
    static String unquote(String catalogueName) {
        return catalogueName == null ? null : catalogueName.replace("\"", "");
    }
}
