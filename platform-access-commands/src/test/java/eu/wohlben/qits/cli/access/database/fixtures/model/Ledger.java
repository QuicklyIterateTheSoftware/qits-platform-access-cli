package eu.wohlben.qits.cli.access.database.fixtures.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

import java.math.BigDecimal;

/** Property access (@Id on the getter) and an entity name that names the table. */
@Entity(name = "LedgerBook")
public class Ledger extends PanacheEntityBase {

    private String code;
    private BigDecimal total;
    private boolean closed;
    private String owner;

    @Id
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    @Column(name = "ownerName", length = 40)
    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    @Transient
    public String getComputed() {
        return code + owner;
    }
}
