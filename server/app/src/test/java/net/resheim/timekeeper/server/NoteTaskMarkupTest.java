package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NoteTaskMarkupTest {
    @Test void realListTasksExcludeFrontmatterEscapesAndCodeExamples(){
        String source="\ufeff---\nexample: '- [ ] Metadata'\n---\n\n```md\n- [ ] Fenced example\n```\n\n    - [ ] Indented example\n\n- \\[ ] Escaped marker\n\n[ ] Not a list\n\n- [ ] Real task\n";
        var tasks=NoteTaskMarkup.items(source);assertEquals(1,tasks.size());assertEquals("Real task",tasks.get(0).summary());assertEquals(source.indexOf("[ ] Real"),tasks.get(0).marker());assertFalse(tasks.get(0).checked());
    }
    @Test void summariesUseVisibleInlineTextAndKeepRepeatedItemsDistinct(){
        String source="- [ ] **Review** [the spec](https://example.invalid) and `command`\n- [X] Repeat\n- [ ] Repeat\n";
        var tasks=NoteTaskMarkup.items(source);assertEquals(List.of("Review the spec and command","Repeat","Repeat"),tasks.stream().map(NoteTaskMarkup.Item::summary).toList());assertTrue(tasks.get(1).checked());assertFalse(tasks.get(2).checked());assertNotEquals(tasks.get(1).marker(),tasks.get(2).marker());
    }
    @Test void checkedReferenceLabelsStillActAsRawCheckboxMarkers(){
        String source="[x]: https://example.invalid\n\n- [x] Read [the reference][x]\n";var tasks=NoteTaskMarkup.items(source);assertEquals(1,tasks.size());assertEquals("Read the reference",tasks.get(0).summary());assertTrue(tasks.get(0).checked());
    }
    @Test void nestedQuotedAndOrderedListsRetainTheirOwnLocations(){
        String source="1. [ ] Parent\n   - [x] Child\n\n> - [ ] Quoted task\n";var tasks=NoteTaskMarkup.items(source);assertEquals(List.of("Parent","Child","Quoted task"),tasks.stream().map(NoteTaskMarkup.Item::summary).toList());assertEquals(List.of(0,1,0),tasks.stream().map(NoteTaskMarkup.Item::depth).toList());for(var task:tasks)assertEquals('[',source.charAt(task.marker()));
    }
    @Test void checkboxEditsPreserveBomUnicodeCrlfAndAllOtherBytes(){
        String source="\ufeff---\r\nprivate: retained\r\n---\r\n\r\nÆ 😀\r\n\r\n- [ ] First  \r\n- [X] Second\r\n";var tasks=NoteTaskMarkup.items(source);assertEquals(2,tasks.size());
        assertEquals(source.replace("[ ] First","[x] First"),NoteTaskMarkup.checked(source,tasks.get(0),true));assertEquals(source,NoteTaskMarkup.checked(source,tasks.get(1),true));assertEquals(source.replace("[X] Second","[ ] Second"),NoteTaskMarkup.checked(source,tasks.get(1),false));
        assertThrows(IllegalArgumentException.class,()->NoteTaskMarkup.checked(source.replace("[ ] First","[x] First"),tasks.get(0),true));
    }
    @Test void blankMarkersWaitForATitleAndSoftWrappingDoesNotSplitATask(){
        var tasks=NoteTaskMarkup.items("- [ ]\n- [x]   \n- [ ] Wrapped\n  description\n");assertEquals(1,tasks.size());assertEquals("Wrapped description",tasks.get(0).summary());
    }
}
