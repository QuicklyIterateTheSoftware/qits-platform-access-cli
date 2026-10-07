package eu.wohlben.qits.cli.access.database;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One language/ORM. JPA/Hibernate (id "jpa") is the first; another stack is one more
 * implementation, listed in {@link EntityModelSources}.
 */
public interface EntityModelSource {

    String id();

    /** True when this source finds its inputs under root (for jpa: some {@code target/classes} of the reactor). */
    boolean detects(Path root);

    /** Every unit this repository owns, fully mapped. Empty = no entities. */
    List<UnitModel> read(Path root) throws IOException;
}
