package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Private restored-database checks. Never consult source paths or modify the target. */
final class InstanceAuthenticationPreflight {
    private InstanceAuthenticationPreflight() { }
    static void inspect(Connection connection) throws InstanceArchiveRejection {
        try {
            var settings=ServiceSettings.forRestore(connection);
            try(var statement=query(connection,"SELECT origin,epoch FROM tk_recovery_control WHERE id=1");var rows=statement.executeQuery()) {
                if(!rows.next()||rows.getLong(2)!=settings.effective().path("originEpoch").asLong()
                    ||!Objects.equals(rows.getString(1),settings.effective().path("publicOrigin").textValue()))throw invalid();
            }
            long administrators=0;
            try(var statement=query(connection,"SELECT username,password_hash,display_name,state,administrator FROM tk_account");var rows=statement.executeQuery()) {
                while(rows.next()) {
                    displayName(rows.getString(3));String name=rows.getString(1),encoded=rows.getString(2),state=rows.getString(4);
                    if(name!=null&&(!username(name).equals(name)||!AccountPasswords.supported(encoded)))throw invalid();
                    if(state.equals("ENABLED")&&rows.getBoolean(5)&&name!=null)administrators++;
                }
            }
            if(administrators==0)throw invalid();
            try(var statement=query(connection,"SELECT bootstrap_completed FROM tk_auth_control WHERE id=1");var rows=statement.executeQuery()) {if(!rows.next()||!rows.getBoolean(1))throw invalid();}
            try(var statement=query(connection,"SELECT digest,source,legacy_digest,created_at,last_used_at,expires_at FROM tk_api_token");var rows=statement.executeQuery()) {
                while(rows.next()) {
                    digest(rows.getString(1));String legacy=rows.getString(3);
                    if(rows.getString(2).equals("LEGACY")){digest(legacy);}else if(legacy!=null)throw invalid();
                    Instant.parse(rows.getString(4));optionalInstant(rows.getString(5));optionalInstant(rows.getString(6));
                }
            }
            try(var statement=query(connection,"SELECT user_handle FROM tk_passkey_user");var rows=statement.executeQuery()) {while(rows.next())if(PasskeyCredentials.bytes(rows.getString(1)).isEmpty())throw invalid();}
            try(var statement=query(connection,"SELECT k.credential_digest,k.credential_id,k.public_key,k.rp_id,k.transports,k.created_at,k.last_used_at,u.user_handle FROM tk_passkey_credential k JOIN tk_passkey_user u ON u.owner_id=k.owner_id");var rows=statement.executeQuery()) {
                while(rows.next()) {
                    var credential=PasskeyCredentials.bytes(rows.getString(2));
                    if(credential.isEmpty()||!credential.getBase64Url().equals(rows.getString(2))||!hash(credential.getBase64Url()).equals(rows.getString(1)))throw invalid();
                    var key=RegisteredCredential.builder().credentialId(credential).userHandle(PasskeyCredentials.bytes(rows.getString(8))).publicKeyCose(PasskeyCredentials.bytes(rows.getString(3))).signatureCount(0).build();
                    key.getParsedPublicKey();String rp=rows.getString(4);
                    if(!PasskeyRealm.rp("https://"+rp,"AUTO",true).filter(rp::equals).isPresent())throw invalid();
                    var transports=parse(rows.getString(5));if(!transports.isArray())throw invalid();
                    for(var transport:transports){if(!transport.isTextual()||transport.asText().isBlank())throw invalid();AuthenticatorTransport.of(transport.asText());}
                    Instant.parse(rows.getString(6));optionalInstant(rows.getString(7));
                }
            }
        }catch(Exception failure){throw invalid();}
    }
    private static void digest(String value) throws InstanceArchiveRejection {if(value==null||!value.matches("[a-f0-9]{64}"))throw invalid();}
    private static void optionalInstant(String value){if(value!=null)Instant.parse(value);}
    private static InstanceArchiveRejection invalid(){return new InstanceArchiveRejection(InstanceArchiveRejection.Code.INVALID_INSTANCE_AUTHENTICATION);}
}
