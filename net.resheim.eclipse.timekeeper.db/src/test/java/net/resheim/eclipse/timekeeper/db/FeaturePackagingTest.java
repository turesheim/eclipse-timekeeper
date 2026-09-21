package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** Install-time requirements that bundle resolution in the test target can hide. */
class FeaturePackagingTest {
	@Test
	void requiresTheCompleteCompatibleMylynTasksFeature() throws Exception {
		Document feature = feature();
		var xpath = XPathFactory.newDefaultInstance().newXPath();
		String requirement = "/feature/requires/import[@feature='org.eclipse.mylyn.tasks.feature']";
		assertEquals("1", xpath.evaluate("count(" + requirement + ")", feature));
		assertEquals("4.12.0", xpath.evaluate(requirement + "/@version", feature));
		assertEquals("compatible", xpath.evaluate(requirement + "/@match", feature));
	}

	@Test
	void doesNotForceAnAdditionalSlf4jProviderIntoTheHostIde() throws Exception {
		Document feature = feature();
		var xpath = XPathFactory.newDefaultInstance().newXPath();
		assertEquals("0", xpath.evaluate("count(/feature/plugin[@id='org.eclipse.equinox.slf4j'"
				+ " or @id='slf4j.simple'])", feature));
		assertEquals("1", xpath.evaluate("count(/feature/plugin[@id='slf4j.api'])", feature));
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
