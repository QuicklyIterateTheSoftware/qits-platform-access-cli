package eu.wohlben.qits.cli.access.database.fixtures.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;

import java.util.List;

/** Inherits its key and audit columns through two @MappedSuperclass levels. */
@Entity
public class Team extends AuditedEntity {

    public String name;

    /** Unidirectional: the column lands on badge. */
    @OneToMany
    @JoinColumn(name = "team_ref")
    public List<Badge> badges;

    /** A bag of embeddables: no key. */
    @ElementCollection
    @CollectionTable(name = "team_contact")
    public List<Contact> contacts;
}
