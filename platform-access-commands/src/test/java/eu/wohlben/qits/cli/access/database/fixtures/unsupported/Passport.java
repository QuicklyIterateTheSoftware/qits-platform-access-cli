package eu.wohlben.qits.cli.access.database.fixtures.unsupported;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import org.hibernate.annotations.Formula;

import java.util.Set;

/** Constructs the diagram lists rather than draws. */
@Entity
public class Passport extends PanacheEntityBase {

    @Id
    public String number;

    @OneToOne
    public Holder holder;

    @ManyToMany
    public Set<Holder> previousHolders;

    @Formula("upper(number)")
    public String shouted;
}
