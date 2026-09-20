// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * Defines the authentication data (ModalityUserPrincipal) sent by the server to the client over the network.
 */
module modality.crm.shared.authn {

    // Direct dependencies modules
    requires webfx.platform.ast;
    requires webfx.platform.util;
    requires webfx.stack.com.serial;

    // Exported packages
    exports one.modality.crm.shared.services.authn;
    exports one.modality.crm.shared.services.authn.serial;

    // Provided services
    provides dev.webfx.stack.com.serial.spi.SerialCodec with one.modality.crm.shared.services.authn.serial.ModalityUserPrincipalSerialCodec, one.modality.crm.shared.services.authn.serial.ModalityGuestPrincipalSerialCodec, one.modality.crm.shared.services.authn.serial.AuthenticateWithCartCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.SendBookingAccessEmailCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RequestSupportViewCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.AuthenticateWithSupportViewCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RequestBackOfficeViewCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.AuthenticateWithBackOfficeViewCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.StartPasskeyRegistrationCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.FinalisePasskeyRegistrationCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.StartPasskeyAssertionCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.AuthenticateWithPasskeyCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ListPasskeysCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RemovePasskeyCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RenamePasskeyCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ListPendingPasskeysCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.PasswordSignInStatusCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ClosePasswordSignInCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ReopenPasswordSignInCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ApprovePasskeyCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RejectPasskeyCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.StartTotpEnrolmentCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ConfirmTotpEnrolmentCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ListSecondFactorsCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RemoveTotpCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RegenerateTotpBackupCodesCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.AuthenticateWithTotpCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.AuthenticateWithTotpBackupCodeCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.CancelSecondFactorCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ResetSecondFactorCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.RevokeApprovedPasskeyCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.LookupSecondFactorsCredentialsSerialCodec, one.modality.crm.shared.services.authn.serial.ListAccountPasskeysCredentialsSerialCodec;

}