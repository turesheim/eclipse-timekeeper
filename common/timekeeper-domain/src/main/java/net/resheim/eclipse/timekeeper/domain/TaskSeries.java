package net.resheim.eclipse.timekeeper.domain;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** RFC 5545 rule parts, with the end condition kept separate for provider mapping. */
public record TaskSeries(String rule, TaskRecurrenceEnd end, Optional<TaskMoment> until, Optional<Integer> count) {
	public TaskSeries {
		rule=Values.required(rule,"recurrenceRule").trim();
		if(rule.length()>4096 || !rule.startsWith("FREQ=")) throw new DomainValidationException("recurrenceRule","invalid recurrence rule");
		Set<String> parts=new HashSet<>();
		for(String part:rule.split(";",-1)) {
			int equals=part.indexOf('=');
			if(equals<1 || equals==part.length()-1 || !part.matches("[A-Z]+=[A-Z0-9,+-]+") || !parts.add(part.substring(0,equals)))
				throw new DomainValidationException("recurrenceRule","invalid or duplicate rule part");
			if(part.startsWith("INTERVAL=")) {
				try { if(Integer.parseInt(part.substring(equals+1))<1) throw new NumberFormatException(); }
				catch(NumberFormatException e) { throw new DomainValidationException("recurrenceRule","interval must be positive"); }
			}
		}
		if(!rule.matches("FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;.*)?") || parts.contains("UNTIL") || parts.contains("COUNT"))
			throw new DomainValidationException("recurrenceRule","use a supported frequency and a separate end condition");
		end=Values.required(end,"recurrenceEnd");
		until=until==null?Optional.empty():until;
		count=count==null?Optional.empty():count;
		if(switch(end) {
			case NEVER -> until.isPresent() || count.isPresent();
			case UNTIL -> until.isEmpty() || count.isPresent();
			case COUNT -> count.isEmpty() || until.isPresent() || count.get()<1;
		}) throw new DomainValidationException("recurrenceEnd","end condition and value do not match");
	}
}
