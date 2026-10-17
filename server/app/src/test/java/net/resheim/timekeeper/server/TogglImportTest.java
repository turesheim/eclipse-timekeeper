package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

@QuarkusTest @TestProfile(TogglImportTest.Profile.class)
class TogglImportTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:toggl_import_tests");}
    }
    @Inject ServerStore store;
    private ObjectNode map(String key,String type,String name,String parent) {
        var value=object().put("key",hash(key)).put("resourceType",type).put("action","CREATE").put("name",name).put("color","#123456");
        if(parent!=null) value.put("parentKey",hash(parent));return value;
    }
    private ArrayNode mappings() {return array().add(map("client","CLIENT","Imported client",null)).add(map("project","PROJECT","Imported project","client"));}
    private ObjectNode source(JsonNode mappings,int count,String nanos) {
        var input=object().put("label","Reviewed export").put("sourceKey",hash("member/workspace")).put("sourceZone","Europe/Oslo")
                .put("fileFingerprint",hash("file")).put("selectedEntries",count).put("selectedNanos",nanos);input.set("mappings",mappings);return input;
    }
    private ObjectNode row() {
        var value=object().put("sourceKey",hash("member/workspace")).put("start","2025-10-25T20:00:00.000000001Z").put("end","2025-10-26T03:00:00.000000003Z")
                .put("durationNanos","25200000000002").put("client","Source client").put("project","Source project").put("task","").put("summary","Independent description")
                .put("projectKey",hash("project")).put("occurrence",0);value.putArray("tags");value.putArray("labelKeys");return value;
    }
    private ObjectNode commit(TogglImport importer,UUID job,ArrayNode rows,int index,boolean finished) {
        var preview=importer.preview(job,rows);var input=object().put("finished",finished).put("reviewToken",preview.path("reviewToken").asText());input.set("rows",rows);
        return importer.batch(job,index,input);
    }
    @Test void exactTimeAcrossDstIsPreservedAndRepeatedExportsReuseEditedRecordsWithoutCreatingCatalogs() {
        String owner="toggl-time-"+UUID.randomUUID();var importer=new TogglImport(store);var ledger=new ImportLedger(store);
        var definitions=mappings().add(map("task","TASK","Task name","project")).add(map("tag","LABEL","Source tag",null));
        var row=row().put("task","Task name").put("taskKey",hash("task"));((ArrayNode)row.get("tags")).add("Source tag");((ArrayNode)row.get("labelKeys")).add(hash("tag"));var rows=array().add(row);
        UUID job=uuid(store.transaction(owner,()->{store.updateProfile("Europe/Oslo");return importer.create(source(definitions,1,row.path("durationNanos").asText()));}).get("id"));
        var request=store.transaction(owner,()->{
            var preview=importer.preview(job,rows);assertEquals(2,preview.path("dailyActivities").asInt());
            var value=object().put("finished",true).put("reviewToken",preview.path("reviewToken").asText());value.set("rows",rows);return value;
        });
        var outcome=store.transaction(owner,()->importer.batch(job,0,request));assertEquals(2,outcome.path("activityCount").asInt());assertEquals(row.path("durationNanos").asText(),outcome.path("importedNanos").asText());
        assertEquals(outcome.toString(),store.transaction(owner,()->new TogglImport(store).batch(job,0,parse(request.toString()))).toString());
        var parts=store.transaction(owner,()->store.list("ACTIVITY"));assertEquals(2,parts.size());
        Duration total=Duration.ZERO;
        for(var part:parts) {total=total.plus(Duration.between(Instant.parse(part.path("start").asText()),Instant.parse(part.path("end").asText())));assertEquals("Independent description",part.path("summary").asText());assertTrue(part.hasNonNull("taskId"));assertEquals(1,part.path("labelIds").size());}
        assertEquals(Duration.ofHours(7).plusNanos(2),total);
        store.transaction(owner,()->{var part=parts.get(0);return store.service().updateActivity(new UpdateActivity(new ActivityId(uuid(part.get("id"))),part.path("version").asLong(),Instant.parse(part.path("start").asText()),Optional.of(Instant.parse(part.path("end").asText())),"Edited later",labels(part.get("labelIds"))));});
        UUID repeat=uuid(store.transaction(owner,()->importer.create(source(definitions,1,row.path("durationNanos").asText()).put("fileFingerprint",hash("renamed overlapping file")))).get("id"));
        store.transaction(owner,()->{
            assertEquals("REUSED_EDITED",importer.preview(repeat,rows).path("rows").get(0).path("status").asText());
            var repeated=commit(importer,repeat,rows,0,true);assertEquals(1,repeated.path("skippedEntries").asInt());assertEquals("0",repeated.path("importedNanos").asText());assertEquals(0,repeated.path("activityCount").asInt());
            assertEquals(1,store.list("CLIENT").size());assertEquals(1,store.list("PROJECT").size());assertEquals(1,store.list("TASK").size());
            assertEquals(0,ledger.required(ImportLedger.Kind.IMPORT_JOB,repeat).path("catalogCount").asInt());assertEquals(2,store.list("ACTIVITY").size());return null;
        });
    }
    @Test void previewAndFailedBatchesWriteNoBusinessDataAndFinalTotalsMustReconcile() {
        String owner="toggl-rollback-"+UUID.randomUUID();var importer=new TogglImport(store);var definitions=mappings();var row=row();var rows=array().add(row);
        store.transaction(owner,()->{
            var input=source(definitions,1,row.path("durationNanos").asText());input.set("rows",rows);
            long revision=store.revision();assertEquals("NEW",importer.preview(input).path("rows").get(0).path("status").asText());
            assertEquals(revision,store.revision());assertTrue(store.list("CLIENT").isEmpty());assertTrue(store.list("IMPORT_JOB").isEmpty());return null;
        });
        UUID job=uuid(store.transaction(owner,()->importer.create(source(definitions,2,row.path("durationNanos").asText()))).get("id"));
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction(owner,()->commit(importer,job,rows,0,true))).status);
        store.transaction(owner,()->{assertTrue(store.list("ACTIVITY").isEmpty());assertTrue(store.list("CLIENT").isEmpty());assertTrue(store.list("IMPORT_ITEM").isEmpty());return null;});
        var future=row.deepCopy().put("end","2099-10-26T03:00:00Z");
        assertThrows(ApiFailure.class,()->store.transaction(owner,()->commit(importer,job,array().add(row).add(future),0,false)));
        assertEquals(0,store.transaction(owner,()->new ImportLedger(store).required(ImportLedger.Kind.IMPORT_JOB,job).path("nextBatch").asInt()));
    }
    @Test void existingUnclaimedMatchesRequireExplicitReviewAndCannotBeOverwritten() {
        String owner="toggl-matches-"+UUID.randomUUID();var importer=new TogglImport(store);var row=row();var rows=array().add(row);
        var definitions=store.transaction(owner,()->{
            var client=store.createClient(object().put("name","Existing client").put("color","#123456"));
            var project=store.withCreationClient(uuid(client.get("id")),()->store.service().createProject(new CreateProject("Existing project")));
            store.service().createActivity(new CreateActivity(null,new OwnerId(owner),Instant.parse(row.path("start").asText()),Optional.of(Instant.parse(row.path("end").asText())),row.path("summary").asText(),Set.of(),Optional.empty(),Optional.of(project.id())));
            var projectJson=store.find("PROJECT",project.id().value()).orElseThrow();
            return array().add(object().put("key",hash("client")).put("resourceType","CLIENT").put("action","REUSE").put("id",client.path("id").asText()).put("etag",etag(client)))
                    .add(object().put("key",hash("project")).put("resourceType","PROJECT").put("action","REUSE").put("id",project.id().value().toString()).put("etag",etag(projectJson)).put("parentKey",hash("client")));
        });
        UUID job=uuid(store.transaction(owner,()->importer.create(source(definitions,1,row.path("durationNanos").asText()))).get("id"));
        var review=store.transaction(owner,()->importer.preview(job,rows));assertEquals("REVIEW_MATCHES",review.path("rows").get(0).path("status").asText());
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction(owner,()->commit(importer,job,rows,0,true))).status);
        row.put("duplicateDecision","REUSE");var chosen=row.putArray("reuseActivities");for(var group:review.path("rows").get(0).path("matches")) chosen.add(group.get(0));
        var result=store.transaction(owner,()->commit(importer,job,rows,0,true));assertEquals(1,result.path("skippedEntries").asInt());
        store.transaction(owner,()->new ImportUndo(store).start(job,etag(new ImportLedger(store).required(ImportLedger.Kind.IMPORT_JOB,job))));
        for(int step=0;step<10;step++) {
            int n=step;var outcome=store.transaction(owner,()->{var preview=new ImportUndo(store).preview(job);var input=object().put("reviewToken",preview.path("reviewToken").asText());input.set("choices",object());return new ImportUndo(store).apply(job,n,input);});
            if(outcome.path("state").asText().equals("UNDONE")) break;
        }
        store.transaction(owner,()->{assertEquals(2,store.list("ACTIVITY").size());assertEquals(1,store.list("PROJECT").size());assertEquals(1,store.list("CLIENT").size());return null;});
    }
    @Test void stableIdsCannotSilentlyChangeAndMixedSourcesOrOversizedSpansAreRejected() {
        String owner="toggl-validation-"+UUID.randomUUID();var importer=new TogglImport(store);var row=row().put("sourceId","source-42");var rows=array().add(row);
        UUID job=uuid(store.transaction(owner,()->importer.create(source(mappings(),1,row.path("durationNanos").asText()))).get("id"));
        store.transaction(owner,()->commit(importer,job,rows,0,true));
        UUID next=uuid(store.transaction(owner,()->importer.create(source(mappings(),1,row.path("durationNanos").asText()))).get("id"));
        var changed=array().add(row.deepCopy().put("summary","Changed source description"));
        assertEquals("SOURCE_CHANGED",store.transaction(owner,()->importer.preview(next,changed)).path("rows").get(0).path("status").asText());
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction(owner,()->commit(importer,next,changed,0,true))).status);
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction(owner,()->importer.preview(next,array().add(row.deepCopy().put("sourceKey",hash("another person")))))).status);
        var huge=row.deepCopy().put("start","2000-01-01T00:00:00Z").put("end","2020-01-01T00:00:00Z").put("durationNanos","631152000000000000");
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction(owner,()->importer.preview(next,array().add(huge)))).status);
        store.transaction(owner,()->{store.updateProfile("Europe/Oslo");return null;});
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction(owner,()->importer.preview(next,rows))).status);
    }
    @Test void undoReleasesOnlyItsOriginalClaimAndCannotDeleteAReplacementImportedAfterExternalRemoval() {
        String owner="toggl-replacement-"+UUID.randomUUID();var importer=new TogglImport(store);var ledger=new ImportLedger(store);var row=row();var rows=array().add(row);
        UUID old=uuid(store.transaction(owner,()->importer.create(source(mappings(),1,row.path("durationNanos").asText()))).get("id"));
        store.transaction(owner,()->commit(importer,old,rows,0,true));
        store.transaction(owner,()->{
            for(var value:store.list("ACTIVITY")) store.service().deleteActivity(new DeleteActivity(new ActivityId(uuid(value.get("id"))),value.path("version").asLong()));return null;
        });
        UUID replacement=uuid(store.transaction(owner,()->importer.create(source(mappings(),1,row.path("durationNanos").asText()))).get("id"));
        store.transaction(owner,()->commit(importer,replacement,rows,0,true));
        var fingerprint=store.transaction(owner,()->ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(replacement,2)).path("fingerprint").asText());
        store.transaction(owner,()->new ImportUndo(store).start(old,etag(ledger.required(ImportLedger.Kind.IMPORT_JOB,old))));
        for(int step=0;step<10;step++) {
            int n=step;
            var result=store.transaction(owner,()->{
                var preview=new ImportUndo(store).preview(old);var input=object().put("reviewToken",preview.path("reviewToken").asText());input.set("choices",object());
                return new ImportUndo(store).apply(old,n,input);
            });
            if(result.path("state").asText().startsWith("UNDONE")) break;
        }
        store.transaction(owner,()->{
            assertEquals(replacement.toString(),new ImportProvenance(store).active(ledger.required(ImportLedger.Kind.IMPORT_JOB,replacement),fingerprint,0).orElseThrow().path("jobId").asText());
            assertEquals(2,store.list("ACTIVITY").size());return null;
        });
    }
}
