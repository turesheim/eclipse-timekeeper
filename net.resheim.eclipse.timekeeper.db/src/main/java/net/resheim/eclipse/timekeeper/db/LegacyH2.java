package net.resheim.eclipse.timekeeper.db;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scoped, isolated H2 1.4.194 reader for explicit offline migrations and fixtures.
 * The old JAR is not on the application/OSGi class path. Closing the connection
 * also deregisters its driver, closes the loader and removes its temporary JAR.
 * Never used by normal startup. Production callers use read-only backup copies.
 */
public final class LegacyH2 {
	private LegacyH2() { }

	public static Connection open(String jdbcUrl) throws SQLException {
		if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:h2:")
				|| jdbcUrl.startsWith("jdbc:h2:tcp:") || jdbcUrl.startsWith("jdbc:h2:ssl:")
				|| jdbcUrl.matches("(?is).*;\\s*(INIT|AUTO_SERVER)\\s*=.*")) {
			throw new SQLException("The legacy driver is limited to explicit offline database copies.");
		}
		Path jar = null;
		URLClassLoader loader = null;
		Class<?> driverClass = null;
		Connection opened = null;
		try {
			jar = Files.createTempFile("timekeeper-legacy-h2-", ".jar");
			try (var stream = LegacyH2.class.getResourceAsStream("/lib/h2-1.4.194.jar")) {
				if (stream == null) throw new IOException("Missing bundled legacy H2 migration reader");
				Files.copy(stream, jar, StandardCopyOption.REPLACE_EXISTING);
			}
			loader = new URLClassLoader(new java.net.URL[] { jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
			driverClass = Class.forName("org.h2.Driver", true, loader);
			Driver driver = (Driver) driverClass.getConstructor().newInstance();
			Properties credentials = new Properties();
			credentials.setProperty("user", "sa");
			credentials.setProperty("password", "");
			// Do not retain an isolated loader through a JVM shutdown hook after close.
			opened = driver.connect(jdbcUrl + ";DB_CLOSE_ON_EXIT=FALSE", credentials);
			if (opened == null) throw new SQLException("Unsupported legacy database URL");
			Connection connection = opened;
			URLClassLoader scopedLoader = loader;
			Path scopedJar = jar;
			Class<?> scopedDriver = driverClass;
			AtomicBoolean closed = new AtomicBoolean();
			return (Connection) Proxy.newProxyInstance(LegacyH2.class.getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						if (method.getName().equals("close")) {
							if (closed.compareAndSet(false, true)) {
								try { connection.close(); }
								finally { cleanup(scopedDriver, scopedLoader, scopedJar); }
							}
							return null;
						}
						try { return method.invoke(connection, args); }
						catch (InvocationTargetException failure) { throw failure.getCause(); }
					});
		} catch (Exception failure) {
			try { if (opened != null) opened.close(); }
			catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
			try { cleanup(driverClass, loader, jar); }
			catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
			throw new SQLException("Could not read the legacy H2 database copy. Keep the original and its backup.", failure);
		}
	}

	private static void cleanup(Class<?> driver, URLClassLoader loader, Path jar) throws SQLException {
		try {
			try { if (driver != null) driver.getMethod("unload").invoke(null); }
			finally {
				try { if (loader != null) loader.close(); }
				finally { if (jar != null) Files.deleteIfExists(jar); }
			}
		} catch (Exception failure) {
			throw new SQLException("Could not release the isolated legacy reader", failure);
		}
	}
}
