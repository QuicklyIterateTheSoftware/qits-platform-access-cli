package eu.wohlben.qits.cli.access.database.fixtures.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/** Flattened into account, each column named by its own attribute. */
@Embeddable
public class Address {

    @Column(nullable = false)
    public String street;

    public String zipCode;

    public int floor;

    @Embedded
    public GeoPoint point;
}
