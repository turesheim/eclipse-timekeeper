package net.resheim.timekeeper.server;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Receipt and domain changes are committed in the same locked owner transaction. */
@ApplicationScoped
public class CommandReceipts {
    @Inject ServerStore store;
    public ApiReplies execute(String key,String method,String path,String ifMatch,JsonNode input,Supplier<ApiReplies> work) {
        UUID id=ContractInputs.pathId(key);
        String fingerprint=JsonValues.hash(method+"\n"+path+"\n"+(ifMatch==null ? "" : ifMatch)+"\n"+(input==null ? "" : JsonValues.canonical(input)));
        try(var statement=store.statement("SELECT fingerprint,status,body,etag,location FROM tk_receipt WHERE owner_id=? AND command_id=?",store.owner(),id);var rows=statement.executeQuery()) {
            if(rows.next()) {
                if(!fingerprint.equals(rows.getString(1))) throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED");
                return new ApiReplies(rows.getInt(2),JsonValues.parse(rows.getString(3)),rows.getString(4),rows.getString(5));
            }
        } catch(SQLException e) { throw new ApiFailure(503,"UNAVAILABLE"); }
        ApiReplies result=work.get();
        store.update("INSERT INTO tk_receipt(owner_id,command_id,fingerprint,status,body,etag,location,created_at) VALUES(?,?,?,?,?,?,?,?)",store.owner(),id,fingerprint,result.status(),result.body().toString(),result.etag(),result.location(),OffsetDateTime.ofInstant(store.now(),ZoneOffset.UTC));
        return result;
    }
}
