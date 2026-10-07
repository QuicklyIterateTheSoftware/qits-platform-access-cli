package eu.wohlben.qits.cli.access.database.fixtures.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Basic;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A PanacheEntity: its id comes from the Panache jar's @MappedSuperclass. */
@Entity
@Table(name = "account", uniqueConstraints = @UniqueConstraint(columnNames = {"displayName", "tenant"}))
public class Account extends PanacheEntity {

    public static int created;

    @Column(name = "displayName", nullable = false, length = 80)
    public String displayName;

    public String tenant;

    @Column(unique = true, nullable = false, length = 320)
    public String email;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    public Status status;

    public Level level;

    public boolean active;

    @NotNull
    public Instant createdAt;

    @Lob
    public String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    public String settings;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    public String document;

    @Column(columnDefinition = "text")
    public String biography;

    public byte[] avatar;

    public BigDecimal balance;

    public LocalDate birthday;

    @Version
    public long revision;

    @Convert(converter = ColorConverter.class)
    public Color favouriteColor;

    @Basic(optional = false)
    public Integer loginCount;

    public transient String scratch;

    @Transient
    public String computed;

    @Embedded
    @AttributeOverride(name = "zipCode", column = @Column(name = "postal_code", length = 12))
    public Address address;

    @ElementCollection
    @CollectionTable(name = "account_tag", joinColumns = @JoinColumn(name = "account_id"))
    @Column(name = "tag", nullable = false)
    public Set<String> tags;

    @ElementCollection
    @OrderColumn(name = "position")
    public List<String> aliases;

    @ElementCollection
    @MapKeyColumn(name = "label_key")
    @Column(name = "label_value", length = 400)
    public Map<String, String> labels;

    @OneToMany(mappedBy = "account")
    public List<Membership> memberships;

    public enum Status { ACTIVE, CLOSED }

    public enum Level { LOW, HIGH }
}
