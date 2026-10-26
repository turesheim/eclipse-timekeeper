package net.resheim.eclipse.timekeeper.domain;

import java.util.Optional;

/** Provider principal metadata; this is not a Timekeeper account grant. */
public record TaskListParticipant(String remotePrincipalId,Optional<String> displayName,TaskListMemberRole role) {
    public TaskListParticipant {
        remotePrincipalId=Values.required(remotePrincipalId,"remotePrincipalId");
        displayName=displayName==null?Optional.empty():displayName.map(String::trim).filter(text -> !text.isEmpty());
        role=Values.required(role,"role");
        if(remotePrincipalId.length()>1000 || remotePrincipalId.codePoints().anyMatch(Character::isISOControl)
                || displayName.filter(text -> text.length()>200 || text.codePoints().anyMatch(Character::isISOControl)).isPresent())
            throw new DomainValidationException("taskListParticipant","invalid participant metadata");
    }
}
