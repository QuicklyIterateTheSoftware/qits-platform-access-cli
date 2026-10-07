package eu.wohlben.qits.cli.access.database.fixtures.other.sub;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** In a sub-package of the default unit's package: the unit claims it, as Quarkus does. */
@Entity
public class Remark extends PanacheEntityBase {

    @Id
    public long id;
}
