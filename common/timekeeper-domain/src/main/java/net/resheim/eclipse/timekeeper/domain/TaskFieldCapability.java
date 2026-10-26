package net.resheim.eclipse.timekeeper.domain;

import java.util.Optional;

/** Effective read, write, and delete support observed by one connector. */
public record TaskFieldCapability(boolean readable, boolean writable, boolean deletable, Optional<String> note) {
	public TaskFieldCapability {
		note=note==null?Optional.empty():note.map(String::trim).filter(value -> !value.isEmpty());
		if(writable && !readable || deletable && !writable)
			throw new DomainValidationException("fieldCapability","write requires read and delete requires write");
		if(note.isPresent() && (note.get().length()>500 || note.get().codePoints().anyMatch(Character::isISOControl)))
			throw new DomainValidationException("capabilityNote","invalid capability note");
	}
}
