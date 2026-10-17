package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;

/** Shared credential repository. Call mutations under the authentication-control lock. */
final class PasskeyCredentials implements CredentialRepository {
    private final Connection connection;
    private final String rpId;
    PasskeyCredentials(Connection connection,String rpId) {this.connection=connection;this.rpId=rpId;}
    record Stored(UUID id,String owner,String username,ByteArray handle,String rpId,ByteArray credentialId,
            ByteArray publicKey,long counter,boolean backupEligible,boolean backedUp,Set<AuthenticatorTransport> transports,
            String name,Instant created,Instant lastUsed,boolean revoked,long version) {
        RegisteredCredential registered() {
            return RegisteredCredential.builder().credentialId(credentialId).userHandle(handle).publicKeyCose(publicKey)
                    .signatureCount(counter).backupEligible(backupEligible).backupState(backedUp).transports(transports).build();
        }
        ObjectNode management() {
            return object().put("id",id.toString()).put("ownerId",owner).put("name",name).put("rpId",rpId)
                    .put("createdAt",created.toString()).put("revoked",revoked).put("version",version);
        }
        ObjectNode listed(String currentRp) {
            var value=management();value.put("etag",etag(management()));value.put("lastUsedAt",lastUsed==null ? null : lastUsed.toString());
            value.put("usableHere",!revoked && rpId.equals(currentRp));value.put("backedUp",backedUp);return value;
        }
    }
    private static final String SELECT="SELECT k.id,k.owner_id,a.username,u.user_handle,k.rp_id,k.credential_id,k.public_key,k.signature_count,k.backup_eligible,k.backed_up,k.transports,k.name,k.created_at,k.last_used_at,k.revoked,k.version FROM tk_passkey_credential k JOIN tk_passkey_user u ON u.owner_id=k.owner_id JOIN tk_account a ON a.owner_id=k.owner_id ";
    static ByteArray bytes(String value) {
        try {return ByteArray.fromBase64Url(value);}catch(Exception invalid) {throw new ApiFailure(503,"UNAVAILABLE");}
    }
    private List<Stored> select(String where,Object... parameters) {
        var result=new ArrayList<Stored>();
        try(var statement=query(connection,SELECT+where,parameters);var rows=statement.executeQuery()) {
            while(rows.next()) {
                var transports=new HashSet<AuthenticatorTransport>();
                for(var transport:parse(rows.getString(11))) transports.add(AuthenticatorTransport.of(transport.asText()));
                String used=rows.getString(14);
                result.add(new Stored(rows.getObject(1,UUID.class),rows.getString(2),rows.getString(3),bytes(rows.getString(4)),rows.getString(5),bytes(rows.getString(6)),bytes(rows.getString(7)),
                        rows.getLong(8),rows.getBoolean(9),rows.getBoolean(10),Set.copyOf(transports),rows.getString(12),Instant.parse(rows.getString(13)),used==null ? null : Instant.parse(used),rows.getBoolean(15),rows.getLong(16)));
            }
        }catch(SQLException failure) {throw databaseFailure(failure);}return List.copyOf(result);
    }
    Stored owned(String owner,UUID id) {return select("WHERE k.owner_id=? AND k.id=? AND k.revoked=FALSE",owner,id).stream().findFirst().orElseThrow(ApiFailure::notFound);}
    List<Stored> owned(String owner) {return select("WHERE k.owner_id=? AND k.revoked=FALSE ORDER BY k.created_at,k.id",owner);}
    Optional<Stored> credential(ByteArray id,boolean activeOnly) {
        return select("WHERE k.credential_digest=?"+(activeOnly ? " AND k.revoked=FALSE AND a.state='ENABLED' AND k.rp_id=?" : ""),
                activeOnly ? new Object[]{hash(id.getBase64Url()),rpId} : new Object[]{hash(id.getBase64Url())}).stream()
                .filter(value->value.credentialId().equals(id)).findFirst();
    }
    ByteArray handle(String owner) {
        try(var statement=query(connection,"SELECT user_handle FROM tk_passkey_user WHERE owner_id=?",owner);var rows=statement.executeQuery()) {
            if(rows.next()) return bytes(rows.getString(1));
        }catch(SQLException failure) {throw databaseFailure(failure);}
        String handle=AccountPasswords.secret();execute(connection,"INSERT INTO tk_passkey_user(owner_id,user_handle) VALUES(?,?)",owner,handle);return bytes(handle);
    }
    Stored add(String owner,String name,RegistrationResult registration,Instant now) {
        name=displayName(name);ByteArray credential=registration.getKeyId().getId();
        if(owned(owner).size()>=100 || credential.size()>3072 || registration.getPublicKeyCose().size()>6144) throw ApiFailure.validation();
        // Includes tombstones and all RPs/owners. A revoked credential is never reassigned.
        if(credential(credential,false).isPresent()) throw ApiFailure.conflict();
        UUID id=UUID.randomUUID();var transports=array();registration.getKeyId().getTransports().orElseGet(TreeSet::new).stream().sorted().forEach(value->transports.add(value.getId()));
        execute(connection,"INSERT INTO tk_passkey_credential(id,owner_id,credential_digest,credential_id,rp_id,public_key,signature_count,backup_eligible,backed_up,transports,name,created_at,last_used_at,revoked,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL,FALSE,0)",
                id,owner,hash(credential.getBase64Url()),credential.getBase64Url(),rpId,registration.getPublicKeyCose().getBase64Url(),registration.getSignatureCount(),registration.isBackupEligible(),registration.isBackedUp(),transports.toString(),name,now.toString());
        return owned(owner,id);
    }
    Stored used(WebAuthnProtocol.Assertion assertion,Instant now) {
        var result=assertion.result();var stored=credential(result.getCredentialId(),true).orElseThrow(()->ApiFailure.unauthorized(false));
        if(!stored.handle().equals(result.getUserHandle()) || stored.counter()!=result.getCredential().getSignatureCount()
                || stored.backupEligible()!=result.isBackupEligible()) throw ApiFailure.unauthorized(false);
        if(execute(connection,"UPDATE tk_passkey_credential SET signature_count=?,backed_up=?,last_used_at=? WHERE id=? AND owner_id=? AND revoked=FALSE AND signature_count=?",
                assertion.nextSignatureCount(),result.isBackedUp(),now.toString(),stored.id(),stored.owner(),stored.counter())!=1) throw ApiFailure.unauthorized(false);
        return owned(stored.owner(),stored.id());
    }
    Stored rename(String owner,UUID id,String match,String name) {
        var value=owned(owner,id);ApiReplies.match(value.management(),match);
        if(execute(connection,"UPDATE tk_passkey_credential SET name=?,version=version+1 WHERE id=? AND owner_id=? AND version=? AND revoked=FALSE",displayName(name),id,owner,value.version())!=1) throw new ApiFailure(412,"STALE_VERSION");
        return owned(owner,id);
    }
    void revoke(String owner,UUID id,String match) {
        var value=owned(owner,id);ApiReplies.match(value.management(),match);
        if(execute(connection,"UPDATE tk_passkey_credential SET revoked=TRUE,version=version+1 WHERE id=? AND owner_id=? AND version=? AND revoked=FALSE",id,owner,value.version())!=1) throw new ApiFailure(412,"STALE_VERSION");
        execute(connection,"DELETE FROM tk_auth_session WHERE owner_id=? AND passkey_id=?",owner,id);
    }
    static void recover(Connection c,String owner) {
        execute(c,"UPDATE tk_passkey_credential SET revoked=TRUE,version=version+1 WHERE owner_id=? AND revoked=FALSE",owner);
        execute(c,"UPDATE tk_passkey_ceremony SET consumed=TRUE WHERE owner_id=? AND consumed=FALSE",owner);
    }
    @Override public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        var ids=new HashSet<PublicKeyCredentialDescriptor>();
        for(var value:select("WHERE a.username=? AND a.state='ENABLED' AND k.rp_id=? AND k.revoked=FALSE",username,rpId))
            ids.add(PublicKeyCredentialDescriptor.builder().id(value.credentialId()).transports(value.transports()).build());return Set.copyOf(ids);
    }
    @Override public Optional<ByteArray> getUserHandleForUsername(String username) {
        try(var statement=query(connection,"SELECT u.user_handle FROM tk_passkey_user u JOIN tk_account a ON a.owner_id=u.owner_id WHERE a.username=? AND a.state='ENABLED'",username);var rows=statement.executeQuery()) {
            return rows.next() ? Optional.of(bytes(rows.getString(1))) : Optional.empty();
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    @Override public Optional<String> getUsernameForUserHandle(ByteArray handle) {
        try(var statement=query(connection,"SELECT a.username FROM tk_passkey_user u JOIN tk_account a ON a.owner_id=u.owner_id WHERE u.user_handle=? AND a.state='ENABLED'",handle.getBase64Url());var rows=statement.executeQuery()) {
            return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
        }catch(SQLException failure) {throw databaseFailure(failure);}
    }
    @Override public Optional<RegisteredCredential> lookup(ByteArray id,ByteArray handle) {return credential(id,true).filter(value->value.handle().equals(handle)).map(Stored::registered);}
    @Override public Set<RegisteredCredential> lookupAll(ByteArray id) {return credential(id,true).map(value->Set.of(value.registered())).orElseGet(Set::of);}
}
