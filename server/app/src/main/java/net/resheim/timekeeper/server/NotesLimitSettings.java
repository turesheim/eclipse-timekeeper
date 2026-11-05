package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.AccountStore.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Live shared quotas have their own version and never activate authentication/SMTP settings. */
@ApplicationScoped
class NotesLimitSettings {
    @Inject AccountStore accounts;
    @Inject InstanceAdministrator administrators;
    @Inject VaultLimits limits;
    record Stored(long version,VaultLimits.Values values){ObjectNode json(){return values.json().put("version",version);}}
    record Reply(ObjectNode data,String etag){}
    static void validateStored(JsonNode value){
        if(!value.isObject()||value.size()!=5||!value.path("version").isIntegralNumber()||!value.path("version").canConvertToLong()||value.path("version").asLong()<1||value.path("version").asLong()>VaultLimits.MAXIMUM_TOTAL)throw ApiFailure.validation();
        var fields=((ObjectNode)value).deepCopy();fields.remove("version");VaultLimits.Values.parse(fields);
    }
    static Stored read(Connection c,boolean locked,VaultLimits.Values defaults){
        try(var statement=query(c,"SELECT effective FROM tk_service_settings WHERE id=1"+(locked?" FOR UPDATE":""));var rows=statement.executeQuery()){
            if(!rows.next())return new Stored(0,defaults);
            var value=parse(rows.getString(1)).get(VaultLimits.SETTINGS_KEY);if(value==null)return new Stored(0,defaults);
            try{validateStored(value);var fields=((ObjectNode)value).deepCopy();fields.remove("version");return new Stored(value.path("version").asLong(),VaultLimits.Values.parse(fields));}
            catch(ApiFailure invalid){throw new ApiFailure(503,"UNAVAILABLE");}
        }catch(SQLException failure){throw databaseFailure(failure);}
    }
    Reply get(TokenRegistry.Principal principal){return accounts.tx(c->{accounts.guard(c,principal);requireAdmin(c,principal);return reply(read(c,false,limits.defaults()),accountSession(c,principal));});}
    Reply save(InstanceAdministrator.Proof proof,String match,JsonNode input){
        administrators.requireFresh(proof);var desired=VaultLimits.Values.parse(input);
        return accounts.tx(c->{
            lock(c);administrators.check(c,proof.principal());var current=read(c,true,limits.defaults());VaultCollection.match(etag(current.json()),match);
            if(desired.equals(current.values()))return reply(current,true);
            if(desired.fileBytes()>current.values().fileBytes()&&desired.fileBytes()>limits.uploadCeiling())throw new ApiFailure(422,"VAULT_HTTP_LIMIT");
            if(current.version()>=VaultLimits.MAXIMUM_TOTAL)throw ApiFailure.conflict();
            var next=new Stored(current.version()+1,desired);
            try(var statement=query(c,"SELECT saved,effective FROM tk_service_settings WHERE id=1 FOR UPDATE");var rows=statement.executeQuery()){
                if(!rows.next())throw new ApiFailure(503,"UNAVAILABLE");
                var saved=(ObjectNode)parse(rows.getString(1));var effective=(ObjectNode)parse(rows.getString(2));
                saved.set(VaultLimits.SETTINGS_KEY,next.json());effective.set(VaultLimits.SETTINGS_KEY,next.json());
                if(execute(c,"UPDATE tk_service_settings SET saved=?,effective=? WHERE id=1",saved.toString(),effective.toString())!=1)throw new ApiFailure(503,"UNAVAILABLE");
            }catch(SQLException failure){throw databaseFailure(failure);}
            return reply(next,true);
        });
    }
    private Reply reply(Stored stored,boolean edit){
        var result=object().put("version",stored.version()).put("canEdit",edit).put("configured",stored.version()>0).put("maximumUploadBytes",limits.uploadCeiling());
        result.set("limits",stored.values().json());result.set("defaults",limits.defaults().json());return new Reply(result,etag(stored.json()));
    }
}
