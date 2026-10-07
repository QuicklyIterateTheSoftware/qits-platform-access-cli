package eu.wohlben.qits.cli.access.database;

/** One output format. Mermaid erDiagram is the only one; the report kind reads it back. */
public interface EntityDiagramFormat {

    String render(UnitModel unit);

    UnitModel parse(String text);
}
