package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Recent document metadata, bounded by civil days in the account's time zone. */
final class RecentNotes {
    record Window(LocalDate first,LocalDate last,ZoneId zone) {
        Instant from(){return first.atStartOfDay(zone).toInstant();}
        Instant until(){return last.plusDays(1).atStartOfDay(zone).toInstant();}
    }
    static Window window(Instant now,ZoneId zone,int days){
        var today=now.atZone(zone).toLocalDate();return new Window(today.minusDays(days-1L),today,zone);
    }
    private final ServerStore store;
    private final VaultLimits limits;
    RecentNotes(ServerStore store,VaultLimits limits){this.store=store;this.limits=limits;}
    ObjectNode page(int offset,int count,String expected){
        if(offset<0||count<1||count>limits.pageSize())throw ApiFailure.validation();
        var settings=new VaultSettings(store).get();int days=settings.path("recentNotesDays").asInt();
        var window=window(store.now(),ZoneId.of(store.profile().path("zoneId").asText()),days);
        var vault=new VaultCollection(store,limits);
        var context=object().put("generation",vault.generation()).put("days",days).put("zoneId",window.zone().getId())
                .put("fromDate",window.first().toString()).put("toDate",window.last().toString());
        String snapshot=etag(context);
        if(expected!=null)VaultCollection.match(snapshot,expected);
        else if(offset>0)throw new ApiFailure(428,"PRECONDITION_REQUIRED");
        var journal=new VaultPath(settings.path("journalFolder").asText());
        boolean configuration=settings.path("syncObsidianConfig").asBoolean();
        Instant from=window.from(),until=window.until();
        var entries=new VaultIndex(store).search("",false).stream()
                .filter(entry->Set.of("MARKDOWN","EVENT").contains(entry.path("kind").asText()))
                .filter(entry->!VaultCollection.path(entry).beneath(journal))
                .filter(entry->configuration||!VaultSettings.configurationPath(VaultCollection.path(entry)))
                .filter(entry->{var modified=Instant.parse(entry.path("modifiedAt").asText());return !modified.isBefore(from)&&modified.isBefore(until);})
                .sorted(Comparator.<ObjectNode,Instant>comparing(entry->Instant.parse(entry.path("modifiedAt").asText())).reversed()
                        .thenComparing(entry->VaultCollection.path(entry).key()).thenComparing(entry->entry.path("id").asText())).toList();
        if(offset>entries.size())throw ApiFailure.validation();
        var result=context.deepCopy();result.remove("generation");result.put("snapshot",snapshot);
        var items=result.putArray("items");int end=(int)Math.min(entries.size(),(long)offset+count);
        for(var entry:entries.subList(offset,end))items.add(vault.metadata(entry));
        if(end<entries.size())result.put("nextOffset",end);return result;
    }
}
