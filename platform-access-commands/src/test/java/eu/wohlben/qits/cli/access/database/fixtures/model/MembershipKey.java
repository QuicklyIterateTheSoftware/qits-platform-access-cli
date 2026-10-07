package eu.wohlben.qits.cli.access.database.fixtures.model;

import java.io.Serializable;
import java.util.Objects;

/** Membership's @IdClass. */
public class MembershipKey implements Serializable {

    public Long accountId;

    public String role;

    public MembershipKey() {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MembershipKey key && Objects.equals(accountId, key.accountId)
                && Objects.equals(role, key.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, role);
    }
}
