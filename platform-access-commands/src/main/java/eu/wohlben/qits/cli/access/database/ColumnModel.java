package eu.wohlben.qits.cli.access.database;

import java.util.List;

/**
 * One column, as the mapping makes it.
 *
 * @param type   a logical word ({@code string}, {@code uuid}, {@code enum}, ...) or a plain-word
 *               {@code columnDefinition}
 * @param length the length the mapping states for a {@code string} or {@code bytes} column; null
 *               when it states none
 * @param flags  what else the diagram's comment says about it, in this order: {@code lob},
 *               {@code version}, {@code generated}. The one component beyond the epic's Design §1:
 *               the file format writes these words, and a record that cannot hold them could not be
 *               read back from it. The seven-argument constructor is Design §1's.
 */
public record ColumnModel(String name, String type, boolean primaryKey, boolean foreignKey,
                          boolean unique, boolean nullable, Integer length, List<String> flags) {

    public static final String LOB = "lob";
    public static final String VERSION = "version";
    public static final String GENERATED = "generated";

    public ColumnModel {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    public ColumnModel(String name, String type, boolean primaryKey, boolean foreignKey,
                       boolean unique, boolean nullable, Integer length) {
        this(name, type, primaryKey, foreignKey, unique, nullable, length, List.of());
    }
}
