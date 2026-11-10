package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Invoked twice by task_provider_restart.py; each phase runs in a different server JVM. */
@QuarkusTest @TestProfile(TaskProviderRestartTest.Profile.class)
@EnabledIfEnvironmentVariable(named="TIMEKEEPER_TEST_TASK_RESTART_PHASE",matches="prepare|verify")
class TaskProviderRestartTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){
            if(System.getenv("TIMEKEEPER_TEST_TASK_RESTART_PHASE")!=null&&System.getenv("TIMEKEEPER_TEST_TASK_DB_URL")==null)throw new IllegalStateException("Restart acceptance needs its isolated persistent PostgreSQL fixture");
            var config=TaskProviderTestDatabase.settings("task_provider_restart");config.put("timekeeper.tasks.automatic","false");return config;
        }
    }
    @Inject ServerStore store;
    @Inject CommandReceipts receipts;
    @Inject ContractInputs inputs;
    private TaskProviderConnections connections(){return new TaskProviderConnections(store);}
    private TaskProviderScans scans(){return new TaskProviderScans(store,receipts,inputs);}
    private TaskProviderWrites writes(){return new TaskProviderWrites(store,receipts,inputs);}
    private TaskCloudProvider.RemoteTask remote(String id,String title,String etag,String position){return new TaskCloudProvider(TaskCloudProvider.Provider.GOOGLE).normalize(object().put("id",id).put("title",title).put("etag",etag).put("status","needsAction").put("position",position));}
    private ObjectNode connection(String label,boolean writable){
        var settings=new TaskProviderOAuth.Settings("restart-client","synthetic-private-secret","https://example.invalid/web/task-provider-callback.html");
        var attempt=connections().begin(TaskCloudProvider.Provider.GOOGLE,settings,"restart-browser",writable);
        String state=Arrays.stream(URI.create(attempt.path("authorizationUrl").asText()).getRawQuery().split("&")).filter(value->value.startsWith("state=")).map(value->URLDecoder.decode(value.substring(6),StandardCharsets.UTF_8)).findFirst().orElseThrow();
        String account="restart-account-"+label;var list=object().put("id","list").put("title",label);
        var connected=connections().connect(connections().consume(state,"restart-browser"),settings,new TaskCloudProvider.Account(account,label),new TaskProviderOAuth.Tokens("synthetic-private-access","synthetic-private-refresh",store.now().plusSeconds(3600),Set.of("openid","https://www.googleapis.com/auth/tasks"+(writable?"":".readonly"))),List.of(new TaskCloudProvider.RemoteList("list",label,"STANDARD",false,true,list)));
        UUID id=uuid(connected.get("id"));connections().select(id,List.of("list"),etag(connected));
        var selected=store.list("TASK_LIST").stream().filter(value->value.path("connectionId").asText().equals(id.toString())).findFirst().orElseThrow();
        return object().put("connection",id.toString()).put("list",selected.path("id").asText()).put("account",account);
    }
    private void expiredLease(UUID connection){var auth=connections().required(connection);auth.put("leaseUntil",store.now().minusSeconds(1).toString()).put("nextRefresh",store.now().minusSeconds(1).toString()).put("lastAttempt",store.now().minusSeconds(601).toString());connections().save(TaskProviderConnections.AUTH,auth);}
    private ObjectNode stageWrite(String name,boolean acknowledged){
        var fixture=connection(name,true);UUID connection=uuid(fixture.get("connection")),list=uuid(fixture.get("list"));var first=connections().claim(connection,false);var start=scans().begin(first);var original=remote("task","Original","\"g1\"","001");
        scans().acknowledge(first,start,new TaskCloudProvider.Page<>(List.of(original),null,null));var review=scans().review(connection,list);scans().approve(connection,list,etag(review));
        var key=new ExternalTaskReferenceKey("google",TaskCloudPull.repository(fixture.path("account").asText(),"list"),"task");var task=(Task)decode("TASK",store.byReference(key).orElseThrow());
        String title=acknowledged?"Acknowledged update":"Uncertain update";task=store.service().updateTask(new UpdateTask(task.id(),task.version(),task.projectId(),task.parentTaskId(),title,task.url()));
        fixture.put("task",task.id().value().toString());var preview=writes().prepare(connection,list,task.id().value(),TaskProviderWrites.Action.UPDATE);UUID write=uuid(preview.get("id"));writes().confirm(connection,write,etag(preview));
        var claim=connections().claim(connection,false);assertNotNull(claim);writes().next(claim);var sending=writes().sending(claim,write,original);assertEquals("SENDING",sending.path("state").asText());
        if(acknowledged)assertTrue(writes().acknowledge(write,sending.path("attempt").asText(),remote("task",title,"\"g2\"","001")));
        fixture.put("write",write.toString());fixture.put("taskEtag",etag(store.find("TASK",task.id().value()).orElseThrow()));expiredLease(connection);return fixture;
    }
    private void prepare(Path proof)throws Exception{
        String owner="task-restart-"+UUID.randomUUID();var saved=store.transaction(owner,()->{
            var result=object().put("owner",owner).put("preparePid",ProcessHandle.current().pid());var pages=connection("pages",false);UUID connection=uuid(pages.get("connection"));var claim=connections().claim(connection,false);var start=scans().begin(claim);
            var first=scans().acknowledge(claim,start,new TaskCloudProvider.Page<>(List.of(remote("first","First page","\"p1\"","001")),"second-page",null));pages.put("scanId",first.path("scanId").asText());expiredLease(connection);result.set("pages",pages);
            result.set("uncertain",stageWrite("uncertain",false));result.set("acknowledged",stageWrite("acknowledged",true));return result;
        });
        Files.writeString(proof,saved.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }
    private void verify(Path proof)throws Exception{
        var saved=(ObjectNode)parse(Files.readString(proof));assertNotEquals(saved.path("preparePid").asLong(),ProcessHandle.current().pid(),"Acceptance must restart the actual server JVM");
        store.transaction(saved.path("owner").asText(),()->{
            var pages=saved.path("pages");UUID connection=uuid(pages.get("connection")),list=uuid(pages.get("list"));var before=store.find(TaskProviderScans.SCAN,list).orElseThrow();assertEquals("FETCHING",before.path("state").asText());assertEquals(1,before.path("pageCount").asInt());assertEquals("second-page",before.path("next").asText());
            var key=new ExternalTaskReferenceKey("google",TaskCloudPull.repository(pages.path("account").asText(),"list"),"first");assertTrue(store.byReference(key).isEmpty());var claim=connections().claim(connection,false);assertNotNull(claim);var resumed=scans().begin(claim);assertEquals(pages.path("scanId"),resumed.path("scanId"));assertEquals("second-page",resumed.path("next").asText());
            var ready=scans().acknowledge(claim,resumed,new TaskCloudProvider.Page<>(List.of(remote("second","Second page","\"p2\"","002")),null,null));assertTrue(scans().reconcile(claim).path("awaitingImport").asBoolean());var review=scans().review(connection,list);assertEquals(2,review.path("createCount").asInt());scans().approve(connection,list,etag(review));assertTrue(store.byReference(key).isPresent());
            var scan=new TaskCloudPull.Scan("google",pages.path("account").asText(),"list",scans().tasks(ready),store.now());new TaskCloudPull(store,receipts,inputs).apply(connection,list,scan);assertEquals(4,store.list("TASK").size());return null;
        });
        for(String name:List.of("uncertain","acknowledged")){
            var fixture=saved.path(name);UUID connection=uuid(fixture.get("connection")),write=uuid(fixture.get("write")),task=uuid(fixture.get("task"));
            var claim=store.transaction(saved.path("owner").asText(),()->{assertEquals(fixture.path("taskEtag").asText(),etag(store.find("TASK",task).orElseThrow()));var claimed=connections().claim(connection,false);assertNotNull(claimed);return claimed;});
            var writer=new GoogleTasksWriter(new TaskProviderHttp(Set.of("tasks.googleapis.com"),request->{throw new AssertionError("Restart recovery must not issue another provider request: "+request.method());}));
            writes().drain(claim,"synthetic-private-access",writer);writes().drain(claim,"synthetic-private-access",writer);
            store.transaction(saved.path("owner").asText(),()->{
                var row=writes().get(connection,write);assertEquals(name.equals("uncertain")?"UNKNOWN":"DONE",row.path("state").asText());var current=store.find("TASK",task).orElseThrow();assertEquals(name.equals("uncertain")?"Uncertain update":"Acknowledged update",current.path("summary").asText());
                assertEquals(name.equals("uncertain")?"Original":"Acknowledged update",current.path("externalReferences").get(0).path("baseline").path("fields").path("summary").asText());assertEquals(1,current.path("externalReferences").size());assertEquals(4,store.list("TASK").size());return null;
            });
        }
        saved.put("verifyPid",ProcessHandle.current().pid()).put("passed",true);Files.writeString(proof,saved.toString(),StandardCharsets.UTF_8);
    }
    @Test void savedPagesAndWriteOutcomesSurviveANewServerJvm()throws Exception{
        var proof=Path.of(Objects.requireNonNull(System.getenv("TIMEKEEPER_TEST_TASK_RESTART_PROOF")));if(System.getenv("TIMEKEEPER_TEST_TASK_RESTART_PHASE").equals("prepare"))prepare(proof);else verify(proof);
    }
}
