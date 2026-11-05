package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NoteTitlesTest {
    @Test void usesTheFirstNonemptyDocumentHeadingAtAnyLevel(){
        assertEquals("First heading",NoteTitles.heading("Introduction\n\n### First heading ###\n\n# Later title\n"));
        assertEquals("Setext heading",NoteTitles.heading("Introduction\n\nSetext heading\n==============\n"));
        assertEquals("Usable",NoteTitles.heading("#\n\n###### Usable\n"));
    }
    @Test void ignoresFrontmatterCodeAndQuotedExamples(){
        assertEquals("Real title",NoteTitles.heading("\ufeff---\nname: |\n  # Metadata\n---\n\n```markdown\n# Example\n```\n\n    # Indented example\n\n> # Quoted heading\n\n## Real title\n"));
        assertEquals("",NoteTitles.heading("---\ntitle: Not a document heading\n---\n\n```\n# Example\n```\n\nPlain text\n"));
    }
    @Test void inlineFormattingContributesPlainTextAndHtmlAttributesDoNot(){
        assertEquals("Review bold italic code old link & image",NoteTitles.heading("# Review **bold** *italic* `code` ~~old~~ [link](https://example.test) &amp; ![image](asset.png)\n"));
        assertEquals("Safe text",NoteTitles.heading("# <img src=x onerror=alert(1)>Safe <em>text</em>\n"));
        assertEquals("Multiline heading",NoteTitles.heading("Multiline\nheading\n-------\n"));
    }
    @Test void noHeadingDoesNotTurnBodyTextIntoATitle(){
        assertEquals("",NoteTitles.heading("Ordinary body\n\n- #not-a-heading\n\n\\# Escaped\n"));
        assertEquals("",NoteTitles.heading(""));
    }
    @Test void longTitlesAreBoundedWithoutSplittingUnicodeCharacters(){
        String title="😀".repeat(NoteTitles.MAXIMUM+1),result=NoteTitles.heading("# "+title);
        assertEquals(NoteTitles.MAXIMUM,result.codePointCount(0,result.length()));assertEquals("😀".repeat(NoteTitles.MAXIMUM),result);
    }
}
