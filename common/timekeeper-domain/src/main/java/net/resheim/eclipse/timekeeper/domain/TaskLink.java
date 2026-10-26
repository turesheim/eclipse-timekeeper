package net.resheim.eclipse.timekeeper.domain;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A local link or a provider resource, kept distinct from the task's primary URL. */
public record TaskLink(UUID id, TaskLinkKind kind, String url, Optional<String> title,
		Optional<String> providerId, Optional<String> remoteId) {
	public TaskLink {
		id=Values.required(id,"taskLinkId");kind=Values.required(kind,"taskLinkKind");
		url=Values.required(url,"taskLinkUrl").trim();
		try {
			URI uri=URI.create(url);
			if(url.length()>4096 || !uri.isAbsolute() || !Set.of("http","https","mailto").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))
					|| (uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")) && uri.getHost()==null
					|| uri.getUserInfo()!=null || url.codePoints().anyMatch(Character::isISOControl))
				throw new IllegalArgumentException();
		} catch(IllegalArgumentException error) { throw new DomainValidationException("taskLinkUrl","invalid task link URL"); }
		title=title==null?Optional.empty():title.map(String::trim).filter(value -> !value.isEmpty());
		providerId=providerId==null?Optional.empty():providerId.map(String::trim).filter(value -> !value.isEmpty());
		remoteId=remoteId==null?Optional.empty():remoteId.map(String::trim).filter(value -> !value.isEmpty());
		if(title.filter(value -> value.length()>200 || value.codePoints().anyMatch(Character::isISOControl)).isPresent()
				|| providerId.filter(value -> value.length()>200 || value.codePoints().anyMatch(Character::isISOControl)).isPresent()
				|| remoteId.filter(value -> value.length()>1000 || value.codePoints().anyMatch(Character::isISOControl)).isPresent())
			throw new DomainValidationException("taskLink","invalid link metadata");
		if(kind==TaskLinkKind.LOCAL && (providerId.isPresent() || remoteId.isPresent())
				|| kind!=TaskLinkKind.LOCAL && providerId.isEmpty()
				|| kind==TaskLinkKind.PROVIDER_LINKED && remoteId.isEmpty())
			throw new DomainValidationException("taskLink","provider identity does not match link kind");
	}
}
