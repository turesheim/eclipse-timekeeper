package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Bounded whole-job undo. Every preview and commit runs in the authenticated owner transaction. */
final class ImportUndo {
    private static final int MAX_TARGETS=100, MAX_ITEMS=200;
    private final ServerStore store;
    private final ImportLedger ledger;
    ImportUndo(ServerStore store) {this.store=store;ledger=new ImportLedger(store);}

    ObjectNode start(UUID id,String match) {
        var job=ledger.beginUndo(id,match);
        job.put("undoPartCursor",0);job.put("undoRemoved",0);job.put("undoRetained",0);job.put("undoPreserved",0);job.put("undoMissing",0);
        return ledger.update(ImportLedger.Kind.IMPORT_JOB,job);
    }
    ObjectNode preview(UUID id) {return plan(ledger.required(ImportLedger.Kind.IMPORT_JOB,id));}

    private ObjectNode plan(ObjectNode job) {
        if(!"UNDOING".equals(job.path("state").asText())) throw ApiFailure.conflict();
        UUID id=uuid(job.get("id"));String phase=job.path("undoPhase").asText();
        int cursor=job.path("undoItemCursor").asInt(),partCursor=job.path("undoPartCursor").asInt();
        int count=job.path("itemCount").asInt(),visited=0;
        var result=object().put("jobId",id.toString()).put("jobVersion",job.path("version").asLong()).put("phase",phase);
        var targets=result.putArray("targets");
        while(visited<MAX_ITEMS && targets.size()<MAX_TARGETS && cursor>=0 && cursor<count) {
            var item=ledger.required(ImportLedger.Kind.IMPORT_ITEM,ledger.itemId(id,cursor));visited++;
            boolean entry="ENTRY".equals(item.path("kind").asText());
            if(phase.equals("ACTIVITIES") && entry) {
                var parts=item.path("parts");
                while(partCursor<parts.size() && targets.size()<MAX_TARGETS) {
                    var part=parts.get(partCursor);
                    targets.add(target(job,item,item.path("recordType").asText("ACTIVITY"),uuid(part.get("id")),part,partCursor));partCursor++;
                }
                if(partCursor<parts.size()) break;
            } else if(phase.equals("CATALOG") && !entry) {
                // Commit children before assessing their parents' dependencies in a later step.
                if(!targets.isEmpty() && !targets.get(0).path("recordType").asText().equals(item.path("recordType").asText())) break;
                targets.add(target(job,item,item.path("recordType").asText(),uuid(item.get("recordId")),item,-1));
            }
            partCursor=0;cursor+=phase.equals("ACTIVITIES") ? 1 : -1;
        }
        if(phase.equals("ACTIVITIES") && cursor>=count) {phase="CATALOG";cursor=count-1;partCursor=0;}
        boolean finished=phase.equals("CATALOG") && cursor<0;
        result.put("nextPhase",phase);result.put("nextItemCursor",cursor);result.put("nextPartCursor",partCursor);result.put("finished",finished);
        result.put("reviewToken",hash(canonical(result).toString()));return result;
    }
    private ObjectNode target(ObjectNode job,ObjectNode item,String type,UUID id,JsonNode baseline,int partIndex) {
        String key=item.path("index").asInt()+":"+partIndex;
        var target=object().put("key",key).put("itemId",item.path("id").asText()).put("partIndex",partIndex)
                .put("recordType",type).put("recordId",id.toString());
        var current=store.find(type,id);
        String status;
        if(!item.path("created").asBoolean()) status="REUSED";
        else if(current.isEmpty()) status="MISSING";
        else if(new ImportReferences(store).usedByAnotherImport(type,id,uuid(job.get("id")))) status="REFERENCED";
        else if(entryType(type) && !current.get().hasNonNull("end")) status="RUNNING";
        else if(!entryType(type) && dependent(type,id)) status="DEPENDENCY";
        else {
            String expected=entryType(type) ? baseline.path("baselineHash").asText() : hash(canonical(baseline.get("baseline")).toString());
            status=baseline.path("baselineVersion").asLong(-1)==current.get().path("version").asLong()
                    && expected.equals(hash(canonical(current.get()).toString())) ? "DELETE" : "REVIEW";
        }
        target.put("status",status);
        current.ifPresent(value->{target.put("etag",etag(value));target.set("current",value);});
        return target;
    }
    private static boolean entryType(String type) {return type.equals("ACTIVITY") || type.equals("WORK_SESSION");}
    private boolean dependent(String type,UUID id) {
        // Relations are owner-scoped and maintained by the normal domain repositories.
        return switch(type) {
            case "CLIENT" -> store.related("client",type,id);
            case "PROJECT" -> store.related("project",type,id);
            case "TASK" -> store.related("task",type,id) || store.related("parent",type,id);
            case "LABEL" -> store.related("label",type,id);
            default -> throw ApiFailure.validation();
        };
    }
    ObjectNode apply(UUID id,int index,JsonNode input) {
        if(input==null || !input.isObject() || !input.path("choices").isObject() || input.path("choices").size()>MAX_TARGETS)
            throw ApiFailure.validation();
        return ledger.undoBatch(id,index,input,job->{
            var plan=plan(job);
            if(!plan.path("reviewToken").asText().equals(input.path("reviewToken").asText())) throw new ApiFailure(412,"STALE_VERSION");
            var choices=input.path("choices");var keys=new HashSet<String>();
            var result=object();var outcomes=result.putArray("outcomes");
            for(var target:plan.path("targets")) {
                String key=target.path("key").asText(),status=target.path("status").asText();keys.add(key);
                String choice=choices.path(key).asText("");
                if(!choice.isEmpty() && !Set.of("KEEP","DELETE").contains(choice)) throw ApiFailure.validation();
                boolean missing=status.equals("MISSING"),reused=status.equals("REUSED");
                boolean removable=status.equals("DELETE") || status.equals("REVIEW");
                if(!missing && !reused && !status.equals("DELETE") && choice.isEmpty()) throw new ApiFailure(409,"IMPORT_UNDO_REVIEW_REQUIRED");
                if(choice.equals("DELETE") && !removable && !missing) throw new ApiFailure(409,"IMPORT_UNDO_REVIEW_REQUIRED");
                String outcome=missing ? "MISSING" : reused ? "PRESERVED" : choice.equals("KEEP") ? "RETAINED" : "REMOVED";
                if(outcome.equals("REMOVED")) remove(target);
                String counter=switch(outcome) {case "REMOVED" -> "undoRemoved";case "MISSING" -> "undoMissing";case "PRESERVED" -> "undoPreserved";default -> "undoRetained";};
                job.put(counter,job.path(counter).asLong()+1);
                var item=ledger.required(ImportLedger.Kind.IMPORT_ITEM,uuid(target.get("itemId")));
                if(target.path("partIndex").asInt()>=0) ((ObjectNode)item.path("parts").get(target.path("partIndex").asInt())).put("undoOutcome",outcome);
                else item.put("undoOutcome",outcome);
                ledger.update(ImportLedger.Kind.IMPORT_ITEM,item);
                if("ENTRY".equals(item.path("kind").asText())) new ImportProvenance(store).releaseIfMissing(job,item);
                outcomes.add(object().put("key",key).put("outcome",outcome));
            }
            var names=choices.fieldNames();while(names.hasNext()) if(!keys.contains(names.next())) throw ApiFailure.validation();
            job.put("undoPhase",plan.path("nextPhase").asText());job.put("undoItemCursor",plan.path("nextItemCursor").asInt());job.put("undoPartCursor",plan.path("nextPartCursor").asInt());
            if(plan.path("finished").asBoolean()) job.put("state",job.path("undoRetained").asLong()>0 ? "UNDONE_PARTIAL" : "UNDONE");
            result.put("removed",job.path("undoRemoved").asLong());result.put("retained",job.path("undoRetained").asLong());result.put("preserved",job.path("undoPreserved").asLong());result.put("missing",job.path("undoMissing").asLong());
            return result;
        });
    }
    private void remove(JsonNode target) {
        String type=target.path("recordType").asText();UUID id=uuid(target.get("recordId"));long version=target.path("current").path("version").asLong();
        var service=store.service();
        switch(type) {
            case "ACTIVITY" -> service.deleteActivity(new DeleteActivity(new ActivityId(id),version));
            case "WORK_SESSION" -> store.workSessionService().deleteWorkSession(new DeleteWorkSession(new WorkSessionId(id),version));
            case "TASK" -> service.deleteTask(new DeleteTask(new TaskId(id),version));
            case "PROJECT" -> service.deleteProject(new DeleteProject(new ProjectId(id),version));
            case "LABEL" -> service.deleteLabel(new DeleteLabel(new LabelId(id),version));
            case "CLIENT" -> {store.delete(type,id);store.change(type,"DELETED",id.toString());}
            default -> throw ApiFailure.validation();
        }
    }
}
