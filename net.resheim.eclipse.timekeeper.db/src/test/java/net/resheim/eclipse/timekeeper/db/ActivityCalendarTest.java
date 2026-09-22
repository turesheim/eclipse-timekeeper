package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.OwnerIdentity;
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.db.report.model.WorkWeek;

class ActivityCalendarTest {
	private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");

	@Test
	void calendarDayBoundariesUseTheRequestedZoneAcrossDst() {
		Task task = new Task("DST work");
		Activity activity = new Activity(task, Instant.parse("2026-03-28T23:30:00Z"));
		activity.setEnd(Instant.parse("2026-03-29T01:30:00Z"));

		assertEquals(Duration.ofHours(2), activity.getDuration(LocalDate.of(2026, 3, 29), OSLO));
		assertEquals(Duration.ofMinutes(30), activity.getDuration(LocalDate.of(2026, 3, 28), ZoneOffset.UTC));
		assertEquals(Duration.ofMinutes(90), activity.getDuration(LocalDate.of(2026, 3, 29), ZoneOffset.UTC));

		Activity fallBackDay = new Activity(task,
				LocalDate.of(2026, 10, 25).atStartOfDay(OSLO).toInstant());
		fallBackDay.setEnd(LocalDate.of(2026, 10, 26).atStartOfDay(OSLO).toInstant());
		assertEquals(Duration.ofHours(25), fallBackDay.getDuration(LocalDate.of(2026, 10, 25), OSLO));
	}

	@Test
	void weeklyGroupingUsesTheRequestedZoneAndExplicitFirstDay() {
		Task task = new Task("Week boundary");
		Activity activity = new Activity(task, Instant.parse("2026-03-29T22:30:00Z"));
		activity.setEnd(Instant.parse("2026-03-29T23:30:00Z"));
		task.addActivity(activity);

		assertEquals(Duration.ofHours(1),
				new WorkWeek(LocalDate.of(2026, 3, 30), Set.of(task), OSLO).getSum());
		assertEquals(Duration.ZERO,
				new WorkWeek(LocalDate.of(2026, 3, 30), Set.of(task), ZoneOffset.UTC).getSum());
		assertEquals(Duration.ofHours(1),
				new WorkWeek(LocalDate.of(2026, 3, 23), Set.of(task), ZoneOffset.UTC).getSum());
	}

	@Test
	void ownerIdentityIsNormalizedAndRequired() {
		assertEquals(new OwnerIdentity("user:alice"), new OwnerIdentity(" user:alice "));
		assertThrows(IllegalArgumentException.class, () -> new OwnerIdentity("  "));
		assertThrows(NullPointerException.class, () -> new OwnerIdentity(null));
	}
}
