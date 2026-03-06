package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityId;

import java.time.LocalDate;

/**
 * A date negotiation proposal between a coordinator and a volunteer.
 * Records proposed dates, the proposer, reason, status, and the action token
 * used for the volunteer's token-based email response.
 *
 * <p>Proposals form a versioned chain via {@link #parentProposal}.
 * When a new proposal is created the previous pending one is superseded.
 */
public interface VolunteeringDateProposal extends Entity {

    // --- Field name constants ---

    String application           = "application";
    String proposedStartDate     = "proposedStartDate";
    String proposedEndDate       = "proposedEndDate";
    String proposedStartTime     = "proposedStartTime";
    String proposedEndTime       = "proposedEndTime";
    String proposedBy            = "proposedBy";
    String reason                = "reason";
    String status                = "status";
    String responseDate          = "responseDate";
    String responseNotes         = "responseNotes";
    String version               = "version";
    String parentProposal        = "parentProposal";
    String actionToken           = "actionToken";
    String actionTokenExpiresAt  = "actionTokenExpiresAt";
    String actionTokenUsedAt     = "actionTokenUsedAt";

    // --- Application FK ---

    default void setApplication(Object value) { setForeignField(application, value); }
    default EntityId getApplicationId() { return getForeignEntityId(application); }
    default VolunteeringApplication getApplication() { return getForeignEntity(application); }

    // --- Proposed dates ---

    default void setProposedStartDate(LocalDate value) { setFieldValue(proposedStartDate, value); }
    default LocalDate getProposedStartDate() { return getLocalDateFieldValue(proposedStartDate); }

    default void setProposedEndDate(LocalDate value) { setFieldValue(proposedEndDate, value); }
    default LocalDate getProposedEndDate() { return getLocalDateFieldValue(proposedEndDate); }

    default void setProposedStartTime(String value) { setFieldValue(proposedStartTime, value); }
    default String getProposedStartTime() { return getStringFieldValue(proposedStartTime); }

    default void setProposedEndTime(String value) { setFieldValue(proposedEndTime, value); }
    default String getProposedEndTime() { return getStringFieldValue(proposedEndTime); }

    // --- Metadata ---

    default void setProposedBy(String value) { setFieldValue(proposedBy, value); }
    default String getProposedBy() { return getStringFieldValue(proposedBy); }

    default void setReason(String value) { setFieldValue(reason, value); }
    default String getReason() { return getStringFieldValue(reason); }

    /** One of: pending | accepted | rejected | superseded */
    default void setStatus(String value) { setFieldValue(status, value); }
    default String getStatus() { return getStringFieldValue(status); }

    default void setResponseDate(LocalDate value) { setFieldValue(responseDate, value); }
    default LocalDate getResponseDate() { return getLocalDateFieldValue(responseDate); }

    default void setResponseNotes(String value) { setFieldValue(responseNotes, value); }
    default String getResponseNotes() { return getStringFieldValue(responseNotes); }

    default void setVersion(Integer value) { setFieldValue(version, value); }
    default Integer getVersion() { return (Integer) getFieldValue(version); }

    // --- Parent proposal (chain) ---

    default void setParentProposal(Object value) { setForeignField(parentProposal, value); }
    default EntityId getParentProposalId() { return getForeignEntityId(parentProposal); }
    default VolunteeringDateProposal getParentProposal() { return getForeignEntity(parentProposal); }

    // --- Action token (for volunteer email response) ---

    default void setActionToken(String value) { setFieldValue(actionToken, value); }
    default String getActionToken() { return getStringFieldValue(actionToken); }

    default void setActionTokenExpiresAt(LocalDate value) { setFieldValue(actionTokenExpiresAt, value); }
    default LocalDate getActionTokenExpiresAt() { return getLocalDateFieldValue(actionTokenExpiresAt); }

    default void setActionTokenUsedAt(LocalDate value) { setFieldValue(actionTokenUsedAt, value); }
    default LocalDate getActionTokenUsedAt() { return getLocalDateFieldValue(actionTokenUsedAt); }
}
