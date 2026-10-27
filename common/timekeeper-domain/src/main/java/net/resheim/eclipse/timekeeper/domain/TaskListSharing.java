package net.resheim.eclipse.timekeeper.domain;

import java.util.List;
import java.util.Optional;

/** Optional provider-disclosed sharing context, separate from list permissions. */
public record TaskListSharing(Optional<String> remoteOwnerId,List<TaskListParticipant> participants) {
    public TaskListSharing {
        remoteOwnerId=remoteOwnerId==null?Optional.empty():remoteOwnerId.map(String::trim).filter(text -> !text.isEmpty());
        participants=participants==null?List.of():List.copyOf(participants);
        if(remoteOwnerId.filter(text -> text.length()>1000 || text.codePoints().anyMatch(Character::isISOControl)).isPresent()
                || participants.size()>100 || participants.stream().map(TaskListParticipant::remotePrincipalId).distinct().count()!=participants.size())
            throw new DomainValidationException("taskListSharing","invalid sharing metadata");
    }
    public static TaskListSharing empty() {return new TaskListSharing(Optional.empty(),List.of());}
}
