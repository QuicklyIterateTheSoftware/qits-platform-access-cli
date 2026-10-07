package eu.wohlben.qits.cli.access.database.fixtures.stray;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** No unit lists this package: it is drawn in a file named after the package. */
@Entity
public class Orphan extends PanacheEntityBase {

    @Id
    public String id;
}
