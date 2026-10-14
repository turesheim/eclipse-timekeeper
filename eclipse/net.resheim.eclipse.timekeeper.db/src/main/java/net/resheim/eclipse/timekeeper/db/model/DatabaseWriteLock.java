package net.resheim.eclipse.timekeeper.db.model;

import javax.persistence.*;

/** One database row serializes service transactions across shared H2 clients. */
@Entity @Table(name="TIMEKEEPER_WRITE_LOCK") @Cacheable(false)
public class DatabaseWriteLock {
    @Id @Column(name="ID") private int id;
}
