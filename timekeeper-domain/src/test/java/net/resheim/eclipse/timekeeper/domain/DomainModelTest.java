package net.resheim.eclipse.timekeeper.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DomainModelTest {
	@Test
	void validationFailuresHaveStableMachineReadableDetails() {
		DomainValidationException failure = assertThrows(DomainValidationException.class,
				() -> new Task(TaskId.random(), Optional.empty(), " ", Optional.empty(), Set.of(), 0));

		assertEquals(FailureCode.VALIDATION, failure.code());
		assertEquals("summary", failure.field());
	}

	@Test
	void activityDurationIsClippedToRequestedInstantRange() {
		Activity activity = new Activity(ActivityId.random(), TaskId.random(), OwnerId.LOCAL,
				Instant.parse("2026-03-28T22:30:00Z"), Optional.of(Instant.parse("2026-03-29T02:30:00Z")),
				"DST deployment", Set.of(), false, 0);

		assertEquals(Duration.ofMinutes(210), activity.duration(
				LocalDate.of(2026, 3, 29),
				ZoneId.of("Europe/Oslo"), Instant.parse("2026-03-29T03:00:00Z")));
		assertEquals(Duration.ofHours(1), activity.durationBetween(Instant.parse("2026-03-29T01:30:00Z"),
				Instant.parse("2026-03-29T03:00:00Z"), Instant.parse("2026-03-29T03:00:00Z")));
	}

	@Test
	void externalReferencesAreProviderScopedAndReplaceable() {
		ExternalTaskReference first = new ExternalTaskReference("jira", "https://issues.example", "TK-12",
				Optional.empty());
		ExternalTaskReference refreshed = new ExternalTaskReference("jira", "https://issues.example", "TK-12",
				"https://issues.example/browse/TK-12");
		Task task = new Task(TaskId.random(), Optional.empty(), "standalone", Optional.empty(), Set.of(), 0)
				.link(first).link(refreshed);

		assertEquals(1, task.externalReferences().size());
		assertEquals(Optional.of("https://issues.example/browse/TK-12"),
				task.externalReferences().iterator().next().externalUrl());
	}
}
