// File managed by WebFX (DO NOT EDIT MANUALLY)
package one.modality.crm.shared.services.authn;

public interface ModalityAuthenticationI18nKeys {

    Object AuthnBackofficeMethodNotAllowedError = "AuthnBackofficeMethodNotAllowedError";
    Object AuthnEmailSignInClosedError = "AuthnEmailSignInClosedError";
    Object AuthnLastPasskeyWhilePasswordClosedError = "AuthnLastPasskeyWhilePasswordClosedError";
    Object AuthnNewPasswordTooShortError = "AuthnNewPasswordTooShortError";
    Object AuthnNoSuchUserAccountError = "AuthnNoSuchUserAccountError";
    Object AuthnNoUsablePasskeyError = "AuthnNoUsablePasskeyError";
    Object AuthnOldPasswordNotMatchingError = "AuthnOldPasswordNotMatchingError";
    Object AuthnPasskeyAdminNotPermittedError = "AuthnPasskeyAdminNotPermittedError";
    Object AuthnPasskeyError = "AuthnPasskeyError";
    Object AuthnPasskeyManagementError = "AuthnPasskeyManagementError";
    /**
     * The caller is signed in but this server never verified the session — no identity token, or one past
     * its signed expiry, which pre-flip is tolerated rather than treated as a logout. Distinct from
     * {@link #AuthnPasskeyManagementError} on purpose: the cure is to sign in again, and a caller told only
     * that "the change could not be completed" has no reason to guess that.
     */
    Object AuthnSessionNotVerifiedError = "AuthnSessionNotVerifiedError";
    Object AuthnPasskeyNotApprovedError = "AuthnPasskeyNotApprovedError";
    Object AuthnPasskeyNotConfiguredError = "AuthnPasskeyNotConfiguredError";
    Object AuthnPasskeyRegistrationError = "AuthnPasskeyRegistrationError";
    Object AuthnPasswordSignInClosedError = "AuthnPasswordSignInClosedError";
    Object AuthnSecondFactorAttemptsExceededError = "AuthnSecondFactorAttemptsExceededError";
    Object AuthnSecondFactorCodeError = "AuthnSecondFactorCodeError";
    Object AuthnSecondFactorNotEnrolledError = "AuthnSecondFactorNotEnrolledError";
    Object AuthnSecondFactorUnavailableError = "AuthnSecondFactorUnavailableError";
    Object AuthnTotpEnrolmentError = "AuthnTotpEnrolmentError";
    Object AuthnUnrecognizedUserIdError = "AuthnUnrecognizedUserIdError";
    Object AuthnUserOrPasswordEmptyError = "AuthnUserOrPasswordEmptyError";
    Object AuthnWrongUserOrPasswordError = "AuthnWrongUserOrPasswordError";
    Object CreateAccountAlreadyExistsError = "CreateAccountAlreadyExistsError";
    Object LoginLinkAlreadyUsedError = "LoginLinkAlreadyUsedError";
    Object LoginLinkExpiredError = "LoginLinkExpiredError";
    Object LoginLinkUnrecognisedError = "LoginLinkUnrecognisedError";
    Object MagicLinkBusClosedError = "MagicLinkBusClosedError";
    Object MagicLinkPushError = "MagicLinkPushError";
    Object MagicLinkUnexpectedError = "MagicLinkUnexpectedError";
    Object Please = "Please";
    Object SupportViewInvalidTargetError = "SupportViewInvalidTargetError";
    Object SupportViewLinkInvalidError = "SupportViewLinkInvalidError";
    Object SupportViewNotPermittedError = "SupportViewNotPermittedError";
    Object VerificationCodeAttemptsExceededError = "VerificationCodeAttemptsExceededError";

}