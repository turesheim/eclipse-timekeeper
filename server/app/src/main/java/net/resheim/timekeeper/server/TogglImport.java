package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.*;

/** Normalized, completed Track entries. CSV decoding stays in a cancellable browser worker. */
final class TogglImport {
    private final ServerStore store;
    private final ImportLedger ledger;
    private final ImportCatalog catalog;
    private final ImportProvenance provenance;
    private record Row(JsonNode input,Instant start,Instant end,BigInteger nanos,String fingerprint,String content,int occurrence,List<ActivityDailySegments.Interval> parts) { }
    TogglImport(ServerStore store) {this.store=store;ledger=new ImportLedger(store);catalog=new ImportCatalog(store);provenance=new ImportProvenance(store);}

    ObjectNode create(JsonNode input) {
        var mappings=input.path("mappings");catalog.review(mappings);String mode=mode(input);
        if(mode.equals("WORK_SESSION")) for(var mapping:mappings) if(!Set.of("CLIENT","LABEL").contains(mapping.path("resourceType").asText())) throw ApiFailure.validation();
        int count=integer(input,"selectedEntries",1,100000);var total=nanos(input.path("selectedNanos"));
        var job=ledger.create(text(input,"label",255),text(input,"sourceKey",64),text(input,"sourceZone",255),
                text(input,"fileFingerprint",64),hash(canonical(mappings).toString()));
        job.put("mode",mode);catalog.register(job,mappings);job.set("mappings",mappings.deepCopy());job.put("selectedEntries",count);job.put("selectedNanos",total.toString());
        String dateFormat=input.path("sourceDateFormat").asText("ISO");if(!Set.of("ISO","DMY","MDY").contains(dateFormat)) throw ApiFailure.validation();
        if(input.has("selectionFingerprint")) {
            String selection=text(input,"selectionFingerprint",64);if(!selection.matches("[0-9a-f]{64}")) throw ApiFailure.validation();job.put("selectionFingerprint",selection);
        }
        if(input.has("sourceDiagnostics")) {
            var diagnostics=input.get("sourceDiagnostics");
            int totalRows=integer(diagnostics,"sourceRows",1,100000),rejected=integer(diagnostics,"rejectedRows",0,100000),
                    excluded=integer(diagnostics,"excludedRows",0,100000),unselected=integer(diagnostics,"unselectedRows",0,100000);
            if(totalRows!=count+rejected+excluded+unselected) throw ApiFailure.validation();
            strings(diagnostics.path("unsupportedFields"),100);job.set("sourceDiagnostics",diagnostics.deepCopy());
        }
        job.put("sourceDateFormat",dateFormat);job.put("skippedNanos","0");return ledger.update(ImportLedger.Kind.IMPORT_JOB,job);
    }
    ObjectNode preview(JsonNode input) {
        var job=object().put("sourceKey",text(input,"sourceKey",64)).put("reportZone",store.profile().path("zoneId").asText());
        if(!job.path("sourceKey").asText().matches("[0-9a-f]{64}")) throw ApiFailure.validation();
        job.put("mode",mode(input));job.set("mappings",input.path("mappings"));return preview(job,input.path("rows"));
    }
    ObjectNode preview(UUID id,JsonNode rows) {
        var job=ledger.required(ImportLedger.Kind.IMPORT_JOB,id);
        if(!"IMPORTING".equals(job.path("state").asText()) || !job.path("reportZone").asText().equals(store.profile().path("zoneId").asText())) throw ApiFailure.conflict();
        return preview(job,rows);
    }
    private ObjectNode preview(ObjectNode job,JsonNode input) {
        var targets=targets(job);var rows=rows(job,input);
        var result=object().put("reportZone",job.path("reportZone").asText()).put("requestFingerprint",hash(canonical(input).toString()));
        if(job.has("id")) result.put("jobId",job.path("id").asText()).put("jobVersion",job.path("version").asLong());
        var outcomes=result.putArray("rows");BigInteger total=BigInteger.ZERO;int parts=0;
        for(var row:rows) {outcomes.add(inspect(job,row,targets));total=total.add(row.nanos());parts+=row.parts().size();}
        result.put("sourceNanos",total.toString());result.put(sessions(job) ? "workSessions" : "dailyActivities",parts);
        result.put("reviewToken",hash(canonical(result).toString()));return result;
    }
    private Map<String,JsonNode> targets(ObjectNode job) {
        var targets=new HashMap<>(catalog.review(job.path("mappings")));
        if(job.has("id")) for(var definition:job.path("mappings")) {
            var mapping=catalog.definition(uuid(job.get("id")),definition.path("key").asText());
            if(mapping.hasNonNull("targetId")) {
                var current=store.find(definition.path("resourceType").asText(),uuid(mapping.get("targetId"))).orElseThrow(ApiFailure::notFound);
                ApiReplies.match(current,mapping.path("currentTag").asText());targets.put(definition.path("key").asText(),current);
            }
        }
        return targets;
    }
    ObjectNode batch(UUID id,int index,JsonNode input) {
        if(input==null || !input.isObject() || !input.path("finished").isBoolean()) throw ApiFailure.validation();
        return ledger.batch(id,index,input,input.path("finished").asBoolean(),job->{
            var preview=preview(job,input.path("rows"));
            if(!preview.path("reviewToken").asText().equals(input.path("reviewToken").asText())) throw new ApiFailure(412,"STALE_VERSION");
            var rows=rows(job,input.path("rows"));var outcomes=array();int imported=0,skipped=0,activityCount=0;
            BigInteger importedNanos=BigInteger.ZERO,skippedNanos=BigInteger.ZERO;
            for(int n=0;n<rows.size();n++) {
                var row=rows.get(n);var inspected=preview.path("rows").get(n);String status=inspected.path("status").asText();
                if(status.equals("SOURCE_CHANGED")) throw new ApiFailure(409,"IMPORT_SOURCE_CHANGED");
                List<JsonNode> activities;boolean created=false;
                var active=provenance.active(job,row.fingerprint(),row.occurrence());
                if(active.isPresent()) {
                    var original=ledger.required(ImportLedger.Kind.IMPORT_ITEM,uuid(active.get().get("itemId")));
                    activities=currentParts(original);
                    if(activities.isEmpty()) provenance.releaseIfMissing(job,original);
                    else {
                        audit(provenance.recordSnapshots(job,row.fingerprint(),row.content(),row.occurrence(),activities,false,true),row,status.equals("REUSED_EDITED") ? status : "REUSED");
                        skipped++;skippedNanos=skippedNanos.add(row.nanos());
                        outcomes.add(outcome(job,row,status.equals("REUSED_EDITED") ? status : "REUSED",activities,false));continue;
                    }
                }
                var matches=inspected.path("matches");boolean any=false;
                for(var group:matches) if(!group.isEmpty()) any=true;
                String decision=row.input().path("duplicateDecision").asText("");
                if(any && !Set.of("CREATE_DISTINCT","REUSE").contains(decision)) throw new ApiFailure(409,"IMPORT_DUPLICATE_REVIEW_REQUIRED");
                if(decision.equals("REUSE")) {
                    activities=chosen(job,row,matches);
                    resolveCatalog(job,row);
                    skipped++;skippedNanos=skippedNanos.add(row.nanos());
                } else {
                    activities=create(job,row);created=true;imported++;activityCount+=activities.size();importedNanos=importedNanos.add(row.nanos());
                }
                audit(provenance.recordSnapshots(job,row.fingerprint(),row.content(),row.occurrence(),activities,created,false),row,created ? "IMPORTED" : "REUSED");
                outcomes.add(outcome(job,row,created ? "IMPORTED" : "REUSED",activities,created));
            }
            job.put("importedEntries",job.path("importedEntries").asInt()+imported);job.put("skippedEntries",job.path("skippedEntries").asInt()+skipped);
            String recordCounter=sessions(job) ? "workSessionCount" : "activityCount";
            job.put(recordCounter,job.path(recordCounter).asInt()+activityCount);
            job.put("importedNanos",nanos(job.path("importedNanos")).add(importedNanos).toString());job.put("skippedNanos",nanos(job.path("skippedNanos")).add(skippedNanos).toString());
            int committed=job.path("importedEntries").asInt()+job.path("skippedEntries").asInt();
            BigInteger committedNanos=nanos(job.path("importedNanos")).add(nanos(job.path("skippedNanos"))),expected=nanos(job.path("selectedNanos"));
            if(committed>job.path("selectedEntries").asInt() || committedNanos.compareTo(expected)>0
                    || input.path("finished").asBoolean() && (committed!=job.path("selectedEntries").asInt() || !committedNanos.equals(expected))) throw ApiFailure.validation();
            var result=object().put("importedEntries",imported).put("skippedEntries",skipped).put(recordCounter,activityCount)
                    .put("importedNanos",importedNanos.toString()).put("skippedNanos",skippedNanos.toString());result.set("rows",outcomes);return result;
        });
    }
    private ObjectNode inspect(ObjectNode job,Row row,Map<String,JsonNode> targets) {
        var result=object().put("fingerprint",row.fingerprint()).put("occurrence",row.occurrence()).put("sourceNanos",row.nanos().toString());
        var active=provenance.active(job,row.fingerprint(),row.occurrence());
        if(active.isPresent()) {
            if(!row.content().equals(active.get().path("contentFingerprint").asText())) return result.put("status","SOURCE_CHANGED");
            var item=ledger.required(ImportLedger.Kind.IMPORT_ITEM,uuid(active.get().get("itemId")));var current=currentParts(item);
            if(!current.isEmpty()) {
                boolean edited=current.size()!=item.path("parts").size();
                for(var part:item.path("parts")) {
                    var found=store.find(item.path("recordType").asText("ACTIVITY"),uuid(part.get("id")));
                    if(found.isEmpty() || !hash(canonical(found.get()).toString()).equals(part.path("baselineHash").asText())) edited=true;
                }
                result.put("status",edited ? "REUSED_EDITED" : "REUSED");var parts=result.putArray(sessions(job) ? "workSessions" : "activities");
                current.forEach(parts::add);return result;
            }
        }
        var matches=sessions(job) ? sessionMatches(row,targets) : matches(row,targets);result.set("matches",matches);boolean found=false;
        for(var group:matches) if(!group.isEmpty()) found=true;
        return result.put("status",found ? "REVIEW_MATCHES" : "NEW");
    }
    private List<JsonNode> currentParts(JsonNode item) {
        var values=new ArrayList<JsonNode>();
        for(var part:item.path("parts")) store.find(item.path("recordType").asText("ACTIVITY"),uuid(part.get("id"))).ifPresent(value->values.add(value));
        // A running record is never a valid completed import target; report it for review instead.
        if(values.stream().anyMatch(value->!value.hasNonNull("end"))) throw new ApiFailure(409,"IMPORT_RUNNING_MATCH");
        return List.copyOf(values);
    }
    private ArrayNode matches(Row row,Map<String,JsonNode> targets) {
        var result=array();var project=targets.get(row.input().path("projectKey").asText());
        var task=targets.get(row.input().path("taskKey").asText());var labelIds=new TreeSet<String>();boolean known=project!=null;
        if(row.input().hasNonNull("taskKey") && task==null) known=false;
        for(var key:row.input().path("labelKeys")) {var label=targets.get(key.asText());if(label==null) known=false;else labelIds.add(label.path("id").asText());}
        for(var interval:row.parts()) {
            var group=result.addArray();if(!known) continue;
            // Bound materialized candidates even for long histories. Payload starts are canonical UTC instants.
            try(var statement=store.statement("SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type='ACTIVITY' AND payload LIKE ? FETCH FIRST 501 ROWS ONLY",store.owner(),"%\"start\":\""+interval.start()+"\"%");var rows=statement.executeQuery()) {
                int count=0;
                while(rows.next()) {
                    if(++count>500) throw new ApiFailure(409,"IMPORT_TOO_MANY_MATCHES");
                    var value=parse(rows.getString(1));var labels=new TreeSet<String>();value.path("labelIds").forEach(id->labels.add(id.asText()));
                    if(value.path("start").asText().equals(interval.start().toString()) && value.path("end").asText().equals(interval.end().orElseThrow().toString())
                            && value.path("summary").asText().equals(summary(row.input())) && labels.equals(labelIds)
                            && (value.path("projectId").asText().equals(project.path("id").asText()) || !value.hasNonNull("projectId") && task!=null)
                            && value.path("taskId").asText("").equals(task==null ? "" : task.path("id").asText()))
                        group.add(object().put("id",value.path("id").asText()).put("etag",etag(value)));
                }
            } catch(SQLException failure) {throw new ApiFailure(503,"UNAVAILABLE");}
        }
        return result;
    }
    private List<JsonNode> chosen(ObjectNode job,Row row,JsonNode matches) {
        var choices=row.input().path(sessions(job) ? "reuseWorkSessions" : "reuseActivities");
        if(!choices.isArray() || choices.size()!=row.parts().size()) throw ApiFailure.validation();
        var values=new ArrayList<JsonNode>();var seen=new HashSet<UUID>();
        for(int index=0;index<choices.size();index++) {
            var choice=choices.get(index);UUID id=ContractInputs.pathId(choice.path("id").asText());
            boolean found=false;for(var candidate:matches.get(index)) if(candidate.path("id").asText().equals(id.toString()) && candidate.path("etag").asText().equals(choice.path("etag").asText())) found=true;
            if(!found || !seen.add(id)) throw new ApiFailure(412,"STALE_VERSION");
            values.add(store.find(sessions(job) ? "WORK_SESSION" : "ACTIVITY",id).orElseThrow(ApiFailure::notFound));
        }
        return List.copyOf(values);
    }
    private List<JsonNode> create(ObjectNode job,Row row) {
        if(sessions(job)) {
            var client=catalog.resolve(job,row.input().path("clientKey").asText(),"CLIENT");
            var value=store.withCreationClient(uuid(client.get("id")),()->store.workSessionService().createWorkSession(
                    new CreateWorkSession(new OwnerId(store.owner()),row.start(),Optional.of(row.end()),summary(row.input()))));
            var details=object().put("title",title(row.input()));var labels=details.putArray("labelIds");
            for(var key:row.input().path("labelKeys")) labels.add(catalog.resolve(job,key.asText(),"LABEL").path("id").asText());
            return List.of(store.clientDetails("WORK_SESSION",value.id().value(),details));
        }
        var project=catalog.resolve(job,row.input().path("projectKey").asText(),"PROJECT");TaskId task=null;
        if(row.input().hasNonNull("taskKey")) {
            var value=catalog.resolve(job,row.input().path("taskKey").asText(),"TASK");
            if(!project.path("id").asText().equals(value.path("projectId").asText())) throw ApiFailure.validation();task=new TaskId(uuid(value.get("id")));
        }
        var labels=new HashSet<LabelId>();for(var key:row.input().path("labelKeys")) labels.add(new LabelId(uuid(catalog.resolve(job,key.asText(),"LABEL").get("id"))));
        var retained=store.service().createActivity(new CreateActivity(task,new OwnerId(store.owner()),row.start(),Optional.of(row.end()),summary(row.input()),labels,Optional.empty(),Optional.of(new ProjectId(uuid(project.get("id"))))));
        var result=new ArrayList<JsonNode>();
        for(int n=0;n<row.parts().size();n++) {
            var id=n==row.parts().size()-1 ? retained.id() : ActivityDailySegments.identifier(retained.id(),row.parts().get(n).start());
            result.add(store.find("ACTIVITY",id.value()).orElseThrow(ApiFailure::notFound));
        }
        return List.copyOf(result);
    }
    private List<Row> rows(ObjectNode job,JsonNode input) {
        if(!input.isArray() || input.isEmpty() || input.size()>100) throw ApiFailure.validation();
        var definitions=new HashMap<String,JsonNode>();job.path("mappings").forEach(value->definitions.put(value.path("key").asText(),value));
        var result=new ArrayList<Row>();int count=0;
        for(var value:input) {
            if(!value.isObject() || !job.path("sourceKey").asText().equals(value.path("sourceKey").asText())) throw ApiFailure.validation();
            Instant start=ContractInputs.instant(text(value,"start",40)),end=ContractInputs.instant(text(value,"end",40));
            var duration=nanos(value.path("durationNanos"));
            if(end.isBefore(start) || end.isAfter(store.now()) || !duration.equals(BigInteger.valueOf(Duration.between(start,end).getSeconds()).multiply(BigInteger.valueOf(1000000000)).add(BigInteger.valueOf(Duration.between(start,end).getNano())))) throw ApiFailure.validation();
            var zone=ContractInputs.zone(job.path("reportZone").asText());
            if(!sessions(job) && ChronoUnit.DAYS.between(start.atZone(zone).toLocalDate(),end.atZone(zone).toLocalDate())>1000) throw new ApiFailure(422,"IMPORT_INTERVAL_TOO_LARGE");
            var parts=sessions(job) ? List.of(new ActivityDailySegments.Interval(start,Optional.of(end))) : ActivityDailySegments.split(start,Optional.of(end),zone,store.now());count+=parts.size();if(count>1000) throw new ApiFailure(422,"IMPORT_BATCH_TOO_LARGE");
            if(sessions(job)) {mapping(definitions,value.path("clientKey").asText(),"CLIENT");title(value);}
            else {mapping(definitions,value.path("projectKey").asText(),"PROJECT");
            if(value.hasNonNull("taskKey")) {
                mapping(definitions,value.path("taskKey").asText(),"TASK");
                if(!definitions.get(value.path("taskKey").asText()).path("parentKey").asText().equals(value.path("projectKey").asText())) throw ApiFailure.validation();
            }
            }
            if(!sessions(job) || value.has("labelKeys")) {
                var tagNames=strings(value.path("tags"),1000);var labelKeys=strings(value.path("labelKeys"),1000);
                if(tagNames.size()!=labelKeys.size()) throw ApiFailure.validation();for(var key:labelKeys) mapping(definitions,key,"LABEL");
            }
            var tagNames=strings(value.path("tags"),1000);
            int occurrence=integer(value,"occurrence",0,100000);String sourceId=value.path("sourceId").isNull() ? "" : value.path("sourceId").asText("");
            if(sourceId.length()>255 || !sourceId.isEmpty() && occurrence!=0 || occurrence>0 && !value.path("duplicateReviewed").asBoolean()) throw ApiFailure.validation();
            var content=array().add(instantNanos(start)).add(instantNanos(end)).add(text(value,"client",1000)).add(text(value,"project",1000)).add(text(value,"task",1000)).add(summary(value));
            var tags=content.addArray();tagNames.stream().sorted().forEach(tags::add);if(sessions(job)) content.add(title(value));String contentHash=hash(canonical(content).toString());
            String fingerprint=sourceId.isEmpty() ? contentHash : hash(array().add("id").add(sourceId).toString());
            result.add(new Row(value,start,end,duration,fingerprint,contentHash,occurrence,parts));
        }
        return List.copyOf(result);
    }
    private static String mode(JsonNode input) {
        String mode=input.path("mode").asText("ACTIVITY");
        if(!Set.of("ACTIVITY","WORK_SESSION").contains(mode)) throw ApiFailure.validation();return mode;
    }
    private static boolean sessions(JsonNode job) {return mode(job).equals("WORK_SESSION");}
    private static String title(JsonNode row) {
        String value=text(row,"title",1000).strip();if(value.isEmpty()) throw ApiFailure.validation();return value;
    }
    private void resolveCatalog(ObjectNode job,Row row) {
        if(sessions(job)) catalog.resolve(job,row.input().path("clientKey").asText(),"CLIENT");
        else {
            catalog.resolve(job,row.input().path("projectKey").asText(),"PROJECT");
            if(row.input().hasNonNull("taskKey")) catalog.resolve(job,row.input().path("taskKey").asText(),"TASK");
        }
        for(var key:row.input().path("labelKeys")) catalog.resolve(job,key.asText(),"LABEL");
    }
    private ArrayNode sessionMatches(Row row,Map<String,JsonNode> targets) {
        var result=array();var group=result.addArray();var client=targets.get(row.input().path("clientKey").asText());if(client==null) return result;
        var expectedLabels=new TreeSet<String>();
        for(var key:row.input().path("labelKeys")) {var label=targets.get(key.asText());if(label==null) return result;expectedLabels.add(label.path("id").asText());}
        try(var statement=store.statement("SELECT payload FROM tk_resource WHERE owner_id=? AND entity_type='WORK_SESSION' AND payload LIKE ? FETCH FIRST 501 ROWS ONLY",store.owner(),"%\"start\":\""+row.start()+"\"%");var rows=statement.executeQuery()) {
            int count=0;while(rows.next()) {
                if(++count>500) throw new ApiFailure(409,"IMPORT_TOO_MANY_MATCHES");var value=parse(rows.getString(1));
                var labels=new TreeSet<String>();value.path("labelIds").forEach(label->labels.add(label.asText()));
                if(labels.equals(expectedLabels) && value.path("start").asText().equals(row.start().toString()) && value.path("end").asText().equals(row.end().toString())
                        && value.path("clientId").asText().equals(client.path("id").asText()) && value.path("title").asText().equals(title(row.input()))
                        && value.path("summary").asText().equals(summary(row.input()))) group.add(object().put("id",value.path("id").asText()).put("etag",etag(value)));
            }
        } catch(SQLException failure) {throw new ApiFailure(503,"UNAVAILABLE");}return result;
    }
    private static void mapping(Map<String,JsonNode> definitions,String key,String type) {
        if(!definitions.containsKey(key) || !type.equals(definitions.get(key).path("resourceType").asText())) throw ApiFailure.validation();
    }
    private static List<String> strings(JsonNode values,int max) {
        if(!values.isArray() || values.size()>max) throw ApiFailure.validation();var result=new ArrayList<String>();
        for(var value:values) {if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>1000 || result.contains(value.asText())) throw ApiFailure.validation();result.add(value.asText());}return result;
    }
    private static BigInteger nanos(JsonNode value) {if(!value.isTextual() || !value.asText().matches("[0-9]{1,30}")) throw ApiFailure.validation();return new BigInteger(value.asText());}
    private static String instantNanos(Instant value) {return BigInteger.valueOf(value.getEpochSecond()).multiply(BigInteger.valueOf(1000000000)).add(BigInteger.valueOf(value.getNano())).toString();}
    private static String summary(JsonNode value) {return text(value,"summary",2000).strip().replace("\r\n","\n").replace('\r','\n');}
    private static String text(JsonNode input,String field,int max) {var value=input.path(field);if(!value.isTextual() || value.asText().length()>max || value.asText().indexOf(0)>=0) throw ApiFailure.validation();return value.asText();}
    private static int integer(JsonNode value,String field,int min,int max) {var number=value.path(field);if(!number.isIntegralNumber() || !number.canConvertToInt() || number.asInt()<min || number.asInt()>max) throw ApiFailure.validation();return number.asInt();}
    private ObjectNode outcome(ObjectNode job,Row row,String status,List<JsonNode> activities,boolean created) {
        var value=object().put("fingerprint",row.fingerprint()).put("occurrence",row.occurrence()).put("status",status).put("created",created).put("sourceNanos",row.nanos().toString());
        var ids=value.putArray(sessions(job) ? "workSessionIds" : "activityIds");activities.forEach(activity->ids.add(activity.path("id").asText()));return value;
    }
    private void audit(ObjectNode item,Row row,String status) {
        item.put("sourceStart",row.start().toString());item.put("sourceEnd",row.end().toString());item.put("sourceNanos",row.nanos().toString());item.put("outcome",status);
        if(row.input().hasNonNull("sourceId")) item.put("sourceId",row.input().path("sourceId").asText());
        if(row.input().has("sourceLine")) item.put("sourceLine",integer(row.input(),"sourceLine",1,20971521));
        var source=item.putObject("source");for(String field:List.of("client","project","task","summary","tags","title")) if(row.input().has(field)) source.set(field,row.input().get(field).deepCopy());
        ledger.update(ImportLedger.Kind.IMPORT_ITEM,item);
    }
}
