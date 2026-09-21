package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Label reads must be harmless before startup and after connection shutdown. */
class DatabaseAvailabilityTest {
	private EntityManager previous;
	private EntityManager temporary;

	@BeforeEach
	void rememberConnection() {
		previous = TimekeeperPlugin.getDefault().getEntityManager();
	}

	@AfterEach
	void restoreConnection() {
		TimekeeperPlugin.setEntityManager(previous);
		PersistenceHelper.close(temporary);
	}

	@Test
	void labelsAreEmptyWithoutAConnection() {
		TimekeeperPlugin.setEntityManager(null);
		assertEquals(0, TimekeeperPlugin.getLabels().count());
	}

	@Test
	void labelsAreEmptyAfterTheConnectionCloses() {
		temporary = PersistenceHelper.getEntityManager();
		TimekeeperPlugin.setEntityManager(temporary);
		PersistenceHelper.close(temporary);
		assertEquals(0, TimekeeperPlugin.getLabels().count());
	}
}
