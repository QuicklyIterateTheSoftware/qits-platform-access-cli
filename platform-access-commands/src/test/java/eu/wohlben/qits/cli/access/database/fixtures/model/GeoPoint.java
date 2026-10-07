package eu.wohlben.qits.cli.access.database.fixtures.model;

import jakarta.persistence.Embeddable;

/** An embeddable inside an embeddable. */
@Embeddable
public class GeoPoint {

    public double latitude;

    public Double longitude;
}
