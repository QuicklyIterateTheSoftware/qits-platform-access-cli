package eu.wohlben.qits.cli.access.database.fixtures.plain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/** Packed into a jar without pom.properties: its origin is the jar's name without the version. */
@Entity
public class Crate extends PanacheEntityBase {

    @Id
    public UUID id;

    @ManyToOne(optional = false)
    public eu.wohlben.qits.cli.access.database.fixtures.library.Shelf shelf;
}
