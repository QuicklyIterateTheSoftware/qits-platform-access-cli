package eu.wohlben.qits.cli.access.database.fixtures.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/** A composite key, declared out of name order, and three relations. */
@Entity
@IdClass(MembershipKey.class)
public class Membership extends PanacheEntityBase {

    @Id
    public String role;

    @Id
    @Column(name = "account_id")
    public Long accountId;

    /** The same column as accountId: one column, a key and a foreign key. */
    @ManyToOne
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    public Account account;

    @ManyToOne(optional = false)
    @JoinColumn(name = "team_id")
    public Team team;

    /** No @JoinColumn: the implicit former_team_id. */
    @ManyToOne
    public Team formerTeam;
}
