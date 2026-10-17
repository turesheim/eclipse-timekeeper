package net.resheim.timekeeper.server;

import java.sql.SQLException;
import java.util.UUID;
import java.util.Optional;
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
        var previous=find(key,method,path,ifMatch,input);if(previous.isPresent()) return previous.get();
        String fingerprint=fingerprint(method,path,ifMatch,input);
        ApiReplies result=work.get();
        store.update("INSERT INTO tk_receipt(owner_id,command_id,fingerprint,status,body,etag,location,created_at) VALUES(?,?,?,?,?,?,?,?)",store.owner(),id,fingerprint,result.status(),result.body().toString(),result.etag(),result.location(),store.timestamp(store.now()));
        return result;
    }
    private static String fingerprint(String method,String path,String ifMatch,JsonNode input) {
        return JsonValues.hash(method+"\n"+path+"\n"+(ifMatch==null ? "" : ifMatch)+"\n"+(input==null ? "" : JsonValues.canonical(input)));
    }
    Optional<ApiReplies> find(String key,String method,String path,String ifMatch,JsonNode input) {
        UUID id=ContractInputs.pathId(key);
        String fingerprint=fingerprint(method,path,ifMatch,input);
        try(var statement=store.statement("SELECT fingerprint,status,body,etag,location FROM tk_receipt WHERE owner_id=? AND command_id=?",store.owner(),id);var rows=statement.executeQuery()) {
            if(rows.next()) {
                if(!fingerprint.equals(rows.getString(1))) throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED");
                return Optional.of(new ApiReplies(rows.getInt(2),JsonValues.parse(rows.getString(3)),rows.getString(4),rows.getString(5)));
            }
        } catch(SQLException e) { throw new ApiFailure(503,"UNAVAILABLE"); }
        return Optional.empty();
    }
}
