package eu.wohlben.qits.cli.access.database.fixtures.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;

/** The middle of a @MappedSuperclass chain. */
@MappedSuperclass
public abstract class AuditedEntity extends BaseEntity {

    @Column(nullable = false)
    public Instant createdAt;

    public Instant updatedAt;

    @Version
    public Integer revision;
}
