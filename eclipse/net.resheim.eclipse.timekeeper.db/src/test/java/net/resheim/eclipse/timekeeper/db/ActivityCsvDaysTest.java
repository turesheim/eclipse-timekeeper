package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.tools.Csv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityCsvDaysTest {
    @TempDir Path source;
    private static final String ID="00000000-0000-4000-8000-000000000001";
    @Test void csvDayRowsPreserveExactTimeFieldsAndRelationsWithoutChangingSource() throws Exception {
        String input="ID,START_TIME,END_TIME,SUMMARY,OWNER_ID,ADJUSTED\n"+ID+
            ",2025-10-25T21:59:59.123456789Z,2025-10-27T22:00:00.987654321Z,\"quoted, summary\",alice,FALSE\n";
        Files.writeString(source.resolve("activity.csv"),input);
        Files.writeString(source.resolve("trackedtask_activity.csv"),"TASK_ID,ACTIVITIES_ID\ntask,"+ID+"\n");
        Files.writeString(source.resolve("project_activity.csv"),"PROJECT_NAME,CHILDREN_ID\nproject,"+ID+"\n");
        Files.writeString(source.resolve("activity_activitylabel.csv"),"ACTIVITY_ID,LABELS_ID\n"+ID+",label\n");
        var now=Instant.parse("2025-10-28T12:00:00Z");var zone=ZoneId.of("Europe/Oslo");
        List<String> identities=new ArrayList<>();Path temporary;
        try(var prepared=ActivityCsvDays.prepare(source,zone,now)) {
            temporary=prepared.folder;Duration total=Duration.ZERO;
            try(var rows=new Csv().read(prepared.folder.resolve("activity.csv").toString(),null,"UTF-8")) {
                while(rows.next()) {
                    identities.add(rows.getString("ID"));
                    total=total.plus(Duration.between(Instant.parse(rows.getString("START_TIME")),Instant.parse(rows.getString("END_TIME"))));
                    assertEquals("quoted, summary",rows.getString("SUMMARY"));assertEquals("alice",rows.getString("OWNER_ID"));assertEquals("FALSE",rows.getString("ADJUSTED"));
                }
            }
            assertEquals(3,identities.size());assertEquals(ID,identities.getLast());
            assertEquals(Duration.between(Instant.parse("2025-10-25T21:59:59.123456789Z"),Instant.parse("2025-10-27T22:00:00.987654321Z")),total);
            for(var name:List.of("trackedtask_activity.csv","project_activity.csv","activity_activitylabel.csv")) {
                int count=0;try(var rows=new Csv().read(prepared.folder.resolve(name).toString(),null,"UTF-8")) {while(rows.next()) count++;}
                assertEquals(3,count,name);
            }
        }
        assertFalse(Files.exists(temporary));assertEquals(input,Files.readString(source.resolve("activity.csv")));
        try(var repeated=ActivityCsvDays.prepare(source,zone,now);var rows=new Csv().read(repeated.folder.resolve("activity.csv").toString(),null,"UTF-8")) {
            var ids=new ArrayList<String>();while(rows.next()) ids.add(rows.getString("ID"));assertEquals(identities,ids);
        }
    }
    @Test void preparationRejectsFutureStartsAndIgnoresUnrelatedFiles() throws Exception {
        Files.writeString(source.resolve("activity.csv"),"ID,START_TIME,END_TIME\n"+ID+",2025-01-02T00:00:00Z,\n");
        Files.writeString(source.resolve("unrelated.txt"),"not part of the export");
        var now=Instant.parse("2025-01-01T00:00:00Z");
        assertThrows(java.io.IOException.class,()->ActivityCsvDays.prepare(source,ZoneOffset.UTC,now));
        try(var prepared=ActivityCsvDays.prepare(source,ZoneOffset.UTC,now.plus(Duration.ofDays(2)))) {
            assertFalse(Files.exists(prepared.folder.resolve("unrelated.txt")));
        }
    }
}
