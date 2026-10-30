package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Namespace bindings delegate bytes and content versions to one canonical resource. */
final class VaultSources {
    static final List<String> LEGACY=List.of(DayNotes.TYPE,WeekNotes.TYPE,EventNotes.TYPE,JournalAssets.TYPE);
    record Ref(String type,UUID id,String key) {
        Ref {if(!LEGACY.contains(type)&&!VaultContent.TYPE.equals(type))throw new IllegalStateException("Invalid stored vault reference");Objects.requireNonNull(id);Objects.requireNonNull(key);}
    }
    record Snapshot(String tag,long version,boolean deleted,long byteLength,String sha256,Instant modifiedAt) {}
    static Ref fromEntry(JsonNode entry){return new Ref(entry.path("sourceType").asText(),uuid(entry.path("sourceId")),entry.path("sourceKey").asText());}
    static Ref fromStored(String type,JsonNode value){
        String key=switch(type){
            case DayNotes.TYPE->value.path("date").asText();
            case WeekNotes.TYPE->value.path("week").asText();
            case EventNotes.TYPE->new EventNotes.Key(uuid(value.path("subscriptionId")),value.path("eventKey").asText()).filename();
            case JournalAssets.TYPE->value.path("filename").asText();
            default->throw new IllegalArgumentException("Not a journal resource");
        };
        return new Ref(type,uuid(value.path("id")),key);
    }
    static VaultIndex.Kind kind(Ref ref){return switch(ref.type()){
        case DayNotes.TYPE->VaultIndex.Kind.DAY;case WeekNotes.TYPE->VaultIndex.Kind.WEEK;
        case EventNotes.TYPE->VaultIndex.Kind.EVENT;case JournalAssets.TYPE->VaultIndex.Kind.ASSET;
        default->throw new IllegalArgumentException("General file kind belongs to its namespace entry");
    };}
    private final ServerStore store;
    private final VaultLimits limits;
    private final Ref ref;
    VaultSources(ServerStore store,VaultLimits limits,Ref ref){this.store=store;this.limits=limits;this.ref=ref;}
    private ObjectNode value(){
        var value=switch(ref.type()){
            case DayNotes.TYPE->new DayNotes(store).getContent(DayNotes.date(ref.key()));
            case WeekNotes.TYPE->new WeekNotes(store).get(WeekNotes.week(ref.key()));
            case EventNotes.TYPE->new EventNotes(store).get(EventNotes.filename(ref.key()));
            case JournalAssets.TYPE->{
                var previous=new JournalAssets(store).existing(ref.key());
                yield previous==null?object().put("id",ref.id().toString()).put("ownerId",store.owner()).put("version",0).put("deleted",true):(ObjectNode)previous;
            }
            case VaultContent.TYPE->new VaultContent(store).metadata(ref.id());
            default->throw new IllegalStateException("Invalid stored vault reference");
        };
        if(!ref.id().toString().equals(value.path("id").asText())||!store.owner().equals(value.path("ownerId").asText()))throw new IllegalStateException("Invalid stored vault reference");
        return value;
    }
    private byte[] document(){return switch(ref.type()){
        case DayNotes.TYPE->new DayNotes(store).document(DayNotes.date(ref.key()));
        case WeekNotes.TYPE->new WeekNotes(store).document(WeekNotes.week(ref.key()));
        case EventNotes.TYPE->new EventNotes(store).document(EventNotes.filename(ref.key()));
        case JournalAssets.TYPE->JournalAssets.bytes(new JournalAssets(store).get(ref.key()));
        case VaultContent.TYPE->new VaultContent(store).read(ref.id());
        default->throw new IllegalStateException("Invalid stored vault reference");
    };}
    Snapshot snapshot(){
        var value=value();boolean deleted=value.path("version").asLong()==0||value.path("deleted").asBoolean();
        byte[] bytes=deleted?new byte[0]:document();
        Instant modified=switch(ref.type()){
            case DayNotes.TYPE->new DayNotes(store).modifiedAt(DayNotes.date(ref.key()));
            case WeekNotes.TYPE->new WeekNotes(store).modifiedAt(WeekNotes.week(ref.key()));
            case EventNotes.TYPE->new EventNotes(store).modifiedAt(EventNotes.filename(ref.key()));
            default->value.hasNonNull("modifiedAt")?Instant.parse(value.path("modifiedAt").asText()):Instant.EPOCH;
        };
        return new Snapshot(etag(value),value.path("version").asLong(),deleted,bytes.length,ArchiveJournalFiles.digest(bytes),modified);
    }
    byte[] read(){var value=value();if(value.path("version").asLong()==0||value.path("deleted").asBoolean())throw ApiFailure.notFound();return document();}
    Snapshot write(String expected,byte[] bytes,boolean markdown){
        if(bytes==null||bytes.length>limits.fileBytes())throw new ApiFailure(413,"VAULT_FILE_TOO_LARGE");
        switch(ref.type()){
            case DayNotes.TYPE->new DayNotes(store).saveDocument(DayNotes.date(ref.key()),expected,bytes);
            case WeekNotes.TYPE->new WeekNotes(store).saveDocument(WeekNotes.week(ref.key()),expected,bytes);
            case EventNotes.TYPE->new EventNotes(store).saveDocument(EventNotes.filename(ref.key()),expected,bytes);
            case JournalAssets.TYPE->new JournalAssets(store).save(ref.key(),expected,bytes);
            case VaultContent.TYPE->{if(markdown)VaultContent.markdown(bytes,limits.markdownBytes());new VaultContent(store).save(ref.id(),expected,bytes,limits.fileBytes());}
            default->throw new IllegalStateException("Invalid stored vault reference");
        }
        return snapshot();
    }
    Snapshot delete(String expected){
        switch(ref.type()){
            case DayNotes.TYPE->new DayNotes(store).deleteContent(DayNotes.date(ref.key()),expected);
            case WeekNotes.TYPE->new WeekNotes(store).delete(WeekNotes.week(ref.key()),expected);
            case EventNotes.TYPE->new EventNotes(store).delete(EventNotes.filename(ref.key()),expected);
            case JournalAssets.TYPE->new JournalAssets(store).delete(ref.key(),expected);
            case VaultContent.TYPE->new VaultContent(store).delete(ref.id(),expected);
            default->throw new IllegalStateException("Invalid stored vault reference");
        }
        return snapshot();
    }
}
