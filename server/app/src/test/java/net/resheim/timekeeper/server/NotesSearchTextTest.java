package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class NotesSearchTextTest {
    @Test void proseCodeListsQuotesAndLinkLabelsRemainSeparateFromSyntaxAndFrontmatter(){
        String source="\ufeff---\r\nprivate: excludedfrontmatter\r\n---\r\n\r\n# **Cafe\u0301** \u0060foo_bar\u0060\r\n\r\nProse [label](https://not-indexed.invalid/destination) ![alt words](asset.png).\r\n\r\n- First item\r\n- Second ~~old~~ item\r\n\r\n> Quoted paragraph\r\n\r\n~~~shell\r\nkubectl exec service.cluster.local\r\n~~~\r\n";
        byte[] original=source.getBytes(StandardCharsets.UTF_8),copy=original.clone();var projection=NotesSearchText.extract(original);
        assertEquals("Café foo_bar",projection.title());assertTrue(projection.blocks().contains("First item"));assertTrue(projection.blocks().contains("Second old item"));
        assertTrue(projection.blocks().contains("Quoted paragraph"));assertTrue(projection.blocks().contains("kubectl exec service.cluster.local"));
        assertTrue(projection.body().contains("Prose label alt words."));assertFalse(projection.body().contains("excludedfrontmatter"));assertFalse(projection.body().contains("not-indexed.invalid"));
        assertFalse(projection.body().contains("~~~"));assertArrayEquals(copy,original);assertFalse(projection.toString().contains("Quoted"));
    }
    @Test void htmlTagsCommentsAndEntitiesAreProjectedWithoutResourceAccess(){
        var projection=NotesSearchText.extract(("<div title='ignored > attribute'>Visible &amp; ordinary &#x1f680; text<br>Second line</div>\n<!-- secret comment -->\n\n<a href='https://never-fetch.invalid'>Safe label</a>").getBytes(StandardCharsets.UTF_8));
        assertTrue(projection.body().contains("Visible & ordinary 🚀 text"));assertTrue(projection.body().contains("Second line"));assertTrue(projection.body().contains("Safe label"));
        assertFalse(projection.body().contains("attribute"));assertFalse(projection.body().contains("secret comment"));assertFalse(projection.body().contains("never-fetch.invalid"));
    }
    @Test void derivedTitleReusesDocumentHeadingRulesAndInvalidEncodingFailsWithoutEchoingSource(){
        String source="> # Quoted heading\n\n~~~markdown\n# Code example\n~~~\n\n## Actual title\n\nBody";
        assertEquals(NoteTitles.heading(source),NotesSearchText.extract(source.getBytes(StandardCharsets.UTF_8)).title());
        assertThrows(IllegalArgumentException.class,()->NotesSearchText.extract(new byte[]{(byte)0xc3,0x28}));
        assertEquals(List.of(),NotesSearchText.extract("---\nprivate: metadata\n---\n".getBytes(StandardCharsets.UTF_8)).blocks());
    }
}
