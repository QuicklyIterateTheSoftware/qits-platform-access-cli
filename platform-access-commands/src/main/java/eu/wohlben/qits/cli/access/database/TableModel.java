package eu.wohlben.qits.cli.access.database;

import java.util.List;

/**
 * One table.
 *
 * @param javaClass the entity's class, or for a collection table {@code <owner class>.<attribute>}
 * @param origin    the reactor module that compiled it, or the library's artifactId
 * @param columns   the primary key in key order, then the rest by name
 */
public record TableModel(String name, String javaClass, String origin, List<ColumnModel> columns) {

    public TableModel {
        columns = List.copyOf(columns);
    }
}
