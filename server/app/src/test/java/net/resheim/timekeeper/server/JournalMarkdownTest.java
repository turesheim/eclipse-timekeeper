package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JournalMarkdownTest {
    private JournalMarkdown.Note parse(String text){return JournalMarkdown.parse("2026-10-25.md",text.getBytes(StandardCharsets.UTF_8));}
    private void rejects(String code,String text){
        var failure=assertThrows(JournalMarkdown.Invalid.class,()->parse(text));
        assertEquals(code,failure.code);assertNull(failure.getCause());assertEquals(code,failure.getMessage());
    }
    @Test void emptyBodyOnlyAndLabelOnlyNotesPreserveExactBytes(){
        assertEquals("",parse("").body());assertEquals("",parse("").label());
        String body="\ufeff# Søndag 😀\r\n\r\n  - [ ] Task  \r\n\t\n";
        var parsed=parse(body);assertEquals(body,parsed.body());assertEquals("",parsed.label());
        assertEquals(JournalMarkdown.hash(body.getBytes(StandardCharsets.UTF_8)),parsed.hash());
        assertFalse(parsed.toString().contains("Task"));
        var label=parse("---\ntimekeeper-label: Planning\n---\n");assertEquals("Planning",label.label());assertEquals("",label.body());
        assertEquals("---",parse("---").body());
    }
    @Test void realYamlSupportsQuotesUnknownPropertiesAndFilenameWins(){
        var note=parse("\ufeff---\r\ntimekeeper-label: ' Plan: søndag #1 '\r\ndate: 2000-01-01\r\nother:\r\n  values: [1, true, null]\r\n  text: |\r\n    Untouched external metadata\r\n...\r\n\r\n# Exact  \r\n\t\r\n");
        assertEquals("Plan: søndag #1",note.label());assertEquals("2026-10-25",note.date().toString());
        assertEquals("\r\n# Exact  \r\n\t\r\n",note.body());
        assertEquals("",parse("---\nother: yes\n---\nBody").label());
        assertEquals("",parse("---\ntimekeeper-label: null\n---\n").label());
        assertEquals("",parse("---\ntimekeeper-label: ''\n---\n").label());
        assertEquals("Planning",parse("---\ntimekeeper-label: >-\n  Planning\n---").label());
    }
    @Test void unsafeOrMalformedYamlNeverLeaksPayloadInFailures(){
        rejects("INVALID_YAML","---\ntimekeeper-label: secret-draft\ntimekeeper-label: duplicate\n---\n");
        rejects("INVALID_YAML","---\nsecret: !!java/object:java.lang.ProcessBuilder {}\n---\n");
        rejects("INVALID_YAML","---\nsecret: [unterminated-private-content\n---\n");
        rejects("INVALID_YAML","---\na: &private [one, two]\nb: *private\n---\n");
        rejects("INVALID_YAML","---\na: "+"[".repeat(20)+"secret"+"]".repeat(20)+"\n---\n");
        rejects("INVALID_FRONTMATTER","---\nprivate: unterminated\n");
        rejects("INVALID_FRONTMATTER","---\n- private-list\n---\n");
        rejects("INVALID_FRONTMATTER","---\n? [private, key]\n: value\n---\n");
    }
    @Test void labelsMustBeStringsAndMeetTheCalendarLimit(){
        rejects("INVALID_LABEL","---\ntimekeeper-label: true\n---\n");
        rejects("INVALID_LABEL","---\ntimekeeper-label: [private]\n---\n");
        rejects("INVALID_LABEL","---\ntimekeeper-label: \"private\\nlabel\"\n---\n");
        rejects("INVALID_LABEL","---\ntimekeeper-label: '"+"😀".repeat(81)+"'\n---\n");
        assertEquals("😀".repeat(80),parse("---\ntimekeeper-label: '"+"😀".repeat(80)+"'\n---\n").label());
    }
    @Test void exactFilenamesCivilDatesUtf8AndIndependentLimitsAreEnforced(){
        for(String filename:new String[]{"../2026-10-25.md","/2026-10-25.md","2026-10-25.MD","2026-1-25.md","2026-10-25.md/other","2026-10-25.md\n"})
            assertEquals("INVALID_FILENAME",assertThrows(JournalMarkdown.Invalid.class,()->JournalMarkdown.parse(filename,new byte[0])).code);
        for(String filename:new String[]{"0000-01-01.md","2025-02-29.md","2026-13-01.md"})
            assertEquals("INVALID_DATE",assertThrows(JournalMarkdown.Invalid.class,()->JournalMarkdown.parse(filename,new byte[0])).code);
        assertEquals("2024-02-29",JournalMarkdown.parse("2024-02-29.md",new byte[0]).date().toString());
        assertEquals("INVALID_UTF8",assertThrows(JournalMarkdown.Invalid.class,()->JournalMarkdown.parse("2026-10-25.md",new byte[]{(byte)0xc3,0x28})).code);
        assertEquals(200000,parse("ø".repeat(100000)).body().getBytes(StandardCharsets.UTF_8).length);
        rejects("BODY_TOO_LARGE","ø".repeat(100001));rejects("FILE_TOO_LARGE","x".repeat(220001));
        rejects("FRONTMATTER_TOO_LARGE","---\nunknown: '"+"x".repeat(20000)+"'\n---\n");
    }
    @Test void exportingCalendarChangesPreservesUnknownYamlAndExactUnsupportedMarkdown(){
        String original="\ufeff---\r\ntimekeeper-label: Original\r\nother: [1, true, null]\r\nwhen: 2000-01-01\r\nbinary: !!binary SGVsbG8=\r\ncustom:\r\n  message: |\r\n    Preserve this property\r\n---\r\nOld body";
        byte[] bytes=original.getBytes(StandardCharsets.UTF_8);String body="\r\n<div data-private=\"unmodified\">\r\n  <!-- unsupported HTML -->\r\n</div>\r\n\r\n[link][custom]\r\n\t  ";
        byte[] rendered=JournalMarkdown.render("2026-10-25.md",bytes,"true: # Søndag",body);
        var before=JournalMarkdown.document("2026-10-25.md",bytes);var after=JournalMarkdown.document("2026-10-25.md",rendered);
        for(String key:new String[]{"other","when","custom"})assertEquals(before.metadata().get(key),after.metadata().get(key));
        assertArrayEquals((byte[])before.metadata().get("binary"),(byte[])after.metadata().get("binary"));
        assertEquals("true: # Søndag",after.note().label());assertEquals(body,after.note().body());assertFalse(after.toString().contains("private"));
        assertArrayEquals(bytes,JournalMarkdown.render("2026-10-25.md",bytes,before.note().label(),before.note().body()));
    }
    @Test void exportDoesNotMistakeArbitraryMarkdownForFrontmatterAndKeepsEmptyNotesPresent(){
        String body="---\nprivate: !!java/object:java.lang.ProcessBuilder {}\n---\nThis is literal Markdown, not metadata.  \r\n";
        byte[] rendered=JournalMarkdown.render("2026-10-25.md",null,"",body);assertEquals(body,JournalMarkdown.parse("2026-10-25.md",rendered).body());
        assertArrayEquals(new byte[0],JournalMarkdown.render("2026-10-25.md",null,"",""));
        assertEquals("Planning",JournalMarkdown.parse("2026-10-25.md",JournalMarkdown.render("2026-10-25.md",null,"Planning","")).label());
        assertEquals("BODY_TOO_LARGE",assertThrows(JournalMarkdown.Invalid.class,()->JournalMarkdown.render("2026-10-25.md",null,"","ø".repeat(100001))).code);
    }
}
