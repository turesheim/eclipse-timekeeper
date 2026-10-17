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
        store.transaction(owner,()->{
            var audit=ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(job,4));
            assertEquals(row.path("durationNanos").asText(),audit.path("sourceNanos").asText());assertEquals("IMPORTED",audit.path("outcome").asText());
            assertEquals(row.path("start").asText(),audit.path("sourceStart").asText());return null;
        });
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
    private ObjectNode sessionSource() {
        return source(array().add(map("client","CLIENT","Session client",null)),1,row().path("durationNanos").asText()).put("mode","WORK_SESSION");
    }
    private ObjectNode sessionRow() {
        var value=row().put("clientKey",hash("client")).put("title","Imported attendance");value.remove(List.of("projectKey","labelKeys"));return value;
    }
    private void undoAll(UUID job,String owner) {
        store.transaction(owner,()->new ImportUndo(store).start(job,etag(new ImportLedger(store).required(ImportLedger.Kind.IMPORT_JOB,job))));
        for(int step=0;step<20;step++) {
            int index=step;
            var result=store.transaction(owner,()->{var preview=new ImportUndo(store).preview(job);var input=object().put("reviewToken",preview.path("reviewToken").asText());input.set("choices",object());return new ImportUndo(store).apply(job,index,input);});
            if(result.path("state").asText().startsWith("UNDONE")) return;
        }
        fail("Undo did not finish");
    }
    @Test void sessionsPreserveOneContinuousDstIntervalAndReuseWithoutCompanionRecordsThenUndoAndReimport() {
        String owner="toggl-sessions-"+UUID.randomUUID();int labelCount=store.transaction(owner,()->store.list("LABEL").size());var importer=new TogglImport(store);var rows=array().add(sessionRow());
        UUID job=uuid(store.transaction(owner,()->{store.updateProfile("Europe/Oslo");return importer.create(sessionSource());}).get("id"));
        var request=store.transaction(owner,()->{var preview=importer.preview(job,rows);assertEquals(1,preview.path("workSessions").asInt());var body=object().put("finished",true).put("reviewToken",preview.path("reviewToken").asText());body.set("rows",rows);return body;});
        var receipt=store.transaction(owner,()->importer.batch(job,0,request));assertEquals(1,receipt.path("workSessionCount").asInt());assertEquals(1,receipt.path("rows").get(0).path("workSessionIds").size());
        assertEquals(receipt.toString(),store.transaction(owner,()->importer.batch(job,0,request)).toString());
        store.transaction(owner,()->{
            var session=store.list("WORK_SESSION").get(0);assertEquals(sessionRow().path("start").asText(),session.path("start").asText());assertEquals(sessionRow().path("end").asText(),session.path("end").asText());
            assertEquals("Imported attendance",session.path("title").asText());assertEquals("Independent description",session.path("summary").asText());assertEquals(store.list("CLIENT").get(0).path("id"),session.path("clientId"));
            for(String type:List.of("PROJECT","TASK","ACTIVITY")) assertTrue(store.list(type).isEmpty());assertEquals(labelCount,store.list("LABEL").size());return null;
        });
        UUID repeat=uuid(store.transaction(owner,()->importer.create(sessionSource())).get("id"));
        store.transaction(owner,()->{assertEquals("REUSED",importer.preview(repeat,rows).path("rows").get(0).path("status").asText());assertEquals(1,commit(importer,repeat,rows,0,true).path("skippedEntries").asInt());return null;});
        assertEquals(404,assertThrows(ApiFailure.class,()->store.transaction("other-"+owner,()->importer.preview(job,rows))).status);
        // A later job's reused session protects the original import from automatic deletion.
        store.transaction(owner,()->{new ImportUndo(store).start(job,etag(new ImportLedger(store).required(ImportLedger.Kind.IMPORT_JOB,job)));assertEquals("REFERENCED",new ImportUndo(store).preview(job).path("targets").get(0).path("status").asText());return null;});
        undoAll(repeat,owner);
        for(int step=0;step<10;step++) {int index=step;var result=store.transaction(owner,()->{var preview=new ImportUndo(store).preview(job);var input=object().put("reviewToken",preview.path("reviewToken").asText());input.set("choices",object());return new ImportUndo(store).apply(job,index,input);});if(result.path("state").asText().equals("UNDONE")) break;}
        store.transaction(owner,()->{assertTrue(store.list("WORK_SESSION").isEmpty());assertTrue(store.list("CLIENT").isEmpty());return null;});
        UUID fresh=uuid(store.transaction(owner,()->importer.create(sessionSource())).get("id"));assertEquals(1,store.transaction(owner,()->commit(importer,fresh,rows,0,true)).path("importedEntries").asInt());
    }
    @Test void sessionEditsRequireUndoReviewAndImportModesHaveIndependentClaims() {
        String owner="toggl-session-edits-"+UUID.randomUUID();var importer=new TogglImport(store);var rows=array().add(sessionRow());
        UUID job=uuid(store.transaction(owner,()->importer.create(sessionSource())).get("id"));store.transaction(owner,()->commit(importer,job,rows,0,true));
        store.transaction(owner,()->{var session=store.list("WORK_SESSION").get(0);store.workSessionService().updateWorkSession(new UpdateWorkSession(new WorkSessionId(uuid(session.get("id"))),session.path("version").asLong(),Instant.parse(session.path("start").asText()),Optional.of(Instant.parse(session.path("end").asText())),"Edited session"));return null;});
        UUID repeat=uuid(store.transaction(owner,()->importer.create(sessionSource())).get("id"));
        assertEquals("REUSED_EDITED",store.transaction(owner,()->importer.preview(repeat,rows)).path("rows").get(0).path("status").asText());
        // The same source interval can deliberately be imported as an activity, too.
        UUID activity=uuid(store.transaction(owner,()->importer.create(source(mappings(),1,row().path("durationNanos").asText()))).get("id"));
        assertEquals(1,store.transaction(owner,()->commit(importer,activity,array().add(row()),0,true)).path("importedEntries").asInt());
        store.transaction(owner,()->{new ImportUndo(store).start(job,etag(new ImportLedger(store).required(ImportLedger.Kind.IMPORT_JOB,job)));var preview=new ImportUndo(store).preview(job);assertEquals("REVIEW",preview.path("targets").get(0).path("status").asText());return null;});
        store.transaction(owner,()->{var preview=new ImportUndo(store).preview(job);var input=object().put("reviewToken",preview.path("reviewToken").asText());var choices=input.putObject("choices");choices.put(preview.path("targets").get(0).path("key").asText(),"KEEP");new ImportUndo(store).apply(job,0,input);return null;});
        store.transaction(owner,()->{assertEquals(1,store.list("WORK_SESSION").size());assertFalse(store.list("ACTIVITY").isEmpty());return null;});
    }
    @Test void sessionMatchesRequireExplicitChoiceAndCannotReuseForeignOrStaleSessions() {
        String owner="toggl-session-matches-"+UUID.randomUUID();var importer=new TogglImport(store);var rows=array().add(sessionRow());
        var definitions=store.transaction(owner,()->{
            var client=store.createClient(object().put("name","Existing session client").put("color","#123456"));
            var session=store.withCreationClient(uuid(client.get("id")),()->store.workSessionService().createWorkSession(new CreateWorkSession(new OwnerId(owner),Instant.parse(sessionRow().path("start").asText()),Optional.of(Instant.parse(sessionRow().path("end").asText())),sessionRow().path("summary").asText())));
            store.clientDetails("WORK_SESSION",session.id().value(),object().put("title",sessionRow().path("title").asText()));
            return array().add(object().put("key",hash("client")).put("resourceType","CLIENT").put("action","REUSE").put("id",client.path("id").asText()).put("etag",etag(client)));
        });
        var input=sessionSource();input.set("mappings",definitions);UUID job=uuid(store.transaction(owner,()->importer.create(input)).get("id"));
        var preview=store.transaction(owner,()->importer.preview(job,rows));assertEquals("REVIEW_MATCHES",preview.path("rows").get(0).path("status").asText());
        assertEquals(409,assertThrows(ApiFailure.class,()->store.transaction(owner,()->commit(importer,job,rows,0,true))).status);
        ((ObjectNode)rows.get(0)).put("duplicateDecision","REUSE").putArray("reuseWorkSessions").add(preview.path("rows").get(0).path("matches").get(0).get(0));
        assertEquals(1,store.transaction(owner,()->commit(importer,job,rows,0,true)).path("skippedEntries").asInt());undoAll(job,owner);
        assertEquals(1,store.transaction(owner,()->store.list("WORK_SESSION").size()));
        assertEquals(422,assertThrows(ApiFailure.class,()->store.transaction(owner,()->importer.create(source(mappings(),1,"0").put("mode","WORK_SESSION")))).status);
    }

    @Test void workSessionTagsUseReviewedOwnedLabelsAndUndoRemovesOnlyUnusedImportCatalogs() {
        String owner="toggl-session-labels-"+UUID.randomUUID();var importer=new TogglImport(store);
        int originalLabels=store.transaction(owner,()->store.list("LABEL").size());
        var input=sessionSource();((ArrayNode)input.get("mappings")).add(map("tag","LABEL","Session tag",null));
        var row=sessionRow();row.putArray("tags").add("Session tag");row.putArray("labelKeys").add(hash("tag"));var rows=array().add(row);
        UUID job=uuid(store.transaction(owner,()->importer.create(input)).get("id"));
        store.transaction(owner,()->commit(importer,job,rows,0,true));
        var session=store.transaction(owner,()->store.list("WORK_SESSION").get(0));assertEquals(1,session.path("labelIds").size());
        UUID label=uuid(session.path("labelIds").get(0));
        store.transaction(owner,()->{assertTrue(store.related("label","LABEL",label));assertEquals("Session tag",store.find("LABEL",label).orElseThrow().path("name").asText());return null;});
        UUID repeat=uuid(store.transaction(owner,()->importer.create(input)).get("id"));
        assertEquals("REUSED",store.transaction(owner,()->importer.preview(repeat,rows)).path("rows").get(0).path("status").asText());
        store.transaction(owner,()->commit(importer,repeat,rows,0,true));undoAll(repeat,owner);undoAll(job,owner);
        store.transaction(owner,()->{assertTrue(store.list("WORK_SESSION").isEmpty());assertFalse(store.find("LABEL",label).isPresent());assertEquals(originalLabels,store.list("LABEL").size());return null;});
    }

    @Test void undoKeepsAnImportLabelUsedByAnIndependentManualWorkSession() {
        String owner="toggl-session-label-dependency-"+UUID.randomUUID();var importer=new TogglImport(store);
        var input=sessionSource();((ArrayNode)input.get("mappings")).add(map("tag","LABEL","Retained session tag",null));
        var row=sessionRow();row.putArray("tags").add("Retained session tag");row.putArray("labelKeys").add(hash("tag"));var rows=array().add(row);
        UUID job=uuid(store.transaction(owner,()->importer.create(input)).get("id"));store.transaction(owner,()->commit(importer,job,rows,0,true));
        var manual=store.transaction(owner,()->{
            var imported=store.list("WORK_SESSION").get(0);
            var value=store.workSessionService().createWorkSession(new CreateWorkSession(new OwnerId(owner),Instant.parse("2025-10-01T08:00:00Z"),Optional.of(Instant.parse("2025-10-01T09:00:00Z")),"Unrelated manual session"));
            var details=object();details.set("labelIds",imported.path("labelIds").deepCopy());return store.clientDetails("WORK_SESSION",value.id().value(),details);
        });
        store.transaction(owner,()->new ImportUndo(store).start(job,etag(new ImportLedger(store).required(ImportLedger.Kind.IMPORT_JOB,job))));
        boolean sawDependency=false;
        for(int step=0;step<10;step++) {
            int index=step;var preview=store.transaction(owner,()->new ImportUndo(store).preview(job));var choices=object();
            for(var target:preview.path("targets")) if(target.path("status").asText().equals("DEPENDENCY")) {
                assertEquals("LABEL",target.path("recordType").asText());sawDependency=true;choices.put(target.path("key").asText(),"KEEP");
            }
            var request=object().put("reviewToken",preview.path("reviewToken").asText());request.set("choices",choices);
            var result=store.transaction(owner,()->new ImportUndo(store).apply(job,index,request));if(result.path("state").asText().startsWith("UNDONE")) break;
        }
        assertTrue(sawDependency);store.transaction(owner,()->{assertEquals(List.of(manual),store.list("WORK_SESSION"));assertTrue(store.find("LABEL",uuid(manual.path("labelIds").get(0))).isPresent());return null;});
    }

}
