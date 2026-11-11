package net.resheim.timekeeper.server;

import java.text.Normalizer;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import net.resheim.eclipse.timekeeper.domain.ProjectId;

/** Resolve only unambiguous names in the current owner's business catalog. */
final class NoteTaskAssignments {
    record Assignment(Optional<ProjectId> project,UUID client){}
    private final List<JsonNode> clients,projects;
    NoteTaskAssignments(ServerStore store){clients=store.list("CLIENT");projects=store.list("PROJECT");}
    private static String key(String name){return Normalizer.normalize(name.replace('_',' ').strip(),Normalizer.Form.NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);}
    Optional<Assignment> resolve(List<String> tokens){
        var selectedClients=new HashSet<UUID>();var selectedProjects=new HashSet<UUID>();
        for(String token:tokens){
            boolean clientOnly=token.startsWith("client:"),projectOnly=token.startsWith("project:");String name=key(clientOnly||projectOnly?token.substring(token.indexOf(':')+1):token);
            var matchingClients=projectOnly?List.<JsonNode>of():clients.stream().filter(value->key(value.path("name").asText()).equals(name)).toList();
            var matchingProjects=clientOnly?List.<JsonNode>of():projects.stream().filter(value->key(value.path("name").asText()).equals(name)).toList();
            if(matchingClients.size()+matchingProjects.size()!=1)continue;
            if(!matchingClients.isEmpty())selectedClients.add(JsonValues.uuid(matchingClients.getFirst().get("id")));
            else selectedProjects.add(JsonValues.uuid(matchingProjects.getFirst().get("id")));
        }
        if(selectedClients.size()>1||selectedProjects.size()>1)return Optional.empty();
        if(!selectedProjects.isEmpty()){
            UUID project=selectedProjects.iterator().next();var value=projects.stream().filter(item->item.path("id").asText().equals(project.toString())).findFirst().orElseThrow();UUID client=JsonValues.uuid(value.get("clientId"));
            if(!selectedClients.isEmpty()&&!selectedClients.contains(client))return Optional.empty();
            return Optional.of(new Assignment(Optional.of(new ProjectId(project)),null));
        }
        return selectedClients.isEmpty()?Optional.empty():Optional.of(new Assignment(Optional.empty(),selectedClients.iterator().next()));
    }
}
