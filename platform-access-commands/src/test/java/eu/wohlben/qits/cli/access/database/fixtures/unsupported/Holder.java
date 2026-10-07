package eu.wohlben.qits.cli.access.database.fixtures.unsupported;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Holder extends PanacheEntityBase {

    @Id
    public long id;
}
