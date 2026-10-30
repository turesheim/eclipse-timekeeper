package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VaultPathTest {
    @Test void portableNamesHaveExplicitCaseAndUnicodeCollisionKeys(){
        assertEquals("Prosjekter/Blåbær.md",new VaultPath("Prosjekter/Bla\u030abær.md").value());
        assertEquals(new VaultPath("Projects/Straße.md").key(),new VaultPath("projects/STRASSE.MD").key());
        assertEquals(new VaultPath("Bla\u030abær.md").key(),new VaultPath("Blåbær.md").key());
        assertEquals("Archive/notes.md",new VaultPath("Archive/notes.md").value());
        assertEquals(".obsidian",new VaultPath(".obsidian").name());
    }
    @Test void traversalAbsolutePathsAndNonportableNamesAreRejected(){
        for(String path:new String[]{"/notes.md","../notes.md","a/../b","a/./b","a//b","a/","a\\b","C:/notes.md","\\\\server\\share",
                "a/CON.md","CON .md","CONIN$","conout$.txt","nul","com1.txt","LPT²","file.","file ","a\u0000b","a\nb","a\u007fb","a?b.md","a:b","a|b","a*b","<a>","\ud800"})
            assertThrows(ApiFailure.class,()->new VaultPath(path),path);
        assertThrows(ApiFailure.class,()->new VaultPath(null));
    }
    @Test void lengthsAreMeasuredInUtf8AndDepthIsBounded(){
        assertEquals(255,new VaultPath("ø".repeat(127)+"a").value().getBytes(StandardCharsets.UTF_8).length);
        assertThrows(ApiFailure.class,()->new VaultPath("ø".repeat(128)));
        assertThrows(ApiFailure.class,()->new VaultPath("x/".repeat(VaultPath.MAX_DEPTH)+"x"));
        assertThrows(ApiFailure.class,()->new VaultPath(String.join("/",java.util.Collections.nCopies(5,"x".repeat(250)))));
    }
    @Test void uriRoundTripsPreservePlusPercentHashAndUnicodeWithoutDoubleDecoding(){
        var path=new VaultPath("Møter/100% + #meeting.md");
        assertEquals("M%C3%B8ter/100%25%20%2B%20%23meeting.md",path.encoded());
        assertEquals(path,VaultPath.encoded(path.encoded()));
        assertEquals("a+b.md",VaultPath.encoded("a+b.md").value());
        assertEquals("%2e%2e.md",VaultPath.encoded("%252e%252e.md").value());
        assertEquals("%252e%252e.md",VaultPath.encoded("%252e%252e.md").encoded());
        for(String pathText:new String[]{"a%2fb","a%5cb","%2e%2e/a","%00","%c3%28","%ed%a0%80","%ff","%","%zz"})
            assertThrows(ApiFailure.class,()->VaultPath.encoded(pathText),pathText);
    }
    @Test void folderMovesUseSegmentsAndPreserveDescendantsWithoutCycles(){
        var path=new VaultPath("Straße/Plans/This week.md");
        assertTrue(path.beneath(new VaultPath("STRASSE")));
        assertFalse(new VaultPath("Projects-old/a.md").beneath(new VaultPath("Projects")));
        assertEquals("Plans/This week.md",path.relativeTo(new VaultPath("STRASSE")).value());
        assertEquals("Archive/Plans/This week.md",path.rebase(new VaultPath("STRASSE"),new VaultPath("Archive")).value());
        assertEquals("STRASSE/Plans/This week.md",path.rebase(new VaultPath("Straße"),new VaultPath("STRASSE")).value());
        assertThrows(ApiFailure.class,()->path.rebase(new VaultPath("Straße"),new VaultPath("Straße/Plans/New")));
        assertThrows(ApiFailure.class,()->path.rebase(VaultPath.root(),new VaultPath("Archive")));
        assertThrows(ApiFailure.class,()->path.relativeTo(new VaultPath("Other")));
        assertThrows(ApiFailure.class,()->new VaultPath("Plans").resolve("../Other"));
        assertTrue(path.beneath(VaultPath.root()));assertTrue(VaultPath.root().parent().isRoot());
    }
}
