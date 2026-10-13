package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;

class ActivityLabelTest {
	@Test
	void newActivityCanToggleAnUnsavedLabel() {
		Activity activity = new Activity();
		ActivityLabel label = new ActivityLabel("Billable", "0,128,0");
		activity.toggleLabel(label);
		assertEquals(1, activity.getLabels().size());
		activity.toggleLabel(label);
		assertTrue(activity.getLabels().isEmpty());
	}

	@Test
	void unsavedLabelsHaveDistinctIdentities() {
		Activity activity = new Activity();
		ActivityLabel first = new ActivityLabel("Billable", "0,128,0");
		ActivityLabel second = new ActivityLabel("Internal", "128,128,128");
		activity.toggleLabel(first);
		activity.toggleLabel(second);
		assertEquals(2, activity.getLabels().size());
		activity.toggleLabel(first);
		assertEquals(second, activity.getLabels().get(0));
	}

	@Test
	void copiedLabelTogglesTheStoredInstanceById() {
		EntityManager manager = PersistenceHelper.getEntityManager();
		try {
			ActivityLabel label = new ActivityLabel("Billable", "0,128,0");
			manager.getTransaction().begin();
			manager.persist(label);
			manager.getTransaction().commit();
			Activity activity = new Activity();
			activity.toggleLabel(label);
			activity.toggleLabel(new ActivityLabel(label));
			assertTrue(activity.getLabels().isEmpty());
		} finally {
			PersistenceHelper.close(manager);
		}
	}
}
