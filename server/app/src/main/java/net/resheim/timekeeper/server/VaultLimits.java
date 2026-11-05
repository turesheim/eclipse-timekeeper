package net.resheim.timekeeper.server;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.runtime.configuration.MemorySize;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Installation-wide limits; deployment values are defaults until an administrator saves an override. */
@ApplicationScoped
class VaultLimits {
    static final String SETTINGS_KEY="notesLimits";
    static final long MAXIMUM_TOTAL=9007199254740991L;
    static final int MAXIMUM_FILE=1_000_000_000,MAXIMUM_ENTRIES=1_000_000;
    @ConfigProperty(name="timekeeper.notes.max-file-bytes",defaultValue="160000000") int fileBytes;
    @ConfigProperty(name="timekeeper.notes.max-markdown-bytes",defaultValue="10000000") int markdownBytes;
    @ConfigProperty(name="timekeeper.notes.max-total-bytes",defaultValue="5000000000") long totalBytes;
    @ConfigProperty(name="timekeeper.notes.max-entries",defaultValue="100000") int entries;
    @ConfigProperty(name="timekeeper.notes.max-page-size",defaultValue="200") int pageSize;
    @ConfigProperty(name="quarkus.http.limits.max-body-size",defaultValue="200M") MemorySize httpBody;
    @Inject AccountStore accounts;
    @Inject ServerStore store;
    record Values(int fileBytes,int markdownBytes,long totalBytes,int entries) {
        Values {
            if(fileBytes<1||fileBytes>MAXIMUM_FILE||markdownBytes<1||markdownBytes>fileBytes||totalBytes<fileBytes||totalBytes>MAXIMUM_TOTAL||entries<4||entries>MAXIMUM_ENTRIES)throw ApiFailure.validation();
        }
        ObjectNode json(){return JsonValues.object().put("fileBytes",fileBytes).put("markdownBytes",markdownBytes).put("totalBytes",totalBytes).put("entries",entries);}
        static Values parse(JsonNode value){
            if(!value.isObject()||value.size()!=4)throw ApiFailure.validation();
            for(String field:Set.of("fileBytes","markdownBytes","totalBytes","entries"))if(!value.path(field).isIntegralNumber()||!value.path(field).canConvertToLong())throw ApiFailure.validation();
            if(!value.path("fileBytes").canConvertToInt()||!value.path("markdownBytes").canConvertToInt()||!value.path("entries").canConvertToInt())throw ApiFailure.validation();
            return new Values(value.path("fileBytes").asInt(),value.path("markdownBytes").asInt(),value.path("totalBytes").asLong(),value.path("entries").asInt());
        }
    }
    @PostConstruct void validate(){
        try{defaults();if(pageSize<1||pageSize>1000)throw ApiFailure.validation();}
        catch(ApiFailure invalid){throw new IllegalStateException("Invalid Notes storage limits");}
    }
    Values defaults(){return new Values(fileBytes,markdownBytes,totalBytes,entries);}
    Values current(){
        var connection=store==null?null:store.currentConnection();
        if(connection!=null)return NotesLimitSettings.read(connection,false,defaults()).values();
        return accounts==null?defaults():accounts.tx(c->NotesLimitSettings.read(c,false,defaults()).values());
    }
    int fileBytes(){return current().fileBytes();}
    int markdownBytes(){return current().markdownBytes();}
    long totalBytes(){return current().totalBytes();}
    int entries(){return current().entries();}
    int pageSize(){return pageSize;}
    long uploadCeiling(){return httpBody.isNegative()?MAXIMUM_FILE:Math.min(MAXIMUM_FILE,httpBody.asLongValue());}
    ObjectNode snapshot(){return current().json().put("pageSize",pageSize)
            .put("pathBytes",VaultPath.MAX_BYTES).put("segmentBytes",VaultPath.MAX_SEGMENT_BYTES).put("depth",VaultPath.MAX_DEPTH);}
}
