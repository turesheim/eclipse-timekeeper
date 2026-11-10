package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.resheim.eclipse.timekeeper.domain.*;

/** Fetch outside JDBC transactions; reconcile a complete selected list in its authenticated owner transaction. */
final class TaskCloudPull {
    static final int MAX_TASKS=10000,MAX_PAGES=1000,MAX_SCAN_BYTES=32*1024*1024;
    record Scan(String provider,String account,String list,List<TaskCloudProvider.RemoteTask> tasks,Instant observedAt,boolean full) {
        Scan(String provider,String account,String list,List<TaskCloudProvider.RemoteTask> tasks,Instant observedAt){this(provider,account,list,tasks,observedAt,true);}
        Scan {tasks=List.copyOf(tasks);}
    }
    private final ServerStore store;
    private final CommandReceipts receipts;
    private final ContractInputs inputs;
    TaskCloudPull(ServerStore store,CommandReceipts receipts,ContractInputs inputs){this.store=store;this.receipts=receipts;this.inputs=inputs;}

    static Scan fetch(TaskCloudProvider provider,String token,String expectedAccount,String list,Instant observedAt){
        if(!provider.account(token).id().equals(expectedAccount))throw new TaskProviderHttp.Failure("TASK_PROVIDER_ACCOUNT_CHANGED",0,0,false);
        var tasks=new ArrayList<TaskCloudProvider.RemoteTask>();var ids=new HashSet<String>();var pages=new HashSet<String>();
        String next=null;int bytes=0;long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        do{
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new TaskProviderHttp.Failure("TASK_PROVIDER_SCAN_INCOMPLETE",0,0,false);
            var page=provider.tasks(token,list,next);
            for(var task:page.items()){
                if(!ids.add(task.id()))throw new TaskProviderHttp.Failure("TASK_PROVIDER_SCAN_INCOMPLETE",0,0,false);
                bytes+=task.original().toString().getBytes(StandardCharsets.UTF_8).length;
                if(tasks.size()>=MAX_TASKS||bytes>MAX_SCAN_BYTES)throw new TaskProviderHttp.Failure("TASK_PROVIDER_SCAN_LIMIT",0,0,false);
                tasks.add(task);
            }
            next=page.next();
            if(next!=null&&(!pages.add(next)||pages.size()>MAX_PAGES))throw new TaskProviderHttp.Failure("TASK_PROVIDER_SCAN_INCOMPLETE",0,0,false);
        }while(next!=null);
        return new Scan(provider.id(),expectedAccount,list,tasks,observedAt);
    }

    /** A scan never infers deletion from absent items. Explicit tombstones remain review decisions. */
    ObjectNode apply(UUID connectionId,UUID listId,Scan scan){return apply(connectionId,listId,scan,null,false);}
    ObjectNode apply(UUID connectionId,UUID listId,Scan scan,UUID project,boolean acknowledgedScan){
        var connection=required("TASK_CONNECTION",connectionId);var list=required("TASK_LIST",listId);
        if(!connection.path("providerId").asText().equals(scan.provider())||!connection.path("remoteAccountId").asText().equals(scan.account())
                ||!list.path("connectionId").asText().equals(connectionId.toString())||!list.path("remoteListId").asText().equals(scan.list()))throw ApiFailure.conflict();
        if(!connection.path("enabled").asBoolean()||!connection.path("readable").asBoolean()||!list.path("present").asBoolean()||!list.path("readable").asBoolean())throw ApiFailure.conflict();
        String repository=repository(scan.account(),scan.list());
        var result=object().put("created",0).put("updated",0).put("unchanged",0);var pending=result.putArray("pending");
        var sync=new TaskSync(store,receipts);var remaining=new ArrayList<>(scan.tasks());
        remaining.sort(Comparator.comparing((TaskCloudProvider.RemoteTask task)->Objects.toString(task.position(),"" )).thenComparing(TaskCloudProvider.RemoteTask::id));
        var ordering=new HashMap<String,TaskCloudProvider.RemoteTask>();
        if(!scan.full()&&scan.provider().equals("google"))for(var task:store.list("TASK"))for(var reference:task.path("externalReferences")){
            if(reference.path("providerId").asText().equals(scan.provider())&&reference.path("repositoryId").asText().equals(repository)){
                var observation=reference.path("providerData").path("payload").path("observation");
                if(observation.isObject()){var known=new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(observation);ordering.put(known.id(),known);}
            }
        }
        for(var task:remaining)if(task.deleted())ordering.remove(task.id());else ordering.put(task.id(),task);
        var orders=new HashMap<String,Integer>();var ordinal=new HashMap<String,Integer>();
        ordering.values().stream().sorted(Comparator.comparing((TaskCloudProvider.RemoteTask task)->Objects.toString(task.position(),"")).thenComparing(TaskCloudProvider.RemoteTask::id))
                .forEach(task->ordinal.put(task.id(),orders.merge(Objects.toString(task.parent(),""),1,Integer::sum)-1));
        var blocked=new HashSet<String>();
        while(!remaining.isEmpty()){
            boolean progressed=false;
            for(var iterator=remaining.iterator();iterator.hasNext();){
                var remote=iterator.next();var fields=scan.provider().equals("google")?GoogleTaskContext.fields(remote,repository):remote.fields().deepCopy();
                var existing=store.byReference(new ExternalTaskReferenceKey(scan.provider(),repository,remote.id()));
                boolean localHierarchy=false;if(existing.isPresent())for(var ref:existing.get().path("externalReferences"))if(ref.path("providerId").asText().equals(scan.provider())&&ref.path("repositoryId").asText().equals(repository)&&ref.path("externalId").asText().equals(remote.id()))localHierarchy=ref.path("providerData").path("payload").path("policy").path("localHierarchy").asBoolean();
                if(scan.provider().equals("google")&&!remote.deleted()&&!localHierarchy){
                    if(remote.position()==null||remote.position().isBlank()){
                        deferred(pending,remote.id(),"POSITION_UNOBSERVED");blocked.add(remote.id());iterator.remove();progressed=true;continue;
                    }
                    if(remote.parent()!=null){
                        if(blocked.contains(remote.parent())){deferred(pending,remote.id(),"PARENT_UNRESOLVED");blocked.add(remote.id());iterator.remove();progressed=true;continue;}
                        var parent=store.byReference(new ExternalTaskReferenceKey(scan.provider(),repository,remote.parent()));
                        if(parent.isEmpty())continue;
                        fields.put("parentTaskId",parent.get().path("id").asText());
                    }else fields.putNull("parentTaskId");
                    fields.put("siblingOrder",ordinal.get(remote.id()));
                }
                var item=object().put("providerId",scan.provider()).put("repositoryId",repository).put("externalId",remote.id()).put("deleted",remote.deleted());item.set("fields",fields);
                // Include the local version in the receipt identity: recomputed proposals after edits get a new operation.
                var current=existing;
                String operation=UUID.nameUUIDFromBytes(("dagsverk-cloud-pull:"+store.owner()+":"+connectionId+":"+listId+":"
                        +canonical(item)+":"+current.map(JsonValues::etag).orElse("new")+":"+project).getBytes(StandardCharsets.UTF_8)).toString();
                item.put("operationId",operation);
                var preview=object().put("connectionId",connectionId.toString()).put("listId",listId.toString()).put("sourceState","HEALTHY").put("scanComplete",acknowledgedScan);
                preview.putArray("items").add(item);inputs.validate("TaskSyncPreviewInput",preview);
                var decision=sync.preview(preview,acknowledgedScan).body().path("items").get(0);String status=decision.path("status").asText();
                if(Set.of("CREATE","UPDATE","KEEP_LOCAL","UNCHANGED").contains(status)){
                    var input=object().put("connectionId",connectionId.toString()).put("listId",listId.toString()).put("sourceState","HEALTHY");input.set("item",item);
                    if(status.equals("CREATE")&&project!=null&&!fields.hasNonNull("parentTaskId"))input.put("projectId",project.toString());
                    inputs.validate(status.equals("CREATE")?"TaskSyncImportInput":"TaskSyncApplyInput",input);
                    var applied=status.equals("CREATE")?sync.importTask(input):sync.apply(input,decision.path("taskEtag").asText());
                    remember(applied.body(),scan.provider(),repository,remote);
                    String count=status.equals("CREATE")?"created":status.equals("UPDATE")?"updated":"unchanged";result.put(count,result.path(count).asInt()+1);
                }else if(!status.equals("IGNORE_DELETED")){
                    var row=((ObjectNode)decision).deepCopy();row.set("observation",item.deepCopy());pending.add(row);blocked.add(remote.id());
                }
                iterator.remove();progressed=true;
            }
            if(!progressed){for(var remote:remaining)deferred(pending,remote.id(),"PARENT_UNRESOLVED");break;}
        }
        // Conflicts and explicit deletion reviews retain the prior checkpoint.
        if(pending.isEmpty())checkpoint(connectionId,listId,scan.observedAt(),scan.full());
        result.put("complete",pending.isEmpty());return result;
    }
    void remember(JsonNode task,String provider,String repository,TaskCloudProvider.RemoteTask remote){
        var candidate=((ObjectNode)task).deepCopy();ObjectNode reference=null;
        for(var ref:candidate.path("externalReferences"))if(ref.path("providerId").asText().equals(provider)&&ref.path("repositoryId").asText().equals(repository)&&ref.path("externalId").asText().equals(remote.id())){reference=(ObjectNode)ref;break;}
        if(reference==null)throw ApiFailure.conflict();
        var envelope=object().put("namespace",provider+".tasks").put("version",1);envelope.set("payload",remote.original().deepCopy());providerData(envelope);
        // Merge just this adapter's payload; preserve unrelated future keys in its envelope.
        if(reference.hasNonNull("providerData")){
            var existing=reference.path("providerData");
            if(!existing.path("namespace").asText().equals(provider+".tasks")||existing.path("version").asInt()!=1)throw ApiFailure.conflict();
            var payload=((ObjectNode)existing.path("payload")).deepCopy();payload.set("observation",remote.original().deepCopy());envelope.set("payload",payload);
        }else{var payload=object();payload.set("observation",remote.original().deepCopy());envelope.set("payload",payload);}
        providerData(envelope);
        if(provider.equals("google"))GoogleTaskContext.taskUrl(remote.original()).ifPresent(url->referenceUrl(candidate,provider,repository,remote.id(),url));
        var source=reference.path("source").isObject()?((ObjectNode)reference.path("source")).deepCopy():object();
        source.setAll(remote.source());
        if(candidate.equals(task)&&reference.path("source").equals(source)&&reference.path("providerData").equals(envelope))return;
        reference.set("source",source);reference.set("providerData",envelope);
        candidate.put("version",task.path("version").asLong()+1);store.save("TASK",(Task)decode("TASK",candidate));store.change("TASK","UPDATED",task.path("id").asText());
    }
    private static void referenceUrl(ObjectNode task,String provider,String repository,String external,String url){
        for(var ref:task.path("externalReferences"))if(ref.path("providerId").asText().equals(provider)&&ref.path("repositoryId").asText().equals(repository)&&ref.path("externalId").asText().equals(external))((ObjectNode)ref).put("externalUrl",url);
    }
    private void checkpoint(UUID connection,UUID list,Instant observed,boolean full){
        TaskSyncCursor cursor=null;
        for(var candidate:store.list("TASK_SYNC_CURSOR"))if(candidate.path("connectionId").asText().equals(connection.toString())&&candidate.path("listId").asText().equals(list.toString())){cursor=(TaskSyncCursor)decode("TASK_SYNC_CURSOR",candidate);break;}
        // Google uses an overlapping timestamp filter; its checkpoint is connector-owned opaque data.
        var mode=full?TaskSyncMode.INITIAL:TaskSyncMode.INCREMENTAL;
        var opaque=full?Optional.<String>empty():Optional.of(object().put("version",1).put("observedAt",observed.toString()).toString());
        var value=cursor==null?new TaskSyncCursor(UUID.randomUUID(),connection,Optional.of(list),mode,opaque,Optional.of(observed),Optional.of(observed),0)
                :cursor.update(mode,opaque,Optional.of(observed),Optional.of(observed));
        store.save("TASK_SYNC_CURSOR",value);store.change("TASK_SYNC_CURSOR",cursor==null?"CREATED":"UPDATED",value.id().toString());
    }
    static String repository(String account,String list){return TaskCloudProvider.segment(account)+"/"+TaskCloudProvider.segment(list);}
    private JsonNode required(String kind,UUID id){return store.find(kind,id).orElseThrow(ApiFailure::notFound);}
    private static void deferred(com.fasterxml.jackson.databind.node.ArrayNode pending,String id,String reason){pending.add(object().put("externalId",id).put("status","DEFERRED").put("reason",reason));}
}
