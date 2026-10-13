package net.resheim.eclipse.timekeeper.db.model;

import java.time.Instant;
import javax.persistence.*;
import net.resheim.eclipse.timekeeper.db.converters.InstantAttributeConverter;

/** Persistent work period independent of tasks and projects. */
@Entity @Table(name="WORK_SESSION") @Cacheable(false)
public class WorkSession {
    @Id @Column(name="ID",length=36,nullable=false,updatable=false) private String id;
    @Version @Column(name="VERSION",nullable=false) private long version;
    @Column(name="OWNER_ID",length=400,nullable=false,updatable=false) private String ownerId;
    @Column(name="START_TIME",columnDefinition="VARCHAR(30)",nullable=false) @Convert(converter=InstantAttributeConverter.class) private Instant start;
    @Column(name="END_TIME",columnDefinition="VARCHAR(30)") @Convert(converter=InstantAttributeConverter.class) private Instant end;
    @Column(name="SUMMARY",length=2000) private String summary;
    @Column(name="STATE",length=16,nullable=false) private String state;
    protected WorkSession() { }
    public WorkSession(String id,String owner,Instant start,Instant end,String summary) {
        this.id=id;this.ownerId=owner;this.start=start;this.end=end;this.summary=summary;state=end==null ? "RUNNING" : "COMPLETED";
    }
    public String getId() {return id;}
    public long getVersion() {return version;}
    public String getOwnerId() {return ownerId;}
    public Instant getStart() {return start;}
    public Instant getEnd() {return end;}
    public String getSummary() {return summary;}
    public String getState() {return state;}
}
