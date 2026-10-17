package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.sql.SQLException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Reviewed additive restores. Source account and import authority are never installed. */
final class AccountArchiveImport {
    private static final String PATH="/data/account/import";
    private final ServerStore store;
    private final ContractInputs inputs;
    private final CommandReceipts receipts;
    private final AccountUploads uploads;
    AccountArchiveImport(ServerStore store,ContractInputs inputs,CommandReceipts receipts) {
        this.store=store;this.inputs=inputs;this.receipts=receipts;this.uploads=new AccountUploads(store);
    }
    private ObjectNode document(UUID id,ObjectNode upload) {
        try(var file=uploads.materialize(id)) {
            var parsed=AccountArchiveInput.readVerified(file.file(),upload.path("mediaType").asText());var value=parsed.data();
            for(String name:DomainTransfer.TYPES.keySet()) if(value.path("work").path(name).size()>250000) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            if(value.path("importHistory").size()>1000000) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            inputs.validate("AccountArchiveSnapshot",value);
            upload.put("verifiedArchiveId",value.path("archiveId").asText()).put("verifiedArchiveDigest",parsed.sha256());return value;
        }
    }
    private static ObjectNode intent(ObjectNode upload) {return object().put("archiveId",upload.path("verifiedArchiveId").asText()).put("documentSha256",upload.path("verifiedArchiveDigest").asText());}
    private static String key(ObjectNode upload) {if(!upload.hasNonNull("verifiedArchiveId") || !upload.hasNonNull("verifiedArchiveDigest")) throw ApiFailure.conflict();return ImportLedger.identifier("account-archive-import",upload.path("verifiedArchiveId").asText()).toString();}
    private Optional<ApiReplies> prior(ObjectNode upload) {if(!upload.hasNonNull("verifiedArchiveId")) return Optional.empty();return receipts.find(key(upload),"POST",PATH,null,intent(upload));}
    ApiReplies preview(UUID id,boolean restoreZone) {
        var upload=uploads.get(id);var previous=prior(upload);
        if(previous.isPresent()) {
            completed(upload,previous.get().body());
            var summary=((ObjectNode)previous.get().body()).deepCopy().put("alreadyImported",true).put("canImport",false);
            return new ApiReplies(200,summary,null,null);
        }
        var archive=document(id,upload);previous=prior(upload);
        if(previous.isPresent()) {
            completed(upload,previous.get().body());
            return new ApiReplies(200,((ObjectNode)previous.get().body()).deepCopy().put("alreadyImported",true).put("canImport",false),null,null);
        }
        var result=object().put("archiveId",archive.path("archiveId").asText()).put("generatedAt",archive.path("generatedAt").asText());
        result.set("counts",AccountArchiveInput.counts(archive));
        result.set("sourceAccount",object().set("username",archive.path("account").path("username")));
        ((ObjectNode)result.get("sourceAccount")).set("displayName",archive.path("account").path("displayName"));
        result.put("sourceTimeZone",archive.path("preferences").path("zoneId").asText());result.put("currentTimeZone",store.profile().path("zoneId").asText());
        result.put("restoreTimeZone",restoreZone);result.put("alreadyImported",false);
        long revision=store.revision(),profileVersion=store.profile().path("version").asLong();
        int openActivities=running(archive.path("work").path("activities")),openSessions=running(archive.path("work").path("workSessions"));
        if(openActivities>1 || openSessions>1) throw ApiFailure.validation();
        result.put("runningActivities",openActivities).put("runningWorkSessions",openSessions);
        var conflicts=result.putArray("conflicts");
        if(openActivities>0 && store.active().isPresent()) conflicts.add("Stop your current activity timer before restoring this running activity.");
        if(openSessions>0 && store.workSessionService().activeWorkSession(new net.resheim.eclipse.timekeeper.domain.OwnerId(store.owner())).isPresent()) conflicts.add("Stop your current work session before restoring this running work session.");
        // Exercise the complete owned graph/domain constraints without publishing
        // a single change. This also detects existing external-reference conflicts.
        if(conflicts.isEmpty()) {
            try {
                var c=store.current().connection();var savepoint=c.setSavepoint();
                try {restoreZone(archive,restoreZone);new DomainTransfer(store).importRecords(archive.path("work"));}
                catch(ApiFailure invalid) {if(invalid.status!=409) throw invalid;conflicts.add("Existing work records conflict with this archive. Resolve the conflict before importing.");}
                finally {c.rollback(savepoint);c.releaseSavepoint(savepoint);}
            }catch(SQLException unavailable) {throw new ApiFailure(503,"UNAVAILABLE");}
        }
        result.put("canImport",conflicts.isEmpty());
        var reviewed=object().put("revision",revision).put("profileVersion",profileVersion).put("restoreTimeZone",restoreZone);
        reviewed.set("summary",result.deepCopy());upload.set("review",reviewed);upload.put("state","REVIEWED");var saved=uploads.update(upload);
        return new ApiReplies(200,result,etag(saved),null);
    }
    ApiReplies apply(UUID id,boolean restoreZone,String match) {
        var upload=uploads.get(id);var previous=prior(upload);
        if(previous.isPresent()) {completed(upload,previous.get().body());return previous.get();}
        return receipts.execute(key(upload),"POST",PATH,null,intent(upload),()->{
            ApiReplies.match(upload,match);var review=upload.path("review");
            if(!upload.path("state").asText().equals("REVIEWED") || !review.path("summary").path("canImport").asBoolean()
                    || review.path("restoreTimeZone").asBoolean()!=restoreZone) throw ApiFailure.conflict();
            if(review.path("revision").asLong(-1)!=store.revision() || review.path("profileVersion").asLong(-1)!=store.profile().path("version").asLong()) throw new ApiFailure(412,"STALE_VERSION");
            var archive=document(id,upload);restoreZone(archive,restoreZone);new DomainTransfer(store).importRecords(archive.path("work"));
            // Keep old import ledgers as inert audit copies. Their replay keys,
            // mappings and Undo versions are never effective in this account.
            for(var audit:archive.path("importHistory")) {
                var fields=object().put("sourceArchiveId",archive.path("archiveId").asText()).put("sourceGeneratedAt",archive.path("generatedAt").asText()).put("entityType",audit.path("entityType").asText());
                fields.set("data",audit.path("data").deepCopy());insert("ARCHIVE_AUDIT",UUID.randomUUID(),fields);
            }
            UUID importId=UUID.fromString(key(upload));
            var result=object().put("importId",importId.toString()).put("archiveId",archive.path("archiveId").asText()).put("importedAt",store.now().toString())
                    .put("alreadyImported",false).put("restoreTimeZone",restoreZone).put("timeZone",store.profile().path("zoneId").asText()).put("revision",store.revision());
            result.set("counts",AccountArchiveInput.counts(archive));insert("ARCHIVE_IMPORT",importId,result);
            completed(upload,result);return new ApiReplies(201,result,null,null);
        });
    }
    private void completed(ObjectNode upload,JsonNode result) {
        if(upload.path("state").asText().equals("IMPORTED")) return;
        uploads.deleteChunks(upload);upload.put("state","IMPORTED");upload.remove("review");upload.set("outcome",result.deepCopy());uploads.update(upload);
    }
    private void restoreZone(JsonNode archive,boolean restoreZone) {
        String zone=archive.path("preferences").path("zoneId").asText();
        if(restoreZone && !zone.equals(store.profile().path("zoneId").asText())) store.updateProfile(zone);
    }
    private static int running(JsonNode values) {int count=0;for(var value:values) if(value.path("end").isNull()) count++;return count;}
    private void insert(String type,UUID id,ObjectNode fields) {
        var value=fields.deepCopy().put("id",id.toString()).put("ownerId",store.owner()).put("version",0);
        store.update("INSERT INTO tk_resource(owner_id,entity_type,id,version,payload) VALUES(?,?,?,0,?)",store.owner(),type,id,value.toString());
    }
}
