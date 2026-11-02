package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Prospective, bounded, owner-private content snapshots, separate from day labels. */
final class NoteRevisions {
    static final String TYPE="NOTE_REVISION";
    static final int PER_NOTE=20;
    static final long ACCOUNT_BYTES=8_000_000;
    private static final Set<String> TYPES=Set.of(DayNotes.TYPE,WeekNotes.TYPE,EventNotes.TYPE,VaultContent.TYPE);
    private final ServerStore store;
    NoteRevisions(ServerStore store){this.store=store;}
    VaultSources.Ref reference(String type,UUID id){
        if(type==null||!TYPES.contains(type))throw ApiFailure.validation();
        var value=store.find(type,id).orElseThrow(ApiFailure::notFound);
        return type.equals(VaultContent.TYPE)?new VaultSources.Ref(type,id,id.toString()):VaultSources.fromStored(type,value);
    }
    void before(VaultSources.Ref ref,byte[] next){
        if(!TYPES.contains(ref.type()))return;
        var source=new VaultSources(store,store.vaultLimits(),ref);var previous=source.snapshot();
        if(previous.deleted()||previous.version()==0)return;
        byte[] bytes=source.read();if(next!=null&&Arrays.equals(bytes,next))return;
        var existing=values(ref);
        if(existing.stream().anyMatch(value->value.path("contentVersion").asLong()==previous.version()&&value.path("sha256").asText().equals(previous.sha256())))return;
        UUID id=UUID.randomUUID();var value=object().put("id",id.toString()).put("ownerId",store.owner()).put("version",1)
                .put("sourceType",ref.type()).put("sourceId",ref.id().toString()).put("contentVersion",previous.version())
                .put("sequence",store.list(TYPE).stream().mapToLong(item->item.path("sequence").asLong()).max().orElse(0)+1).put("capturedAt",store.now().toString()).put("modifiedAt",previous.modifiedAt().toString()).put("byteLength",bytes.length)
                .put("sha256",previous.sha256()).put("data",Base64.getEncoder().encodeToString(bytes));
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),TYPE,id,1,value.toString());
        var perNote=values(ref);while(perNote.size()>PER_NOTE)remove(perNote.removeLast());
        var all=new ArrayList<>(store.list(TYPE));all.sort(order());long total=all.stream().mapToLong(item->item.path("byteLength").asLong()).sum();
        while(total>ACCOUNT_BYTES&&!all.isEmpty()){var oldest=all.removeLast();total-=oldest.path("byteLength").asLong();remove(oldest);}
    }
    private static Comparator<JsonNode> order(){return Comparator.<JsonNode>comparingLong(value->value.path("sequence").asLong()).reversed();}
    private ArrayList<JsonNode> values(VaultSources.Ref ref){var result=new ArrayList<JsonNode>();for(var value:store.list(TYPE))if(matches(value,ref))result.add(value);result.sort(order());return result;}
    private boolean matches(JsonNode value,VaultSources.Ref ref){return value.path("sourceType").asText().equals(ref.type())&&value.path("sourceId").asText().equals(ref.id().toString());}
    private void remove(JsonNode value){store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),TYPE,uuid(value.path("id")));}
    private ObjectNode metadata(JsonNode value){var result=((ObjectNode)value).deepCopy();result.remove(List.of("data","ownerId","version"));return result;}
    ObjectNode list(VaultSources.Ref ref){var result=object().put("retainedPerNote",PER_NOTE).put("accountByteBudget",ACCOUNT_BYTES).put("prospective",true);var items=result.putArray("items");values(ref).forEach(value->items.add(metadata(value)));return result;}
    ObjectNode get(VaultSources.Ref ref,UUID revision){
        var value=store.find(TYPE,revision).orElseThrow(ApiFailure::notFound);if(!matches(value,ref))throw ApiFailure.notFound();
        byte[] bytes=Base64.getDecoder().decode(value.path("data").asText());if(bytes.length!=value.path("byteLength").asLong()||!ArchiveJournalFiles.digest(bytes).equals(value.path("sha256").asText()))throw new IllegalStateException("Invalid note revision");
        String markdown=VaultContent.markdown(bytes,store.vaultLimits().markdownBytes());String body=switch(ref.type()){
            case DayNotes.TYPE->{try{yield JournalMarkdown.parseOpaque(ref.key()+".md",bytes).body();}catch(Exception invalid){throw new IllegalStateException("Invalid day revision",invalid);}}
            case EventNotes.TYPE->EventNotes.parse(ref.key(),bytes).body();
            default->markdown;
        };
        return metadata(value).put("markdown",markdown).put("body",body);
    }
}
