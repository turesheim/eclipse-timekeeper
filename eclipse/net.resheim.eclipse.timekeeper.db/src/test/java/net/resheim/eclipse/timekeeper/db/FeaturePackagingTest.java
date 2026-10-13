package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** Install-time requirements that bundle resolution in the test target can hide. */
class FeaturePackagingTest {
	@Test
	void optionalAdapterRequiresTheCompleteMylynTasksFeatureAndBase() throws Exception {
		try (var stream = getClass().getResourceAsStream("/packaging-fixture/mylyn/feature.xml")) {
			assertNotNull(stream);
			var feature = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(stream);
			var xpath = XPathFactory.newDefaultInstance().newXPath();
			String mylyn = "/feature/requires/import[@feature='org.eclipse.mylyn.tasks.feature']";
			assertEquals("4.12.0", xpath.evaluate(mylyn + "/@version", feature));
			assertEquals("compatible", xpath.evaluate(mylyn + "/@match", feature));
			assertEquals("1", xpath.evaluate("count(/feature/requires/import[@feature='net.resheim.eclipse.timekeeper'])", feature));
			assertEquals("1", xpath.evaluate("count(/feature/plugin[@id='net.resheim.eclipse.timekeeper.mylyn'])", feature));
		}
	}

	@Test
	void baseFeatureDoesNotRequireMylyn() throws Exception {
		Document feature = feature();
		var xpath = XPathFactory.newDefaultInstance().newXPath();
		String requirement = "/feature/requires/import[@feature='org.eclipse.mylyn.tasks.feature']";
		assertEquals("0", xpath.evaluate("count(" + requirement + ")", feature));
	}

	@Test
	void doesNotForceAnAdditionalSlf4jProviderIntoTheHostIde() throws Exception {
		Document feature = feature();
		var xpath = XPathFactory.newDefaultInstance().newXPath();
		assertEquals("0", xpath.evaluate("count(/feature/plugin[@id='org.eclipse.equinox.slf4j'"
				+ " or @id='slf4j.simple'])", feature));
		assertEquals("1", xpath.evaluate("count(/feature/plugin[@id='slf4j.api'])", feature));
	}

	@Test
	void packagesTheServiceBundlesAndEmbeddedDeclarativeService() throws Exception {
		Document feature = feature();
		var xpath = XPathFactory.newDefaultInstance().newXPath();
		assertEquals("1", xpath.evaluate("count(/feature/plugin[@id='net.resheim.eclipse.timekeeper.domain'])", feature));
		assertEquals("1", xpath.evaluate("count(/feature/plugin[@id='net.resheim.eclipse.timekeeper.service'])", feature));

		try (var stream = getClass().getResourceAsStream(
				"/packaging-fixture/database-bundle/OSGI-INF/embedded-timekeeper-service.xml")) {
			assertNotNull(stream);
			Document component = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(stream);
			assertEquals("net.resheim.eclipse.timekeeper.service.TimekeeperService",
					component.getElementsByTagName("provide").item(0).getAttributes()
							.getNamedItem("interface").getNodeValue());
		}
		try (var stream = getClass().getResourceAsStream(
				"/packaging-fixture/database-bundle/META-INF/MANIFEST.MF")) {
			assertNotNull(stream);
			String manifest = new BufferedReader(new java.io.InputStreamReader(stream)).lines()
					.reduce("", (left, right) -> left + "\n" + right);
			org.junit.jupiter.api.Assertions.assertFalse(manifest.contains("org.eclipse.mylyn"));
			assertTrue(manifest.contains("Service-Component: OSGI-INF/embedded-timekeeper-service.xml"));
		}
	}

	private Document feature() throws Exception {
		// Use the JDK parser, not older XML providers contributed by target bundles.
		var factory = DocumentBuilderFactory.newDefaultInstance();
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		try (var stream = getClass().getResourceAsStream("/packaging-fixture/feature.xml")) {
			assertNotNull(stream);
			return factory.newDocumentBuilder().parse(stream);
		}
	}
}
