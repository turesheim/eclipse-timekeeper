package net.resheim.eclipse.timekeeper.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/** Provider assignment and read-only source context, separate from the task's own URL. */
public record TaskAssignment(Optional<String> assigneeId,Optional<String> assigneeName,
        Optional<String> assignedById,Optional<String> assignedByName,
        Optional<String> sourceId,Optional<String> sourceUrl,boolean writable) {
    public TaskAssignment {
        assigneeId=clean(assigneeId,1000);assigneeName=clean(assigneeName,200);
        assignedById=clean(assignedById,1000);assignedByName=clean(assignedByName,200);
        sourceId=clean(sourceId,1000);sourceUrl=clean(sourceUrl,4096);
        if(assigneeId.isEmpty() && assigneeName.isEmpty() && sourceId.isEmpty() && sourceUrl.isEmpty())
            throw new DomainValidationException("assignment","assignee or source context is required");
        sourceUrl.ifPresent(url -> {
            try {
                URI uri=URI.create(url);
                if(!uri.isAbsolute() || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost()==null || uri.getUserInfo()!=null)
                    throw new IllegalArgumentException();
            } catch(IllegalArgumentException error) {throw new DomainValidationException("assignment","invalid source URL");}
        });
    }
    private static Optional<String> clean(Optional<String> value,int limit) {
        var result=value==null?Optional.<String>empty():value.map(String::trim).filter(text -> !text.isEmpty());
        if(result.filter(text -> text.length()>limit || text.codePoints().anyMatch(Character::isISOControl)).isPresent())
            throw new DomainValidationException("assignment","invalid assignment metadata");
        return result;
    }
}
