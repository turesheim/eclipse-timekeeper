package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NoteTaskMatchesTest {
    private int[] match(String before,String after){return NoteTaskMatches.previous(NoteTaskMarkup.items(before),NoteTaskMarkup.items(after));}
    @Test void checkingAndReorderingKeepTheExistingTaskIdentities(){
        assertArrayEquals(new int[]{1,0},match("- [ ] First\n- [x] Second\n","- [ ] Second\n- [x] First\n"));
    }
    @Test void aNewTaskDoesNotTakeTheIdentityOfAnUnchangedNeighbour(){
        assertArrayEquals(new int[]{-1,0,1},match("- [ ] First\n- [ ] Second\n","- [ ] Added\n- [ ] First\n- [ ] Second\n"));
    }
    @Test void repeatedTitlesStaySeparateWhenTheirStatesChange(){
        assertArrayEquals(new int[]{0,1},match("- [ ] Repeat\n- [x] Repeat\n","- [x] Repeat\n- [ ] Repeat\n"));
    }
    @Test void anUnambiguousTitleEditRetainsItsTask(){
        assertArrayEquals(new int[]{0,1},match("- [ ] Old title\n- [ ] Second\n","- [ ] Replacement title\n- [ ] Second\n"));
    }
    @Test void editingATitleWhileAddingAnotherTaskRetainsTheRecognizableItem(){
        assertArrayEquals(new int[]{0,-1,1},match("- [ ] Draft report\n- [ ] Second\n","- [ ] Draft final report\n- [ ] Added task\n- [ ] Second\n"));
    }
    @Test void ambiguousWholesaleReplacementsDoNotReuseUnrelatedTasks(){
        assertArrayEquals(new int[]{-1,-1,-1},match("- [ ] AAA\n- [ ] BBB\n","- [ ] CCC\n- [ ] DDD\n- [ ] EEE\n"));
    }
}
