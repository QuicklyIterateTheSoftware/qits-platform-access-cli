package eu.wohlben.qits.cli.access.database;

/**
 * A mapped foreign key: {@code from} holds {@code column} (several are joined with {@code ", "}),
 * which points at {@code to}'s key. {@code optional} when that column may be null.
 */
public record RelationModel(String from, String to, String column, boolean optional) {
}
