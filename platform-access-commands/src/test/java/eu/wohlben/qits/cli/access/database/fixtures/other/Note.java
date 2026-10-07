package eu.wohlben.qits.cli.access.database.fixtures.other;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** The default unit's entity, compiled in a nested module. */
@Entity
public class Note extends PanacheEntityBase {

    @Id
    public String id;

    public String body;
}
