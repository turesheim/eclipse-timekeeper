package net.resheim.eclipse.timekeeper.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import net.resheim.eclipse.timekeeper.db.model.Activity;

/** Exercise production CSV export/import, not only prepared CSV strings. */
class NativeDailyImportTest {
    @TempDir Path directory;
    private EntityManager manager, previous;
    @BeforeEach void open() throws Exception {
        previous=TimekeeperPlugin.getDefault().getEntityManager();
        manager=DatabaseStartup.open("jdbc:h2:mem:native_daily_"+UUID.randomUUID());
        TimekeeperPlugin.setEntityManager(manager);
        manager.getTransaction().begin();
        var type=new net.resheim.eclipse.timekeeper.db.model.ProjectType("daily import");manager.persist(type);
        var project=new net.resheim.eclipse.timekeeper.db.model.Project(type,"Daily import project");
        var task=new net.resheim.eclipse.timekeeper.db.model.Task("Daily import task");task.setProject(project);
        var label=new net.resheim.eclipse.timekeeper.db.model.ActivityLabel("Daily import label","12,34,56");manager.persist(label);
        var activity=new Activity(task,Instant.parse("2025-10-25T12:00:00Z"));activity.setSummary("Investigated the build");
        activity.setEnd(activity.getStart().plusSeconds(3600));activity.toggleLabel(label);task.addActivity(activity);
        manager.persist(project);manager.getTransaction().commit();
    }
    @AfterEach void close() {
        TimekeeperPlugin.setEntityManager(previous);DatabaseStartup.close(manager);
    }
    private Map<String,List<String>> rows(String table) {
        var result=new TreeMap<String,List<String>>();
        for(var value:manager.createNativeQuery("SELECT * FROM "+table).getResultList()) {
            var row=Arrays.stream((Object[])value).map(v->Objects.toString(v,null)).toList();
            result.put(row.toString(),row);
        }
        return result;
    }
    @Test void oldMultiDayExportMergesStableDailyIdsWithAllLabelsAndRelations() throws Exception {
        var original=manager.createQuery("SELECT a FROM Activity a WHERE a.summary='Investigated the build'",Activity.class).getSingleResult();
        String identity=original.getId(), label=original.getLabels().getFirst().getId();
        var zone=ZoneId.systemDefault();
        var from=LocalDate.of(2025,10,25).atTime(23,59,59).atZone(zone).toInstant().plusNanos(123456789);
        var until=LocalDate.of(2025,10,27).atTime(23,0).atZone(zone).toInstant().plusNanos(987654321);
        manager.getTransaction().begin();original.setStart(from);original.setEnd(until);manager.getTransaction().commit();
        Path export=directory.resolve("export");TimekeeperPlugin.getDefault().exportTo(export);
        assertTrue(Files.isRegularFile(export.resolve("activitylabel.csv")));
        assertTrue(Files.readString(export.resolve("activity_activitylabel.csv")).contains(label));
        String source=Files.readString(export.resolve("activity.csv"));
        TimekeeperPlugin.getDefault().importFrom(export);manager.clear();
        var parts=manager.createQuery("SELECT a FROM Activity a WHERE a.summary='Investigated the build' ORDER BY a.start",Activity.class).getResultList();
        assertEquals(3,parts.size());assertEquals(identity,parts.getLast().getId());
        Duration total=Duration.ZERO;
        for(var part:parts) {
            total=total.plus(Duration.between(part.getStart(),part.getEnd()));
            assertFalse(part.isEdited());assertEquals(label,part.getLabels().getFirst().getId());
            assertEquals(parts.getFirst().getTrackedTask().getId(),part.getTrackedTask().getId());
            assertEquals(1,((Number)manager.createNativeQuery("SELECT COUNT(*) FROM TASK_ACTIVITY WHERE ACTIVITIES_ID=?1").setParameter(1,part.getId()).getSingleResult()).intValue());
        }
        assertEquals(Duration.between(from,until),total);assertEquals(source,Files.readString(export.resolve("activity.csv")));
        var activityRows=rows("ACTIVITY");var taskRows=rows("TASK_ACTIVITY");var labelRows=rows("ACTIVITY_ACTIVITYLABEL");
        TimekeeperPlugin.getDefault().importFrom(export);manager.clear();
        assertEquals(activityRows,rows("ACTIVITY"));assertEquals(taskRows,rows("TASK_ACTIVITY"));assertEquals(labelRows,rows("ACTIVITY_ACTIVITYLABEL"));
        Path daily=directory.resolve("daily");TimekeeperPlugin.getDefault().exportTo(daily);
        TimekeeperPlugin.getDefault().importFrom(daily);manager.clear();
        assertEquals(activityRows,rows("ACTIVITY"));assertEquals(taskRows,rows("TASK_ACTIVITY"));assertEquals(labelRows,rows("ACTIVITY_ACTIVITYLABEL"));
    }
    @Test void aLateBrokenRelationRollsBackEarlierImportedDailyRows() throws Exception {
        Path export=directory.resolve("invalid");TimekeeperPlugin.getDefault().exportTo(export);
        var before=rows("ACTIVITY");var projects=rows("PROJECT");
        String csv=Files.readString(export.resolve("activity.csv")).replace("Investigated the build","Must roll back");
        Files.writeString(export.resolve("activity.csv"),csv);
        Files.writeString(export.resolve("activity_activitylabel.csv"),"ACTIVITY_ID,LABELS_ID\nmissing-activity,missing-label\n");
        assertThrows(java.io.IOException.class,()->TimekeeperPlugin.getDefault().importFrom(export));manager.clear();
        assertEquals(before,rows("ACTIVITY"));assertEquals(projects,rows("PROJECT"));
        assertTrue(manager.createNativeQuery("SELECT * FROM ACTIVITY WHERE SUMMARY='Must roll back'").getResultList().isEmpty());
    }
}
