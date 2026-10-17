package net.resheim.timekeeper.server;

import java.io.IOException;
import java.util.*;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.*;

/** Protocol verification only. Shared ceremony/session/revocation authority belongs to the caller. */
final class WebAuthnProtocol {
    static final class Rejected extends RuntimeException {
        Rejected() {super("Passkey response rejected");}
    }
    record Assertion(AssertionResult result,long nextSignatureCount) { }
    private final String rpId;
    private final RelyingParty relyingParty;
    WebAuthnProtocol(String rpId,String origin,CredentialRepository repository) {
        this.rpId=Objects.requireNonNull(rpId);
        relyingParty=RelyingParty.builder().identity(RelyingPartyIdentity.builder().id(rpId).name("Timekeeper").build())
                .credentialRepository(repository).origins(Set.of(origin)).allowOriginPort(false).allowOriginSubdomain(false)
                .attestationConveyancePreference(AttestationConveyancePreference.NONE).allowUntrustedAttestation(true)
                .preferredPubkeyParams(List.of(PublicKeyCredentialParameters.ES256,PublicKeyCredentialParameters.RS256,PublicKeyCredentialParameters.EdDSA))
                // Synced passkeys can use zero/nonmonotonic counters; enforce the explicit policy below.
                .validateSignatureCounter(false).build();
    }
    PublicKeyCredentialCreationOptions registration(UserIdentity user) {
        return relyingParty.startRegistration(StartRegistrationOptions.builder().user(user)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder().residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED).build()).timeout(120000).build());
    }
    AssertionRequest assertion(Optional<String> username) {
        return relyingParty.startAssertion(StartAssertionOptions.builder().username(username)
                .userVerification(UserVerificationRequirement.REQUIRED).timeout(120000).build());
    }
    RegistrationResult register(PublicKeyCredentialCreationOptions request,String response) {
        if(!rpId.equals(request.getRp().getId()) || response==null || response.length()>98304) throw new Rejected();
        try {
            var credential=PublicKeyCredential.parseRegistrationResponseJson(response);
            sameOriginClient(credential.getResponse().getClientDataJSON());
            var result=relyingParty.finishRegistration(FinishRegistrationOptions.builder().request(request).response(credential).build());
            if(!result.isUserVerified() || result.isDiscoverable().equals(Optional.of(false))) throw new Rejected();
            return result;
        } catch(IOException | RegistrationFailedException | IllegalArgumentException failure) {throw new Rejected();}
    }
    Assertion authenticate(AssertionRequest request,String response) {
        if(!rpId.equals(request.getPublicKeyCredentialRequestOptions().getRpId()) || response==null || response.length()>98304) throw new Rejected();
        try {
            var credential=PublicKeyCredential.parseAssertionResponseJson(response);
            sameOriginClient(credential.getResponse().getClientDataJSON());
            var result=relyingParty.finishAssertion(FinishAssertionOptions.builder().request(request).response(credential).build());
            if(!result.isSuccess() || !result.isUserVerified()) throw new Rejected();
            var stored=result.getCredential();
            if(stored.isBackupEligible().isEmpty() || stored.isBackupEligible().get()!=result.isBackupEligible()
                    || result.isBackedUp() && !result.isBackupEligible()) throw new Rejected();
            long previous=stored.getSignatureCount(),next=result.getSignatureCount();
            if(previous<0 || previous>0xffffffffL || next<0 || next>0xffffffffL
                    || !result.isBackupEligible() && (previous!=0 || next!=0) && next<=previous) throw new Rejected();
            return new Assertion(result,Math.max(previous,next));
        } catch(IOException | AssertionFailedException | IllegalArgumentException failure) {throw new Rejected();}
    }
    private static void sameOriginClient(ByteArray bytes) throws IOException {
        if(bytes.size()>4096) throw new Rejected();
        // This application supports top-level, same-origin ceremonies only. The
        // library verifies the signed client data; its current model omits these
        // newer embedding fields, so enforce the application's policy explicitly.
        var value=JsonValues.MAPPER.readTree(bytes.getBytes());
        if(value==null || !value.isObject() || value.has("topOrigin")
                || value.has("crossOrigin") && (!value.path("crossOrigin").isBoolean() || value.path("crossOrigin").asBoolean())) throw new Rejected();
    }
}
