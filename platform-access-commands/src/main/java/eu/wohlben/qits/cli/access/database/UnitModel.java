package eu.wohlben.qits.cli.access.database;

import java.util.List;

/**
 * One diagram: a persistence unit the repository declares, or a Java package whose entities no unit
 * of the repository claims.
 *
 * @param name      the unit's name ({@code ci}, {@code <default>}), or the package's
 * @param fileName  the file under the output directory: {@code ci.md}, {@code default.md},
 *                  {@code eu.wohlben.qits.blobstore.entity.md}
 * @param heading   the Markdown heading, without its {@code #}
 * @param notes     the summary line's parts, in order; the format joins them with {@code " · "}
 * @param tables    sorted by name
 * @param relations sorted by (from, to, column)
 * @param notDrawn  what the mapping holds and the diagram leaves out, each as
 *                  {@code `Class.member` (`@Annotation`)}, sorted
 */
public record UnitModel(String name, String fileName, String heading, List<String> notes,
                        List<TableModel> tables, List<RelationModel> relations, List<String> notDrawn) {

    public UnitModel {
        notes = List.copyOf(notes);
        tables = List.copyOf(tables);
        relations = List.copyOf(relations);
        notDrawn = List.copyOf(notDrawn);
    }
}
