package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReferenceKey;

/** Durable, private page staging. No task or public cursor changes before a complete, accepted scan. */
final class TaskProviderScans {
    static final String SCAN="TASK_PULL_SCAN",PAGE="TASK_PULL_PAGE";
    private final ServerStore store;
    private final CommandReceipts receipts;
    private final ContractInputs inputs;
    TaskProviderScans(ServerStore store,CommandReceipts receipts,ContractInputs inputs){this.store=store;this.receipts=receipts;this.inputs=inputs;}
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    ObjectNode begin(TaskProviderConnections.Claim claim){
        var auth=connections().active(claim);if(auth==null)return null;
        var before=store.find(SCAN,claim.list());Instant now=store.now();
        if(before.isPresent()){
            var old=before.get();boolean same=old.path("generation").asText().equals(claim.generation());
            if(same&&old.path("state").asText().equals("FETCHING")&&Instant.parse(old.path("startedAt").asText()).plusSeconds(86400).isAfter(now))return ((ObjectNode)old).deepCopy();
            if(same&&!claim.forced()&&old.path("state").asText().equals("READY")&&Instant.parse(old.path("startedAt").asText()).plusSeconds(900).isAfter(now))return ((ObjectNode)old).deepCopy();
        }
        boolean full=claim.provider()!=TaskCloudProvider.Provider.GOOGLE||!approved(auth,claim.remoteList())||before.isEmpty()
                ||!before.get().hasNonNull("lastFullAt")||!Instant.parse(before.get().path("lastFullAt").asText()).plusSeconds(86400).isAfter(now);
        var value=object().put("connectionId",claim.id().toString()).put("remoteListId",claim.remoteList()).put("providerId",claim.provider().name().toLowerCase(Locale.ROOT))
                .put("accountId",claim.account()).put("generation",claim.generation()).put("scanId",UUID.randomUUID().toString()).put("startedAt",now.toString())
                .put("state","FETCHING").put("pageCount",0).put("count",0).put("bytes",0).put("full",full).putNull("next").putNull("updatedMin");
        value.putArray("seenTokens");value.putArray("seenIds");
        if(before.isPresent())for(String field:List.of("lastFullAt","lastAppliedAt"))if(before.get().hasNonNull(field))value.set(field,before.get().get(field));
        if(!full&&value.hasNonNull("lastAppliedAt"))value.put("updatedMin",Instant.parse(value.path("lastAppliedAt").asText()).minusSeconds(300).toString());
        else value.put("full",true);
        if(before.isPresent()){
            clearPages(before.get());value.put("id",claim.list().toString()).put("ownerId",store.owner()).set("version",before.get().get("version"));return connections().save(SCAN,value);
        }
        return connections().insert(SCAN,claim.list(),value);
    }
    ObjectNode acknowledge(TaskProviderConnections.Claim claim,ObjectNode expected,TaskCloudProvider.Page<TaskCloudProvider.RemoteTask> page){
        if(connections().active(claim)==null)return null;
        var value=required(claim.list());if(!value.path("generation").asText().equals(claim.generation()))return null;
        if(!etag(value).equals(etag(expected))||!value.path("state").asText().equals("FETCHING"))throw ApiFailure.conflict();
        var ids=new HashSet<String>();value.path("seenIds").forEach(id->ids.add(id.asText()));
        var originals=array();int bytes=value.path("bytes").asInt();
        if(page.items().size()>100)throw failure("TASK_PROVIDER_SCAN_INCOMPLETE");
        for(var item:page.items()){
            if(!ids.add(item.id()))throw failure("TASK_PROVIDER_SCAN_INCOMPLETE");
            bytes+=item.original().toString().getBytes(StandardCharsets.UTF_8).length;
            if(ids.size()>TaskCloudPull.MAX_TASKS||bytes>TaskCloudPull.MAX_SCAN_BYTES)throw failure("TASK_PROVIDER_SCAN_LIMIT");
            originals.add(item.original().deepCopy());
        }
        int index=value.path("pageCount").asInt();if(index>=TaskCloudPull.MAX_PAGES)throw failure("TASK_PROVIDER_SCAN_LIMIT");
        if(page.next()!=null){
            if(page.next().isBlank()||page.next().length()>32768)throw failure("TASK_PROVIDER_SCAN_INCOMPLETE");
            String token=hash(page.next());for(var seen:value.path("seenTokens"))if(seen.asText().equals(token))throw failure("TASK_PROVIDER_SCAN_INCOMPLETE");
            value.withArray("seenTokens").add(token);
        }
        var row=object().put("scanId",value.path("scanId").asText()).put("index",index);row.set("items",originals);
        connections().insert(PAGE,pageId(value,index),row);
        value.put("pageCount",index+1).put("count",ids.size()).put("bytes",bytes);var saved=value.putArray("seenIds");ids.stream().sorted().forEach(saved::add);
        if(page.next()==null)value.putNull("next").put("state","READY");else value.put("next",page.next());
        return connections().save(SCAN,value);
    }
    ObjectNode reconcile(TaskProviderConnections.Claim claim){
        var auth=connections().active(claim);if(auth==null)return null;
        var value=required(claim.list());if(!value.path("generation").asText().equals(claim.generation())||!value.path("state").asText().equals("READY"))throw ApiFailure.conflict();
        if(!approved(auth,claim.remoteList()))return object().put("awaitingImport",true);
        return apply(value,auth);
    }
    ObjectNode review(UUID connection,UUID list){
        var auth=connections().required(connection);var value=required(list);check(connection,value,auth);
        if(!value.path("state").asText().equals("READY"))throw new ApiFailure(409,"TASK_PROVIDER_PREVIEW_NOT_READY");
        var result=object().put("id",list.toString()).put("version",value.path("version").asLong()).put("remoteListId",value.path("remoteListId").asText()).put("observedAt",value.path("startedAt").asText())
                .put("count",value.path("count").asInt()).put("createCount",0).put("existingCount",0).put("deletedCount",0);
        result.set("projectId",auth.path("projects").has(value.path("remoteListId").asText())?auth.path("projects").get(value.path("remoteListId").asText()):com.fasterxml.jackson.databind.node.NullNode.instance);
        var samples=result.putArray("samples");var versions=array();
        for(var remote:tasks(value)){
            var task=store.byReference(new ExternalTaskReferenceKey(value.path("providerId").asText(),TaskCloudPull.repository(value.path("accountId").asText(),value.path("remoteListId").asText()),remote.id()));
            String category=remote.deleted()?"deletedCount":task.isEmpty()?"createCount":"existingCount";result.put(category,result.path(category).asInt()+1);
            versions.add(object().put("remoteId",remote.id()).put("etag",task.map(JsonValues::etag).orElse("new")));
            if(samples.size()<20)samples.add(object().put("title",remote.fields().path("summary").asText()).put("deleted",remote.deleted()));
        }
        // Include task versions as a hash so approval cannot silently use a changed local comparison.
        result.put("revision",hash(value.path("scanId").asText()+":"+value.path("version").asText()+":"+versions));return result;
    }
    ObjectNode approve(UUID connection,UUID list,String match){
        var auth=connections().required(connection);var value=required(list);check(connection,value,auth);ApiReplies.match(review(connection,list),match);
        if(Instant.parse(value.path("startedAt").asText()).plusSeconds(900).isBefore(store.now()))throw new ApiFailure(409,"TASK_PROVIDER_PREVIEW_EXPIRED");
        if(approved(auth,value.path("remoteListId").asText()))throw ApiFailure.conflict();
        var outcome=apply(value,auth);
        auth.withArray("approved").add(value.path("remoteListId").asText());auth.putNull("lease").putNull("leaseUntil").put("roundActive",false).put("requested",true).put("nextRefresh",store.now().toString());
        connections().save(TaskProviderConnections.AUTH,auth);return outcome;
    }
    private ObjectNode apply(ObjectNode value,ObjectNode auth){
        UUID connection=uuid(value.get("connectionId")),list=uuid(value.get("id"));
        UUID project=optionalText(auth.path("projects").get(value.path("remoteListId").asText())).map(UUID::fromString).orElse(null);
        var scan=new TaskCloudPull.Scan(value.path("providerId").asText(),value.path("accountId").asText(),value.path("remoteListId").asText(),tasks(value),Instant.parse(value.path("startedAt").asText()),value.path("full").asBoolean());
        var result=new TaskCloudPull(store,receipts,inputs).apply(connection,list,scan,project,true);
        if(result.path("complete").asBoolean()){
            value.put("state","APPLIED").put("lastAppliedAt",value.path("startedAt").asText());if(value.path("full").asBoolean())value.put("lastFullAt",value.path("startedAt").asText());
            connections().save(SCAN,value);
        }
        return result;
    }
    static boolean approved(JsonNode auth,String remote){for(var item:auth.path("approved"))if(item.asText().equals(remote))return true;return false;}
    static com.fasterxml.jackson.databind.node.ArrayNode previews(ServerStore store,JsonNode auth){
        var result=array();for(var value:store.list(SCAN))if(value.path("connectionId").asText().equals(auth.path("id").asText())&&value.path("generation").asText().equals(auth.path("generation").asText())
                &&!approved(auth,value.path("remoteListId").asText()))result.add(object().put("id",value.path("id").asText()).put("remoteListId",value.path("remoteListId").asText()).put("state",value.path("state").asText()).put("count",value.path("count").asInt()));return result;
    }
    List<TaskCloudProvider.RemoteTask> tasks(JsonNode value){
        var result=new ArrayList<TaskCloudProvider.RemoteTask>();var reader=new TaskCloudProvider(TaskProviderConfiguration.provider(value.path("providerId").asText()));
        for(int index=0;index<value.path("pageCount").asInt();index++){
            var page=store.find(PAGE,pageId(value,index)).orElseThrow(()->failure("TASK_PROVIDER_SCAN_INCOMPLETE"));
            if(!page.path("scanId").equals(value.path("scanId"))||page.path("index").asInt()!=index)throw failure("TASK_PROVIDER_SCAN_INCOMPLETE");
            for(var item:page.path("items"))result.add(reader.normalize(item));
        }
        if(result.size()!=value.path("count").asInt())throw failure("TASK_PROVIDER_SCAN_INCOMPLETE");return List.copyOf(result);
    }
    private void check(UUID connection,ObjectNode scan,ObjectNode auth){
        var metadata=store.find("TASK_CONNECTION",connection).orElseThrow(ApiFailure::notFound);var list=store.find("TASK_LIST",uuid(scan.get("id"))).orElseThrow(ApiFailure::notFound);
        if(!scan.path("connectionId").asText().equals(connection.toString())||!scan.path("generation").equals(auth.path("generation"))||!metadata.path("enabled").asBoolean()||!list.path("present").asBoolean()||auth.path("paused").asBoolean())throw ApiFailure.conflict();
        boolean selected=false;for(var key:auth.path("selected"))if(key.equals(scan.path("remoteListId")))selected=true;if(!selected)throw ApiFailure.conflict();
    }
    private ObjectNode required(UUID list){return store.find(SCAN,list).map(node->((ObjectNode)node).deepCopy()).orElseThrow(ApiFailure::notFound);}
    private static UUID pageId(JsonNode value,int index){return UUID.nameUUIDFromBytes(("dagsverk-task-page:"+value.path("scanId").asText()+":"+index).getBytes(StandardCharsets.UTF_8));}
    private void clearPages(JsonNode value){clearPages(store,value);}
    private static void clearPages(ServerStore store,JsonNode value){for(int index=0;index<value.path("pageCount").asInt();index++)store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),PAGE,pageId(value,index));}
    static void retain(ServerStore store,UUID connection,Set<String> selected){
        for(var value:store.list(SCAN))if(value.path("connectionId").asText().equals(connection.toString())&&!selected.contains(value.path("remoteListId").asText())){
            clearPages(store,value);store.update("DELETE FROM tk_resource WHERE owner_id=? AND entity_type=? AND id=?",store.owner(),SCAN,uuid(value.get("id")));
        }
    }
    private static TaskProviderHttp.Failure failure(String code){return new TaskProviderHttp.Failure(code,0,0,false);}
}
