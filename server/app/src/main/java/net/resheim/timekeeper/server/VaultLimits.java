package net.resheim.timekeeper.server;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deployment limits; account settings cannot increase their own storage allowance. */
@ApplicationScoped
class VaultLimits {
    @ConfigProperty(name="timekeeper.notes.max-file-bytes",defaultValue="16000000") int fileBytes;
    @ConfigProperty(name="timekeeper.notes.max-markdown-bytes",defaultValue="1000000") int markdownBytes;
    @ConfigProperty(name="timekeeper.notes.max-total-bytes",defaultValue="500000000") long totalBytes;
    @ConfigProperty(name="timekeeper.notes.max-entries",defaultValue="10000") int entries;
    @ConfigProperty(name="timekeeper.notes.max-page-size",defaultValue="200") int pageSize;
    @PostConstruct void validate(){
        if(fileBytes<1||markdownBytes<1||markdownBytes>fileBytes||totalBytes<fileBytes||entries<4||pageSize<1||pageSize>1000)
            throw new IllegalStateException("Invalid Notes storage limits");
    }
    int fileBytes(){return fileBytes;}
    int markdownBytes(){return markdownBytes;}
    long totalBytes(){return totalBytes;}
    int entries(){return entries;}
    int pageSize(){return pageSize;}
    ObjectNode snapshot(){return JsonValues.object().put("fileBytes",fileBytes).put("markdownBytes",markdownBytes)
            .put("totalBytes",totalBytes).put("entries",entries).put("pageSize",pageSize)
            .put("pathBytes",VaultPath.MAX_BYTES).put("segmentBytes",VaultPath.MAX_SEGMENT_BYTES).put("depth",VaultPath.MAX_DEPTH);}
}
