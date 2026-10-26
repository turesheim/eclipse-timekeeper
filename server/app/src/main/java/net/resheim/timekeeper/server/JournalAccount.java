package net.resheim.timekeeper.server;

import static net.resheim.timekeeper.server.JsonValues.*;
import static net.resheim.timekeeper.server.JournalState.Kind.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Public account projections contain no incoming Markdown, broker tokens or private paths. */
final class JournalAccount {
    private final ServerStore store;
    private final JournalConfiguration configuration;
    JournalAccount(ServerStore store,JournalConfiguration configuration){this.store=store;this.configuration=configuration;}
    ObjectNode status(TokenRegistry.Principal principal){
        var devices=new JournalDevices(store);var state=new JournalState(store);var settings=devices.settings();
        var result=object().put("mechanism","RSYNC_SSH").put("direction",settings.path("bidirectional").asBoolean()?"BIDIRECTIONAL":"CLIENT_TO_SERVER").put("canManage",AccountStore.accountSession(store.current().connection(),principal));
        result.set("settings",object().put("enabled",settings.path("enabled").asBoolean()).put("bidirectional",settings.path("bidirectional").asBoolean()).put("etag",etag(settings)));
        var deployment=result.putObject("deployment").put("ready",false);
        try{
            var configured=configuration.load();var binding=configured.owner(store.owner());
            if(binding.isEmpty())deployment.put("code","JOURNAL_ADMIN_ACCOUNT_NOT_PROVISIONED");
            else{deployment.put("ready",true);connection(deployment,configured,binding.get());}
        }catch(JournalConfiguration.Unavailable unavailable){deployment.put("code",unavailable.code);}
        var credentials=result.putArray("devices");devices.list().forEach(credentials::add);
        var health=result.putObject("health");for(String count:List.of("pending","conflicts","errors","aborted","receiving","outgoing"))health.put(count,0);
        for(var device:devices.list())for(String timestamp:List.of("lastVerifiedAt","lastTransferAt","lastImportAt","lastSyncAt"))if(device.has(timestamp)&&(!health.has(timestamp)||java.time.Instant.parse(device.path(timestamp).asText()).isAfter(java.time.Instant.parse(health.path(timestamp).asText()))))health.set(timestamp,device.get(timestamp));
        var items=state.list(JOURNAL_ITEM).stream().filter(item->!item.path("state").asText().equals("EXCHANGED")).toList();var latest=new HashMap<String,ObjectNode>();
        for(var item:items){String key=item.path("deviceId").asText()+"/"+item.path("filename").asText();var prior=latest.get(key);if(prior==null||prior.path("sequence").asLong()<item.path("sequence").asLong())latest.put(key,item);}
        var visible=result.putArray("items");
        items.stream().filter(item->item.has("sourceId")||latest.get(item.path("deviceId").asText()+"/"+item.path("filename").asText())==item)
                .sorted(Comparator.comparing((ObjectNode item)->item.path("processedAt").asText()).reversed()).limit(700).forEach(item->{
                    var summary=JournalImport.summary(item);summary.put("canDownload",item.has("sourceId"));visible.add(summary);
                    String counter=switch(item.path("state").asText()){case "PENDING"->"pending";case "CONFLICT"->"conflicts";case "ERROR"->"errors";case "ABORTED"->"aborted";default->null;};
                    if(counter!=null)health.put(counter,health.path(counter).asInt()+1);
                });
        for(var lease:state.list(JOURNAL_LEASE))if(lease.path("state").asText().equals("RECEIVING")&&java.time.Instant.parse(lease.path("expiresAt").asText()).isAfter(store.now()))health.put("receiving",health.path("receiving").asInt()+1);
        var reconciliation=new JournalReconciliation(store);
        for(var offer:state.list(JOURNAL_OFFER)){
            visible.add(reconciliation.management(offer));String counter=switch(offer.path("state").asText()){case "OFFERED"->"outgoing";case "CONFLICT"->"conflicts";case "ABORTED"->"aborted";default->null;};if(counter!=null)health.put(counter,health.path(counter).asInt()+1);
        }
        return result;
    }
    ObjectNode profile(UUID device){
        var devices=new JournalDevices(store);var credential=devices.authorize(device);boolean bidi=credential.path("bidirectional").asBoolean();if(bidi)devices.authorizeBidirectional(device);var deployment=configuration.load();var binding=deployment.owner(store.owner()).orElseThrow(()->new ApiFailure(503,"JOURNAL_ADMIN_ACCOUNT_NOT_PROVISIONED"));
        var profile=object().put("format",bidi?2:1).put("mechanism","RSYNC_SSH").put("direction",bidi?"BIDIRECTIONAL":"CLIENT_TO_SERVER").put("deviceId",device.toString()).put("minimumRsyncVersion","3.5.1");
        if(bidi)profile.put("exchangeProtocol",1).put("uploadPrefix","incoming/").put("downloadPrefix","outgoing/").put("maxExchangeFiles",50);
        connection(profile,deployment,binding);profile.put("localFolder","/path/to/Daily").put("identityFile","~/.ssh/timekeeper_journal");return profile;
    }
    private static void connection(ObjectNode result,JournalConfiguration.Deployment deployment,JournalConfiguration.Binding binding){
        String alias="timekeeper-journal-"+hash(deployment.hostname()+":"+deployment.port()).substring(0,24);
        result.put("hostname",deployment.hostname()).put("port",deployment.port()).put("username",binding.username()).put("remotePath",".").put("hostKey",deployment.hostKey().publicKey())
                .put("hostKeyFingerprint",deployment.hostKey().fingerprint()).put("hostAlias",alias).put("knownHostsEntry",alias+" "+deployment.hostKey().publicKey());
    }
}
