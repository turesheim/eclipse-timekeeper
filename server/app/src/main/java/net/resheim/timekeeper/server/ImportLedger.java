package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Private import authority in existing owned storage; never a portable domain collection. */
final class ImportLedger {
    enum Kind { IMPORT_JOB, IMPORT_BATCH, IMPORT_ITEM, IMPORT_MAP, IMPORT_KEY, IMPORT_ORIGIN, IMPORT_UNDO, IMPORT_USE }
    private final ServerStore store;
    ImportLedger(ServerStore store) {this.store=Objects.requireNonNull(store);}

    static UUID identifier(String namespace,String... parts) {
        var input=array().add(namespace);for(var part:parts) input.add(part);
        byte[] digest=HexFormat.of().parseHex(hash(input.toString()));
        // An opaque UUID-shaped lookup key. Keep full fingerprints in authority records too.
        digest[6]=(byte)((digest[6]&0x0f)|0x80);digest[8]=(byte)((digest[8]&0x3f)|0x80);
        var bytes=ByteBuffer.wrap(digest);return new UUID(bytes.getLong(),bytes.getLong());
    }
    Optional<ObjectNode> find(Kind kind,UUID id) {
        return store.find(kind.name(),id).map(value->((ObjectNode)value).deepCopy());
    }
    ObjectNode required(Kind kind,UUID id) {return find(kind,id).orElseThrow(ApiFailure::notFound);}
    ObjectNode insert(Kind kind,UUID id,ObjectNode fields) {
        var value=fields.deepCopy();value.put("id",id.toString());value.put("ownerId",store.owner());value.put("version",0);
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,?,?)",store.owner(),kind.name(),id,0,value.toString());
        return value;
    }
    ObjectNode update(Kind kind,ObjectNode value) {
        if(!store.owner().equals(value.path("ownerId").asText())) throw ApiFailure.notFound();
        long previous=value.get("version").asLong();var saved=value.deepCopy();saved.put("version",Math.addExact(previous,1));
        if(store.update("UPDATE tk_resource SET version=?,payload=? WHERE owner_id=? AND entity_type=? AND id=? AND version=?",previous+1,saved.toString(),store.owner(),kind.name(),uuid(value.get("id")),previous)!=1)
            throw new ApiFailure(412,"STALE_VERSION");
        return saved;
    }
    void remove(Kind kind,ObjectNode value) {
        if(!store.owner().equals(value.path("ownerId").asText())) throw ApiFailure.notFound();
        if(store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=? AND version=?",store.owner(),kind.name(),uuid(value.get("id")),value.get("version").asLong())!=1)
            throw new ApiFailure(412,"STALE_VERSION");
    }

    ObjectNode create(String label,String sourceKey,String sourceZone,String fileFingerprint,String planFingerprint) {
        if(label==null || label.isBlank() || label.length()>255 || !digest(sourceKey) || !digest(fileFingerprint) || !digest(planFingerprint)) throw ApiFailure.validation();
        ContractInputs.zone(sourceZone);
        var value=object();value.put("kind","TOGGL_TRACK");value.put("label",label);value.put("sourceKey",sourceKey);
        value.put("sourceZone",sourceZone);value.put("fileFingerprint",fileFingerprint);value.put("planFingerprint",planFingerprint);
        value.put("reportZone",store.profile().get("zoneId").asText());value.put("state","IMPORTING");
        value.put("createdAt",store.now().toString());value.put("updatedAt",store.now().toString());
        for(var key:List.of("nextBatch","itemCount","catalogCount","importedEntries","skippedEntries","activityCount","undoNextBatch","undoItemCursor","undoCatalogCursor")) value.put(key,0);
        value.put("importedNanos","0");value.put("undoPhase","ACTIVITIES");
        return insert(Kind.IMPORT_JOB,UUID.randomUUID(),value);
    }
    private static boolean digest(String value) {return value!=null && value.matches("[0-9a-f]{64}");}

    /** Caller owns the authenticated owner transaction, so work/receipt/job advancement are atomic. */
    ObjectNode batch(UUID id,int index,JsonNode input,boolean finished,Function<ObjectNode,ObjectNode> work) {
        return execute(id,index,input,finished,false,work);
    }
    ObjectNode undoBatch(UUID id,int index,JsonNode input,Function<ObjectNode,ObjectNode> work) {
        return execute(id,index,input,false,true,work);
    }
    private ObjectNode execute(UUID id,int index,JsonNode input,boolean finished,boolean undo,Function<ObjectNode,ObjectNode> work) {
        // Undo counts daily records, not source rows: a source entry can produce 1,000 parts.
        if(index<0 || index>(undo ? Integer.MAX_VALUE-1 : 100000)) throw ApiFailure.validation();
        var job=required(Kind.IMPORT_JOB,id);var type=undo ? Kind.IMPORT_UNDO : Kind.IMPORT_BATCH;
        UUID batchId=identifier(type.name(),id.toString(),Integer.toString(index));
        String fingerprint=hash(type.name()+"\n"+id+"\n"+index+"\n"+canonical(input)+"\n"+finished);
        var prior=find(type,batchId);
        if(prior.isPresent()) {
            if(!prior.get().path("jobId").asText().equals(id.toString()) || !fingerprint.equals(prior.get().path("fingerprint").asText()))
                throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED");
            return ((ObjectNode)prior.get().get("outcome")).deepCopy();
        }
        String counter=undo ? "undoNextBatch" : "nextBatch";
        if(!(undo ? "UNDOING" : "IMPORTING").equals(job.path("state").asText()) || index!=job.path(counter).asInt())
            throw new ApiFailure(409,"CONFLICT");
        if(!undo && !job.path("reportZone").asText().equals(store.profile().path("zoneId").asText()))
            throw new ApiFailure(409,"CONFLICT");
        var outcome=Objects.requireNonNull(work.apply(job));
        job.put(counter,index+1);job.put("updatedAt",store.now().toString());
        if(!undo && finished) job.put("state","IMPORTED");
        var saved=update(Kind.IMPORT_JOB,job);outcome.put("jobId",id.toString());outcome.put("batch",index);
        outcome.put("jobVersion",saved.path("version").asLong());outcome.put("state",saved.path("state").asText());
        var receipt=object();receipt.put("jobId",id.toString());receipt.put("fingerprint",fingerprint);receipt.set("outcome",outcome);
        insert(type,batchId,receipt);return outcome.deepCopy();
    }
    ObjectNode beginUndo(UUID id,String match) {
        var job=required(Kind.IMPORT_JOB,id);ApiReplies.match(job,match);
        if(!Set.of("IMPORTING","IMPORTED","BLOCKED").contains(job.path("state").asText())) throw ApiFailure.conflict();
        job.put("state","UNDOING");job.put("updatedAt",store.now().toString());return update(Kind.IMPORT_JOB,job);
    }
    UUID itemId(UUID job,int index) {return identifier("item",job.toString(),Integer.toString(index));}
    ObjectNode appendItem(ObjectNode job,ObjectNode item) {
        if(!store.owner().equals(job.path("ownerId").asText())) throw ApiFailure.notFound();
        int index=job.path("itemCount").asInt();var fields=item.deepCopy();fields.put("jobId",job.path("id").asText());fields.put("index",index);
        var saved=insert(Kind.IMPORT_ITEM,itemId(uuid(job.get("id")),index),fields);job.put("itemCount",index+1);return saved;
    }
}
