package net.resheim.eclipse.timekeeper.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.h2.tools.Csv;
import org.h2.tools.SimpleResultSet;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.domain.ActivityDailySegments;

/** Prepare daily rows and their relations without modifying the source export. */
final class ActivityCsvDays implements AutoCloseable {
    final Path folder;
    final Map<String,String> originals=new HashMap<>();
    final Map<String,String> currentActivities=new HashMap<>();
    private record Table(List<String> columns,List<String[]> rows) { }
    private ActivityCsvDays(Path folder) {this.folder=folder;}
    static ActivityCsvDays prepare(Path source,ZoneId zone,Instant now) throws IOException {
        var result=new ActivityCsvDays(Files.createTempDirectory("timekeeper-day-import-"));
        try {
            try(var files=Files.list(source)) {
                var names=Set.of("project_type.csv","project.csv","trackedtask.csv","activity.csv","trackedtask_activity.csv",
                        "project_task.csv","project_activity.csv","external_task_reference.csv","activitylabel.csv","activity_activitylabel.csv","work_session.csv");
                for(var file:files.filter(Files::isRegularFile).filter(file->names.contains(file.getFileName().toString())).toList())
                    Files.copy(file,result.folder.resolve(file.getFileName()));
            }
            var taskFile=source.resolve("trackedtask.csv");
            if(Files.isRegularFile(taskFile)) {
                var tasks=read(taskFile);int taskId=column(tasks,"ID"),parent=column(tasks,"PARENT_TASK"),active=column(tasks,"CURRENTACTIVITY_ID");
                var pending=new LinkedHashMap<String,String[]>();var ordered=new ArrayList<String[]>();var placed=new HashSet<String>();
                for(var row:tasks.rows()) {
                    var copy=row.clone();if(copy[active]!=null && !copy[active].isBlank()) {result.currentActivities.put(copy[taskId],copy[active]);copy[active]=null;}
                    if(pending.put(copy[taskId],copy)!=null) throw new IOException("Duplicate task in export");
                }
                while(!pending.isEmpty()) {
                    int before=pending.size();var iterator=pending.entrySet().iterator();
                    while(iterator.hasNext()) {var entry=iterator.next();String owner=entry.getValue()[parent];
                        if(owner==null || owner.isBlank() || placed.contains(owner)) {ordered.add(entry.getValue());placed.add(entry.getKey());iterator.remove();}}
                    if(pending.size()==before) throw new IOException("Task parents are missing or cyclic");
                }
                write(result.folder.resolve("trackedtask.csv"),new Table(tasks.columns(),ordered));
            }
            var sessionFile=source.resolve("work_session.csv");
            if(Files.isRegularFile(sessionFile)) {
                var sessions=read(sessionFile);int from=column(sessions,"START_TIME"),until=column(sessions,"END_TIME"),state=column(sessions,"STATE");
                for(var row:sessions.rows()) {
                    var first=Instant.parse(row[from]);Activity.validateStart(first,now);
                    var last=Optional.ofNullable(row[until]).filter(value->!value.isBlank()).map(Instant::parse);
                    if(last.filter(value->value.isBefore(first)).isPresent() || !(last.isEmpty() ? "RUNNING" : "COMPLETED").equals(row[state]))
                        throw new IOException("Invalid work session interval or state");
                }
            }
            var table=read(source.resolve("activity.csv"));
            if(!Files.isRegularFile(sessionFile)) {
                for(int index=0;index<table.columns().size();index++) if(table.columns().get(index).equalsIgnoreCase("WORK_SESSION_ID")) {
                    final int sessionColumn=index;
                    if(table.rows().stream().anyMatch(row -> row[sessionColumn]!=null && !row[sessionColumn].isBlank()))
                        throw new IOException("work_session.csv is required for exported session associations");
                }
            }
            int id=column(table,"ID"),start=column(table,"START_TIME"),end=column(table,"END_TIME");
            var rows=new ArrayList<String[]>();var archives=new HashMap<String,List<String>>();
            for(var row:table.rows()) {
                var from=Instant.parse(row[start]);Activity.validateStart(from,now);
                var until=Optional.ofNullable(row[end]).filter(value->!value.isBlank()).map(Instant::parse);
                if(until.filter(value->value.isBefore(from)).isPresent()) throw new IOException("Invalid activity end in import");
                var intervals=ActivityDailySegments.split(from,until,zone,now);var retained=new ActivityId(UUID.fromString(row[id]));
                for(int index=0;index<intervals.size();index++) {
                    var interval=intervals.get(index);var part=row.clone();
                    if(index<intervals.size()-1) {
                        part[id]=ActivityDailySegments.identifier(retained,interval.start()).value().toString();
                        result.originals.put(part[id],row[id]);
                        archives.computeIfAbsent(row[id],ignored->new ArrayList<>()).add(part[id]);
                    }
                    part[start]=interval.start().toString();part[end]=interval.end().map(Instant::toString).orElse(null);rows.add(part);
                }
            }
            write(result.folder.resolve("activity.csv"),new Table(table.columns(),rows));
            for(var link:Map.of("trackedtask_activity.csv","ACTIVITIES_ID","project_activity.csv","CHILDREN_ID",
                    "activity_activitylabel.csv","ACTIVITY_ID").entrySet()) {
                var path=source.resolve(link.getKey());if(!Files.isRegularFile(path)) continue;
                var relations=read(path);int activity=column(relations,link.getValue());var joined=new ArrayList<>(relations.rows());
                for(var row:relations.rows()) for(var archive:archives.getOrDefault(row[activity],List.of())) {
                    var copy=row.clone();copy[activity]=archive;joined.add(copy);
                }
                write(result.folder.resolve(link.getKey()),new Table(relations.columns(),joined));
            }
            return result;
        } catch(SQLException | RuntimeException | IOException failure) {
            result.close();throw new IOException("Could not prepare daily activities for import",failure);
        }
    }
    private static int column(Table table,String name) throws IOException {
        for(int index=0;index<table.columns().size();index++) if(table.columns().get(index).equalsIgnoreCase(name)) return index;
        throw new IOException("Missing CSV column "+name);
    }
    private static Table read(Path path) throws SQLException {
        try(var data=new Csv().read(path.toString(),null,"UTF-8")) {
            var metadata=data.getMetaData();var columns=new ArrayList<String>();var rows=new ArrayList<String[]>();
            for(int index=1;index<=metadata.getColumnCount();index++) columns.add(metadata.getColumnName(index));
            while(data.next()) {var row=new String[columns.size()];for(int index=0;index<row.length;index++) row[index]=data.getString(index+1);rows.add(row);}
            return new Table(columns,rows);
        }
    }
    private static void write(Path path,Table table) throws SQLException {
        try(var rows=new SimpleResultSet()) {
            for(var name:table.columns()) rows.addColumn(name,Types.VARCHAR,0,0);
            for(var values:table.rows()) rows.addRow((Object[])values);
            new Csv().write(path.toString(),rows,"UTF-8");
        }
    }
    @Override public void close() throws IOException {
        try(var files=Files.walk(folder)) {for(var path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
    }
}
