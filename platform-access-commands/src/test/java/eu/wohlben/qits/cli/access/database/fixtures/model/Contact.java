package eu.wohlben.qits.cli.access.database.fixtures.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class Contact {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Kind kind;

    @Column(name = "contact_value", nullable = false)
    public String value;

    public enum Kind { MAIL, PHONE }
}
