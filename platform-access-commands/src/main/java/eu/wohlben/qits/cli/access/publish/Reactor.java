package eu.wohlben.qits.cli.access.publish;

import java.util.Map;
import java.util.Set;

/**
 * What the SBOM half of a maven hash needs to know about the module's reactor siblings.
 *
 * @param bundled every sibling ({@code g:a}) whose content went into the uploaded jar; it is folded
 *                into the SBOM's root, because its bytes are already in the content lines
 * @param linked  every sibling ({@code g:a}) that stays a pom dependency, with the version decided
 *                for it in this release: V when it was published now, U when it is unchanged since U
 */
record Reactor(Set<String> bundled, Map<String, String> linked) {

    static final Reactor NONE = new Reactor(Set.of(), Map.of());

    Reactor {
        bundled = Set.copyOf(bundled);
        linked = Map.copyOf(linked);
    }
}
