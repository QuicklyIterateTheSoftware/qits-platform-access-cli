package eu.wohlben.qits.cli.access.database;

import java.util.List;
import java.util.function.Consumer;

/** Every language/ORM {@code qits database diagram} knows. Another stack is one more entry here. */
public final class EntityModelSources {

    private EntityModelSources() {
    }

    /** The sources, each sending its warnings to {@code warnings}. */
    public static List<EntityModelSource> all(Consumer<String> warnings) {
        return List.of(new JpaEntityModelSource(warnings));
    }

    /** The format a source's units are written in: Mermaid, naming the source in its header. */
    public static EntityDiagramFormat format(EntityModelSource source) {
        return new MermaidEntityDiagramFormat(source.id());
    }
}
