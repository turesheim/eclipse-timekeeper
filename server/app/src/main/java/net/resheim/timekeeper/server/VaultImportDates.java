package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Source dates are accepted only inside a create-only import transaction. */
record VaultImportDates(Instant createdAt,Instant modifiedAt) {
    static VaultImportDates parse(String created,String modified){return new VaultImportDates(date(created),date(modified));}
    private static Instant date(String value){
        if(value==null)return null;
        if(value.length()>40||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*"))throw ApiFailure.validation();
        try{var instant=Instant.parse(value);int year=instant.atOffset(java.time.ZoneOffset.UTC).getYear();if(year<1||year>9999)throw ApiFailure.validation();return instant;}catch(DateTimeParseException invalid){throw ApiFailure.validation();}
    }
    ObjectNode apply(ServerStore store,ObjectNode entry){
        if(createdAt==null&&modifiedAt==null)return entry;
        if(modifiedAt!=null&&!VaultCollection.folder(entry)){
            var ref=VaultSources.fromEntry(entry);
            var value=((ObjectNode)store.find(ref.type(),ref.id()).orElseThrow(ApiFailure::notFound)).deepCopy();
            value.put("modifiedAt",modifiedAt.toString());
            // The new content version has not left this owner transaction yet.
            // Date import must not create a spurious edit or change a day label.
            if(store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",value.toString(),store.owner(),ref.type(),ref.id(),value.path("version").asLong())!=1)
                throw new ApiFailure(412,"STALE_VERSION");
            entry=new VaultIndex(store).content(VaultCollection.id(entry),new VaultSources(store,store.vaultLimits(),ref).snapshot(),entry.path("retainedBytes").asLong());
        }
        return new VaultIndex(store).dates(VaultCollection.id(entry),createdAt,modifiedAt);
    }
}
