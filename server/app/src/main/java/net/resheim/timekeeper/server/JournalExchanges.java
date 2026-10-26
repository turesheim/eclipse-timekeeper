package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Completed bidirectional batches. Missing input has meaning only with its explicit common token. */
final class JournalExchanges {
    private final ServerStore store;
    private final JournalState state;
    JournalExchanges(ServerStore store){this.store=store;state=new JournalState(store);}
    static void validate(JsonNode manifest,ObjectNode lease){
        ContractInputs.pathId(JournalGatewayResource.text(manifest,"clientStateId",36));
        if(!manifest.path("exchangeId").asText().equals(lease.path("clientExchangeId").asText()))throw ApiFailure.validation();
        var absent=manifest.path("absent");if(!absent.isArray()||absent.size()+manifest.path("files").size()+manifest.path("errors").size()>50)throw ApiFailure.validation();
        var names=new HashSet<String>();
        for(String group:List.of("files","absent","errors"))for(var entry:manifest.path(group)){
            String name=entry.path("name").asText();if(!name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.md")||!names.add(name))throw ApiFailure.validation();
            if(group.equals("errors"))continue;
            if(group.equals("absent"))JournalGatewayResource.fields(entry,"name","baseToken");
            String token=JournalGatewayResource.text(entry,"baseToken",36);if(!token.isEmpty())ContractInputs.pathId(token);
        }
    }
    ObjectNode seal(ObjectNode lease,JsonNode manifest,Map<String,byte[]> files){
        UUID id=uuid(lease.get("id"));int count=files.size()+manifest.path("absent").size();
        if(state.list(JOURNAL_ITEM).stream().filter(item->item.has("sourceId")).count()+state.list(JOURNAL_OFFER).stream().filter(item->item.has("sourceId")).count()+count>500)throw new ApiFailure(409,"JOURNAL_PENDING_LIMIT");
        var batch=state.insert(JOURNAL_BATCH,id,object().put("bidirectional",true).put("deviceId",lease.path("deviceId").asText()).put("clientStateId",manifest.path("clientStateId").asText())
                .put("sequence",lease.path("sequence").asLong()).put("state",count==0?"PROCESSED":"PENDING").put("receivedAt",store.now().toString()).put("files",count));
        var names=batch.putArray("names");
        for(String group:List.of("files","absent"))for(var entry:manifest.path(group)){
            String name=entry.path("name").asText();names.add(name);UUID item=ImportLedger.identifier("journal-item",id.toString(),name);boolean exists=group.equals("files");
            state.insert(JOURNAL_SOURCE,item,object().put("data",exists?Base64.getEncoder().encodeToString(files.get(name)):""));
            state.insert(JOURNAL_ITEM,item,object().put("batchId",id.toString()).put("deviceId",lease.path("deviceId").asText()).put("sequence",lease.path("sequence").asLong())
                    .put("filename",name).put("baseToken",entry.path("baseToken").asText()).put("exists",exists).put("bidirectional",true).put("state","PENDING").put("sourceId",item.toString()));
        }
        return state.update(JOURNAL_BATCH,batch);
    }
    ObjectNode process(ObjectNode item,ObjectNode batch,String configuration){
        UUID id=uuid(item.get("id")),sourceId=uuid(item.get("sourceId"));
        try{
            byte[] bytes=item.path("exists").asBoolean()?Base64.getDecoder().decode(state.required(JOURNAL_SOURCE,sourceId).path("data").asText()):null;
            var offered=new JournalReconciliation(store).offer(uuid(batch.get("id")),uuid(batch.get("deviceId")),uuid(batch.get("clientStateId")),batch.path("sequence").asLong(),item.path("filename").asText(),item.path("baseToken").asText(),bytes!=null,bytes,configuration);
            if(!offered.path("state").asText().equals("CONFLICT")&&offered.path("expectedHash").asText().equals(offered.path("targetHash").asText())){
                var device=state.required(JOURNAL_DEVICE,uuid(item.get("deviceId")));state.update(JOURNAL_DEVICE,device.put("lastImportAt",store.now().toString()));
            }
            item.put("state","EXCHANGED").put("offerId",offered.path("id").asText());state.remove(JOURNAL_SOURCE,state.required(JOURNAL_SOURCE,sourceId));item.remove("sourceId");
        }catch(JournalMarkdown.Invalid invalid){item.put("state","ERROR").put("error",invalid.code);}
        catch(ApiFailure rejected){
            if(rejected.status>=500)throw rejected;
            item.put("state",rejected.code.equals("JOURNAL_SUPERSEDED")?"SUPERSEDED":"ERROR").put("error",rejected.code);
        }
        if(item.has("sourceId")&&!item.path("state").asText().equals("ABORTED")){
            state.remove(JOURNAL_SOURCE,state.required(JOURNAL_SOURCE,sourceId));item.remove("sourceId");
        }
        item=state.update(JOURNAL_ITEM,item.put("processedAt",store.now().toString()));
        if(state.list(JOURNAL_ITEM).stream().noneMatch(value->value.path("batchId").asText().equals(batch.path("id").asText())&&value.path("state").asText().equals("PENDING")))
            state.update(JOURNAL_BATCH,state.required(JOURNAL_BATCH,uuid(batch.get("id"))).put("state","PROCESSED").put("processedAt",store.now().toString()));
        return JournalImport.summary(item);
    }
    private ObjectNode exchange(UUID device,UUID exchange,String configuration){
        new JournalDevices(store).authorizeBidirectional(device);
        var lease=state.list(JOURNAL_LEASE).stream().filter(value->value.path("deviceId").asText().equals(device.toString())&&value.path("clientExchangeId").asText().equals(exchange.toString())&&value.path("state").asText().equals("COMPLETED")).findFirst().orElseThrow(ApiFailure::notFound);
        var account=AccountStore.account(store.current().connection(),store.owner(),true);
        if(!lease.path("configurationEpoch").asText().equals(configuration)||lease.path("syncEpoch").asLong()!=new JournalDevices(store).settings().path("epoch").asLong()||lease.path("accountVersion").asLong()!=account.path("version").asLong())throw new ApiFailure(409,"JOURNAL_AUTHORIZATION_CHANGED");
        return lease;
    }
    ObjectNode results(UUID device,UUID exchange,String configuration){
        var lease=exchange(device,exchange,configuration);var batch=state.required(JOURNAL_BATCH,uuid(lease.get("id")));var result=object().put("format",2).put("exchangeId",exchange.toString());var offers=result.putArray("offers");var errors=result.putArray("errors");
        var items=state.list(JOURNAL_ITEM);int pending=0;
        for(var name:batch.path("names")){
            UUID id=ImportLedger.identifier("journal-offer",lease.path("id").asText(),name.asText());var offer=state.find(JOURNAL_OFFER,id);
            if(offer.isPresent()){var summary=JournalReconciliation.summary(offer.get());summary.remove(List.of("candidateEtag","deviceId"));offers.add(summary);continue;}
            var item=items.stream().filter(value->value.path("batchId").asText().equals(lease.path("id").asText())&&value.path("filename").asText().equals(name.asText())).findFirst();
            if(item.isPresent()&&item.get().path("state").asText().equals("PENDING"))pending++;
            else errors.add(object().put("name",name.asText()).put("code",item.map(value->value.path("error").asText("JOURNAL_EXCHANGE_EXPIRED")).orElse("JOURNAL_EXCHANGE_EXPIRED")));
        }
        for(var item:items)if(item.path("batchId").asText().equals(lease.path("id").asText())&&!item.path("bidirectional").asBoolean()&&item.has("error"))errors.add(object().put("name",item.path("filename").asText()).put("code",item.path("error").asText()));
        return result.put("pending",pending);
    }
    byte[] outbound(UUID device,UUID exchange,UUID offer,String configuration){
        var lease=exchange(device,exchange,configuration);var value=state.required(JOURNAL_OFFER,offer);
        if(!value.path("batchId").asText().equals(lease.path("id").asText()))throw ApiFailure.notFound();
        return new JournalReconciliation(store).outbound(offer,device,configuration);
    }
    ObjectNode inventory(UUID device,String after){
        new JournalDevices(store).authorizeBidirectional(device);UUID cursor=after==null||after.isEmpty()?DayNotes.id(DayNotes.date("0001-01-01")):DayNotes.id(JournalMarkdown.date(after));
        var result=object();var names=result.putArray("names");
        try(var statement=store.statement("SELECT id,payload FROM tk_resource WHERE owner_id=? AND entity_type=? AND id"+(after==null||after.isEmpty()?">=":">")+"? ORDER BY id LIMIT 51",store.owner(),DayNotes.TYPE,cursor);var rows=statement.executeQuery()){
            int count=0;String last=null;while(rows.next()){if(count++==50){result.put("next",last);break;}String name=parse(rows.getString(2)).path("date").asText()+".md";JournalMarkdown.date(name);names.add(name);last=name;}
        }catch(java.sql.SQLException failure){throw new ApiFailure(503,"UNAVAILABLE");}
        return result;
    }
}
