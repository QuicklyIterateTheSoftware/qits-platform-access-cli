package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code entity-changes} payload, version 1 (epic qits-760, Design §5): the generated entity
 * diagrams under {@code docs/database/} at the fold, compared unit by unit with the same files at the
 * baseline's tag. Every record here is written and read back by Jackson, hence the registrations.
 *
 * @param baseline  what was compared with; null without a baseline, or when its tag could not be had
 * @param units     one per generated file on either side, by file
 * @param truncated true when {@code after} texts were left out to keep the payload under 1 MiB
 */
@RegisterForReflection
public record EntityChanges(BaselineSide baseline, List<Unit> units, boolean truncated) {

    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String CHANGED = "CHANGED";
    public static final String UNCHANGED = "UNCHANGED";
    /** No baseline, or a baseline without a diagram: the diagram is new, not "N tables added". */
    public static final String CURRENT = "CURRENT";

    public EntityChanges {
        units = List.copyOf(units);
    }

    /**
     * @param version    the baseline's version, which is also its tag
     * @param tagSha     the commit the tag names, as qits-ci said; null when it did not say
     * @param hadDiagram false when the tag holds no generated diagram: a version from before them
     */
    @RegisterForReflection
    public record BaselineSide(String version, String tagSha, boolean hadDiagram) {
    }

    /**
     * One generated file.
     *
     * @param file      its path, relative to the step's root: {@code docs/database/ci.md}
     * @param unit      the persistence unit or package it draws, from its heading
     * @param status    {@code ADDED}, {@code REMOVED}, {@code CHANGED}, {@code UNCHANGED} or {@code CURRENT}
     * @param tables    the tables that are not unchanged, by name
     * @param relations the relations added and removed, as rendered lines
     * @param before    the Mermaid block at the baseline, only for CHANGED and REMOVED; the raw file
     *                  when it does not parse
     * @param after     the Mermaid block at the fold, for every unit the fold has; null when left out
     *                  for size
     * @param notes     what the lists above cannot say: a file that does not parse, a change only to
     *                  the unit's description
     */
    @RegisterForReflection
    public record Unit(String file, String unit, String status, List<Table> tables, Relations relations,
                       String before, String after, List<String> notes) {

        public Unit {
            tables = List.copyOf(tables);
            notes = List.copyOf(notes);
        }

        Unit withAfter(String text) {
            return new Unit(file, unit, status, tables, relations, before, text, notes);
        }
    }

    /**
     * One table that is not unchanged.
     *
     * @param status {@code ADDED}, {@code REMOVED}, {@code CHANGED}, or {@code CURRENT} in a unit that is
     * @param origin the module or library that declares it, at the fold (at the baseline for a removed one)
     */
    @RegisterForReflection
    public record Table(String name, String status, String origin, Columns columns) {
    }

    /**
     * @param added   {@code "<name>: <description>"}, every column of an added table
     * @param removed column names, every column of a removed table
     * @param changed the columns whose description differs
     */
    @RegisterForReflection
    public record Columns(List<String> added, List<String> removed, List<ColumnChange> changed) {

        public Columns {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            changed = List.copyOf(changed);
        }

        static Columns none() {
            return new Columns(List.of(), List.of(), List.of());
        }
    }

    /**
     * {@code before} and {@code after} as {@code "<type>, <null|not null>[, PK][, FK][, UK][, <length>]"},
     * then the column's flags ({@code lob}, {@code version}, {@code generated}).
     */
    @RegisterForReflection
    public record ColumnChange(String name, String before, String after) {
    }

    /** Rendered as {@code from }o--|| to : column}, sorted. */
    @RegisterForReflection
    public record Relations(List<String> added, List<String> removed) {

        public Relations {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }

        static Relations none() {
            return new Relations(List.of(), List.of());
        }
    }
}
