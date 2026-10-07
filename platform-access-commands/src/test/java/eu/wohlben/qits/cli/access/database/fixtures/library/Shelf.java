package eu.wohlben.qits.cli.access.database.fixtures.library;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Packed into a library jar with its pom.properties: its origin is that artifactId. */
@Entity
public class Shelf extends PanacheEntityBase {

    @Id
    public String code;

    public int capacity;
}
