package net.resheim.eclipse.timekeeper.db;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Minimal JDBC-only child: also runs with the old driver and no modern H2 classes. */
public final class StorageProcessClient {
	private StorageProcessClient() { }

	public static void main(String[] args) throws Exception {
		Class.forName("org.h2.Driver");
		try (var connection = DriverManager.getConnection(args[0], "sa", "")) {
			throw new AssertionError("An incompatible or exclusively locked connection was accepted");
		} catch (SQLException failure) {
			int expected = Integer.parseInt(args[1]);
			if (failure.getErrorCode() != expected) throw failure;
			System.out.println("EXPECTED_SQL_ERROR=" + expected);
		}
	}
}
