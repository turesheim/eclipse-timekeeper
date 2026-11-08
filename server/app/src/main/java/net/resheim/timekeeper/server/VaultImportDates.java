package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Source dates are applied inside a guarded import transaction. */
record VaultImportDates(Instant createdAt,Instant modifiedAt,String createdAtSource) {
    VaultImportDates {
        if(createdAtSource!=null&&(createdAt==null||!java.util.Set.of("ORIGINAL","ADDED","UNKNOWN").contains(createdAtSource)))throw ApiFailure.validation();
        if(createdAt!=null&&createdAtSource==null)createdAtSource="ORIGINAL";
    }
    VaultImportDates(Instant created,Instant modified){this(created,modified,created==null?null:"ORIGINAL");}
    static VaultImportDates parse(String created,String modified){return parse(created,modified,null);}
    static VaultImportDates parse(String created,String modified,String source){
        if(source!=null&&(created==null||!java.util.Set.of("ORIGINAL","ADDED","UNKNOWN").contains(source)))throw ApiFailure.validation();
        return new VaultImportDates(date(created),date(modified),created==null?null:source==null?"ORIGINAL":source);
    }
    private static Instant date(String value){
        if(value==null)return null;
        if(value.length()>40||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*"))throw ApiFailure.validation();
        try{var instant=Instant.parse(value);int year=instant.atOffset(java.time.ZoneOffset.UTC).getYear();if(year<1||year>9999)throw ApiFailure.validation();return instant;}catch(DateTimeParseException invalid){throw ApiFailure.validation();}
    }
    ObjectNode apply(ServerStore store,ObjectNode entry){
        boolean creationChanged=createdAt!=null&&(!createdAt.toString().equals(entry.path("createdAt").asText())||!createdAtSource.equals(entry.path("createdAtSource").asText()));
        boolean modificationChanged=modifiedAt!=null&&!modifiedAt.toString().equals(entry.path("modifiedAt").asText());
        if(!creationChanged&&!modificationChanged)return entry;
        if(modificationChanged&&!VaultCollection.folder(entry)){
            var ref=VaultSources.fromEntry(entry);
            var value=((ObjectNode)store.find(ref.type(),ref.id()).orElseThrow(ApiFailure::notFound)).deepCopy();
            value.put("modifiedAt",modifiedAt.toString());
            // Import dates and validators atomically without adding a content
            // revision for a metadata-only correction or changing a day label.
            if(store.update("UPDATE tk_resource SET payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",value.toString(),store.owner(),ref.type(),ref.id(),value.path("version").asLong())!=1)
                throw new ApiFailure(412,"STALE_VERSION");
            entry=new VaultIndex(store).content(VaultCollection.id(entry),new VaultSources(store,store.vaultLimits(),ref).snapshot(),entry.path("retainedBytes").asLong());
        }
        return new VaultIndex(store).dates(VaultCollection.id(entry),creationChanged?createdAt:null,modificationChanged?modifiedAt:null,createdAtSource);
    }
}
