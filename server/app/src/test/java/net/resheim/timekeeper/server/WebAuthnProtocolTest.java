package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import com.upokecenter.cbor.CBORObject;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import org.junit.jupiter.api.Test;

/** Real generated P-256 keys and signed WebAuthn messages, not mocked library success. */
class WebAuthnProtocolTest {
    private static final String RP="timekeeper.example",ORIGIN="https://timekeeper.example:8443";
    private static ByteArray bytes(String value) throws Exception {return new ByteArray(sha(value.getBytes(StandardCharsets.UTF_8)));}
    private static byte[] sha(byte[] value) throws Exception {return MessageDigest.getInstance("SHA-256").digest(value);}
    private static byte[] coordinate(BigInteger value) {
        byte[] source=value.toByteArray(),result=new byte[32];int length=Math.min(source.length,32);
        System.arraycopy(source,source.length-length,result,32-length,length);return result;
    }
    private static final class Repository implements CredentialRepository {
        final ByteArray handle;final Map<ByteArray,RegisteredCredential> values=new HashMap<>();String username="alice";
        Repository() throws Exception {handle=bytes("opaque random fixture handle");}
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String name) {
            if(!username.equals(name)) return Set.of();var ids=new HashSet<PublicKeyCredentialDescriptor>();
            values.keySet().forEach(id->ids.add(PublicKeyCredentialDescriptor.builder().id(id).build()));return Set.copyOf(ids);
        }
        public Optional<ByteArray> getUserHandleForUsername(String name) {return username.equals(name) ? Optional.of(handle) : Optional.empty();}
        public Optional<String> getUsernameForUserHandle(ByteArray value) {return handle.equals(value) ? Optional.of(username) : Optional.empty();}
        public Optional<RegisteredCredential> lookup(ByteArray id,ByteArray owner) {return Optional.ofNullable(values.get(id)).filter(value->value.getUserHandle().equals(owner));}
        public Set<RegisteredCredential> lookupAll(ByteArray id) {return values.containsKey(id) ? Set.of(values.get(id)) : Set.of();}
    }
    private static final class Authenticator {
        final KeyPair key;final ByteArray id,cose;
        Authenticator(String label) throws Exception {
            var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));key=generator.generateKeyPair();id=bytes(label);
            var publicKey=(ECPublicKey)key.getPublic();
            cose=new ByteArray(CBORObject.NewMap().Add(1,2).Add(3,-7).Add(-1,1)
                    .Add(-2,coordinate(publicKey.getW().getAffineX())).Add(-3,coordinate(publicKey.getW().getAffineY())).EncodeToBytes());
        }
        private byte[] authenticatorData(String rp,int flags,long counter,boolean registration) throws Exception {
            var value=new ByteArrayOutputStream();value.writeBytes(sha(rp.getBytes(StandardCharsets.UTF_8)));value.write(flags);
            value.writeBytes(ByteBuffer.allocate(4).putInt((int)counter).array());
            if(registration) {value.writeBytes(new byte[16]);value.writeBytes(ByteBuffer.allocate(2).putShort((short)id.size()).array());value.writeBytes(id.getBytes());value.writeBytes(cose.getBytes());}
            return value.toByteArray();
        }
        private byte[] client(String type,ByteArray challenge,String origin) {
            return object().put("type",type).put("challenge",challenge.getBase64Url()).put("origin",origin).put("crossOrigin",false).toString().getBytes(StandardCharsets.UTF_8);
        }
        String registration(PublicKeyCredentialCreationOptions request,String rp,String origin,int flags) throws Exception {
            var attestation=CBORObject.NewMap().Add("fmt","none").Add("attStmt",CBORObject.NewMap())
                    .Add("authData",authenticatorData(rp,flags,0,true)).EncodeToBytes();
            var result=object().put("type","public-key").put("id",id.getBase64Url()).put("rawId",id.getBase64Url());
            result.putObject("response").put("clientDataJSON",new ByteArray(client("webauthn.create",request.getChallenge(),origin)).getBase64Url())
                    .put("attestationObject",new ByteArray(attestation).getBase64Url()).putArray("transports").add("internal");
            result.putObject("clientExtensionResults").putObject("credProps").put("rk",true);return result.toString();
        }
        String assertion(AssertionRequest request,String rp,String origin,int flags,long counter,ByteArray handle,boolean badSignature) throws Exception {
            byte[] data=authenticatorData(rp,flags,counter,false),client=client("webauthn.get",request.getPublicKeyCredentialRequestOptions().getChallenge(),origin);
            var signer=Signature.getInstance("SHA256withECDSA");signer.initSign(key.getPrivate());signer.update(data);signer.update(sha(client));var signature=signer.sign();
            if(badSignature) signature[signature.length-1]^=1;
            var result=object().put("type","public-key").put("id",id.getBase64Url()).put("rawId",id.getBase64Url());
            var response=result.putObject("response");response.put("authenticatorData",new ByteArray(data).getBase64Url());response.put("clientDataJSON",new ByteArray(client).getBase64Url());response.put("signature",new ByteArray(signature).getBase64Url());
            if(handle==null) response.putNull("userHandle");else response.put("userHandle",handle.getBase64Url());result.putObject("clientExtensionResults");return result.toString();
        }
        void store(Repository repo,boolean eligible,long count) {
            repo.values.put(id,RegisteredCredential.builder().credentialId(id).userHandle(repo.handle).publicKeyCose(cose)
                    .backupEligible(eligible).backupState(eligible).signatureCount(count).build());
        }
    }
    private UserIdentity user(Repository repo) {return UserIdentity.builder().name(repo.username).displayName("Alice").id(repo.handle).build();}
    @Test void requestsRequireDiscoverabilityAndVerificationAndRoundTripThroughSharedStorage() throws Exception {
        var repo=new Repository();var protocol=new WebAuthnProtocol(RP,ORIGIN,repo);var request=protocol.registration(user(repo));
        var wire=parse(request.toCredentialsCreateJson()).path("publicKey");assertEquals("required",wire.path("authenticatorSelection").path("residentKey").asText());
        assertEquals("required",wire.path("authenticatorSelection").path("userVerification").asText());assertEquals("none",wire.path("attestation").asText());
        assertEquals(request,PublicKeyCredentialCreationOptions.fromJson(request.toJson()));
        var assertion=protocol.assertion(Optional.empty());assertTrue(assertion.getUsername().isEmpty());
        var options=parse(assertion.toCredentialsGetJson()).path("publicKey");assertEquals("required",options.path("userVerification").asText());assertFalse(options.has("allowCredentials"));
        assertEquals(assertion,AssertionRequest.fromJson(assertion.toJson()));
    }
    @Test void realRegistrationAndDiscoverableSignInVerifyTheKeyAndStableHandle() throws Exception {
        var repo=new Repository();var protocol=new WebAuthnProtocol(RP,ORIGIN,repo);var authenticator=new Authenticator("first passkey");var registration=protocol.registration(user(repo));
        var result=protocol.register(PublicKeyCredentialCreationOptions.fromJson(registration.toJson()),authenticator.registration(registration,RP,ORIGIN,0x5d));
        assertEquals(authenticator.id,result.getKeyId().getId());assertEquals(authenticator.cose,result.getPublicKeyCose());assertTrue(result.isBackupEligible());assertTrue(result.isUserVerified());
        authenticator.store(repo,true,0);
        var request=AssertionRequest.fromJson(protocol.assertion(Optional.empty()).toJson());
        var accepted=protocol.authenticate(request,authenticator.assertion(request,RP,ORIGIN,0x1d,0,repo.handle,false));
        assertEquals(repo.handle,accepted.result().getUserHandle());assertEquals(0,accepted.nextSignatureCount());
        repo.username="renamed-alice";var renamed=protocol.assertion(Optional.empty());
        assertEquals("renamed-alice",protocol.authenticate(renamed,authenticator.assertion(renamed,RP,ORIGIN,0x1d,0,repo.handle,false)).result().getUsername());
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.register(registration,authenticator.registration(registration,RP,ORIGIN,0x5d)));
    }
    @Test void registrationRejectsWrongOriginPortSubdomainRpChallengeAndMissingVerification() throws Exception {
        var repo=new Repository();var protocol=new WebAuthnProtocol(RP,ORIGIN,repo);var key=new Authenticator("registration rejection");var request=protocol.registration(user(repo));
        for(String origin:List.of("https://timekeeper.example:9443","https://sub.timekeeper.example:8443","https://attacker.example"))
            assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.register(request,key.registration(request,RP,origin,0x45)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.register(request,key.registration(request,"wrong.example",ORIGIN,0x45)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.register(request,key.registration(request,RP,ORIGIN,0x41)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.register(request,key.registration(protocol.registration(user(repo)),RP,ORIGIN,0x45)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->new WebAuthnProtocol("wrong.example",ORIGIN,repo).register(request,key.registration(request,RP,ORIGIN,0x45)));
    }
    @Test void assertionRejectsBadSignaturesOriginsRpUserHandlesAndMissingVerification() throws Exception {
        var repo=new Repository();var protocol=new WebAuthnProtocol(RP,ORIGIN,repo);var key=new Authenticator("assertion rejection");key.store(repo,false,0);var request=protocol.assertion(Optional.empty());
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x05,1,repo.handle,true)));
        for(String origin:List.of("https://timekeeper.example:9443","https://sub.timekeeper.example:8443","https://attacker.example"))
            assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,RP,origin,0x05,1,repo.handle,false)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,"wrong.example",ORIGIN,0x05,1,repo.handle,false)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x01,1,repo.handle,false)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x05,1,bytes("foreign handle"),false)));
        assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x05,1,null,false)));
        var another=protocol.assertion(Optional.empty());assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(another,key.assertion(request,RP,ORIGIN,0x05,1,repo.handle,false)));
    }
    @Test void syncedCountersMayRegressButSingleDeviceCountersCannotAndBackupEligibilityIsImmutable() throws Exception {
        var repo=new Repository();var protocol=new WebAuthnProtocol(RP,ORIGIN,repo);var key=new Authenticator("counter passkey");key.store(repo,true,7);
        for(long count:new long[]{0,3,7,8}) {
            var request=protocol.assertion(Optional.empty());assertEquals(Math.max(7,count),protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x1d,count,repo.handle,false)).nextSignatureCount());
        }
        var request=protocol.assertion(Optional.empty());assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x05,8,repo.handle,false)));
        key.store(repo,false,7);
        for(long count:new long[]{0,3,7}) assertThrows(WebAuthnProtocol.Rejected.class,()->protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x05,count,repo.handle,false)));
        assertEquals(8,protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x05,8,repo.handle,false)).nextSignatureCount());
        key.store(repo,false,0);assertEquals(0,protocol.authenticate(request,key.assertion(request,RP,ORIGIN,0x05,0,repo.handle,false)).nextSignatureCount());
    }
}
