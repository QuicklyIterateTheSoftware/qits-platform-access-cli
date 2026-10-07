package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import eu.wohlben.qits.cli.access.database.ColumnModel;
import eu.wohlben.qits.cli.access.database.MermaidEntityDiagramFormat;
import eu.wohlben.qits.cli.access.database.RelationModel;
import eu.wohlben.qits.cli.access.database.TableModel;
import eu.wohlben.qits.cli.access.database.UnitModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * {@code entity-changes}, version 1 (epic qits-760, Design §5): the generated entity diagrams under
 * {@code docs/database/} at the fold, compared with the same files at the baseline's tag. The diff is
 * semantic: both sides are parsed back with {@link MermaidEntityDiagramFormat} and compared by unit
 * (file), table (name), column (name) and relation (rendered line).
 *
 * <p>Fed by no parser: its input is the committed tree, which every QA step has, so it reports from
 * step 0 only. A hand-written file in {@code docs/database/} (one without the generator's header) is
 * not a diagram and is ignored on both sides.
 *
 * <p>Never fails the collect: a file that does not parse is a {@code CHANGED} unit with its raw texts
 * and a note, and a baseline tag that cannot be had is no baseline, said once by {@link BaselineTag}.
 */
public final class EntityChangesReportKind implements ReportKind<EntityChanges> {

    public static final String ID = "entity-changes";
    public static final int VERSION = 1;

    /** Where the generator writes, relative to the step's root. */
    public static final String DIRECTORY = "docs/database";

    /** Above this, {@code after} texts are dropped: UNCHANGED units' first, then CURRENT units'. */
    static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    static final String MINUS = "−";

    private static final String FENCE = "```";

    private final Consumer<String> warnings;
    private final MermaidEntityDiagramFormat format = new MermaidEntityDiagramFormat("jpa");

    public EntityChangesReportKind(Consumer<String> warnings) {
        this.warnings = warnings;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Class<EntityChanges> payloadType() {
        return EntityChanges.class;
    }

    @Override
    public Optional<EntityChanges> collect(StepContext step, List<ReportParser<?>> parsers) throws IOException {
        if (step.stepIndex() != 0) {
            return Optional.empty();
        }
        Map<String, String> fold = foldFiles(step.root());
        Optional<Map<String, String>> baseline = baselineFiles(step);
        if (fold.isEmpty() && baseline.map(Map::isEmpty).orElse(true)) {
            return Optional.empty();
        }
        EntityChanges.BaselineSide side = baseline.isEmpty() ? null
                : new EntityChanges.BaselineSide(step.baseline().get().version(), step.baseline().get().tagSha(),
                        !baseline.get().isEmpty());
        boolean current = side == null || !side.hadDiagram();
        List<EntityChanges.Unit> units = new ArrayList<>();
        if (current) {
            fold.forEach((file, text) -> units.add(currentUnit(file, text)));
        } else {
            TreeSet<String> files = new TreeSet<>(fold.keySet());
            files.addAll(baseline.get().keySet());
            for (String file : files) {
                units.add(compared(file, baseline.get().get(file), fold.get(file)));
            }
        }
        return Optional.of(capped(new EntityChanges(side, units, false)));
    }

    // --- the two sides ---------------------------------------------------------------------------

    /** The generated files under the step's root, by path relative to it. */
    private Map<String, String> foldFiles(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Path directory = root.resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return files;
        }
        List<Path> candidates;
        try (Stream<Path> listed = Files.list(directory)) {
            candidates = listed.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : candidates) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                warnings.accept(ID + ": skipped " + DIRECTORY + "/" + file.getFileName() + ": " + unreadable.getMessage());
                continue;
            }
            if (MermaidEntityDiagramFormat.isGenerated(text)) {
                files.put(DIRECTORY + "/" + file.getFileName(), text);
            }
        }
        return files;
    }

    /**
     * The generated files at the baseline's tag, by path; empty when it holds none. No map at all
     * without a baseline or when its tag could not be had (the tag has said why, once).
     */
    private static Optional<Map<String, String>> baselineFiles(StepContext step) {
        if (step.baseline().isEmpty()) {
            return Optional.empty();
        }
        BaselineTag tag = step.baselineTag();
        try {
            if (tag.ref().isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> files = new TreeMap<>();
            for (String name : tag.list(DIRECTORY)) {
                if (!name.endsWith(".md")) {
                    continue;
                }
                String path = DIRECTORY + "/" + name;
                Optional<byte[]> bytes = tag.read(path);
                if (bytes.isEmpty()) {
                    continue;
                }
                String text = new String(bytes.get(), StandardCharsets.UTF_8);
                if (MermaidEntityDiagramFormat.isGenerated(text)) {
                    files.put(path, text);
                }
            }
            return Optional.of(files);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // --- one unit --------------------------------------------------------------------------------

    /** A file and what was read from it; {@code model} null when it does not parse. */
    private record Side(String text, UnitModel model, String block) {
    }

    private Side side(String text) {
        if (text == null) {
            return null;
        }
        String block = block(text);
        if (block == null) {
            return new Side(text, null, null);
        }
        try {
            return new Side(text, format.parse(text), block);
        } catch (RuntimeException unparsable) {
            return new Side(text, null, null);
        }
    }

    private EntityChanges.Unit currentUnit(String file, String text) {
        Side after = side(text);
        if (after.model() == null) {
            return unparsable(file, EntityChanges.CURRENT, null, after);
        }
        List<EntityChanges.Table> tables = after.model().tables().stream()
                .sorted(Comparator.comparing(TableModel::name))
                .map(t -> new EntityChanges.Table(t.name(), EntityChanges.CURRENT, t.origin(),
                        EntityChanges.Columns.none()))
                .toList();
        return new EntityChanges.Unit(file, unitName(file, after), EntityChanges.CURRENT, tables,
                EntityChanges.Relations.none(), null, after.block(), List.of());
    }

    private EntityChanges.Unit compared(String file, String beforeText, String afterText) {
        Side before = side(beforeText);
        Side after = side(afterText);
        if ((before != null && before.model() == null) || (after != null && after.model() == null)) {
            return unparsable(file, EntityChanges.CHANGED, before, after);
        }
        UnitModel was = before == null ? null : before.model();
        UnitModel is = after == null ? null : after.model();
        List<EntityChanges.Table> tables = tables(was == null ? List.of() : was.tables(),
                is == null ? List.of() : is.tables());
        EntityChanges.Relations relations = relations(was == null ? List.of() : was.relations(),
                is == null ? List.of() : is.relations());
        String status;
        List<String> notes = new ArrayList<>();
        if (was == null) {
            status = EntityChanges.ADDED;
        } else if (is == null) {
            status = EntityChanges.REMOVED;
        } else if (!tables.isEmpty() || !relations.added().isEmpty() || !relations.removed().isEmpty()) {
            status = EntityChanges.CHANGED;
        } else {
            List<String> other = describedOtherwise(was, is);
            if (other.isEmpty()) {
                status = EntityChanges.UNCHANGED;
            } else {
                status = EntityChanges.CHANGED;
                notes.add("Only the unit's description changed: " + String.join(", ", other));
            }
        }
        boolean withBefore = status.equals(EntityChanges.CHANGED) || status.equals(EntityChanges.REMOVED);
        return new EntityChanges.Unit(file, unitName(file, after != null ? after : before), status, tables, relations,
                withBefore ? before.block() : null, after == null ? null : after.block(), notes);
    }

    private static EntityChanges.Unit unparsable(String file, String status, Side before, Side after) {
        List<String> notes = new ArrayList<>();
        if (before != null && before.model() == null) {
            notes.add("The baseline's " + file + " does not parse as an entity diagram");
        }
        if (after != null && after.model() == null) {
            notes.add(file + " does not parse as an entity diagram");
        }
        String unit = unitName(file, after != null && after.model() != null ? after
                : before != null && before.model() != null ? before : null);
        return new EntityChanges.Unit(file, unit, status, List.of(), EntityChanges.Relations.none(),
                before == null ? null : before.model() == null ? before.text() : before.block(),
                after == null ? null : after.model() == null ? after.text() : after.block(), notes);
    }

    /** The unit's name from its heading, else the file's name without {@code .md}. */
    private static String unitName(String file, Side side) {
        if (side != null && side.model() != null && side.model().name() != null && !side.model().name().isBlank()) {
            return side.model().name();
        }
        String name = file.substring(file.lastIndexOf('/') + 1);
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    /** What else differs between two units the tables and relations do not show. */
    private static List<String> describedOtherwise(UnitModel was, UnitModel is) {
        List<String> other = new ArrayList<>();
        if (!Objects.equals(was.heading(), is.heading())) {
            other.add("heading");
        }
        if (!Objects.equals(was.notes(), is.notes())) {
            other.add("summary");
        }
        Map<String, String> wasClasses = new TreeMap<>();
        was.tables().forEach(t -> wasClasses.put(t.name(), String.valueOf(t.javaClass())));
        Map<String, String> isClasses = new TreeMap<>();
        is.tables().forEach(t -> isClasses.put(t.name(), String.valueOf(t.javaClass())));
        if (!wasClasses.equals(isClasses)) {
            other.add("entity classes");
        }
        if (!Objects.equals(was.notDrawn(), is.notDrawn())) {
            other.add("not drawn");
        }
        return other;
    }

    // --- tables, columns and relations -----------------------------------------------------------

    static List<EntityChanges.Table> tables(List<TableModel> before, List<TableModel> after) {
        Map<String, TableModel> was = byName(before);
        Map<String, TableModel> is = byName(after);
        TreeSet<String> names = new TreeSet<>(was.keySet());
        names.addAll(is.keySet());
        List<EntityChanges.Table> tables = new ArrayList<>();
        for (String name : names) {
            TableModel old = was.get(name);
            TableModel now = is.get(name);
            if (old == null) {
                tables.add(new EntityChanges.Table(name, EntityChanges.ADDED, now.origin(), new EntityChanges.Columns(
                        now.columns().stream().map(c -> c.name() + ": " + describe(c)).toList(), List.of(),
                        List.of())));
            } else if (now == null) {
                tables.add(new EntityChanges.Table(name, EntityChanges.REMOVED, old.origin(), new EntityChanges.Columns(
                        List.of(), old.columns().stream().map(ColumnModel::name).toList(), List.of())));
            } else {
                EntityChanges.Columns columns = columns(old.columns(), now.columns());
                boolean changed = !columns.added().isEmpty() || !columns.removed().isEmpty()
                        || !columns.changed().isEmpty() || !Objects.equals(old.origin(), now.origin());
                if (changed) {
                    tables.add(new EntityChanges.Table(name, EntityChanges.CHANGED, now.origin(), columns));
                }
            }
        }
        return tables;
    }

    static EntityChanges.Columns columns(List<ColumnModel> before, List<ColumnModel> after) {
        Map<String, ColumnModel> was = new TreeMap<>();
        before.forEach(c -> was.putIfAbsent(c.name(), c));
        Map<String, ColumnModel> is = new TreeMap<>();
        after.forEach(c -> is.putIfAbsent(c.name(), c));
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<EntityChanges.ColumnChange> changed = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(was.keySet());
        names.addAll(is.keySet());
        for (String name : names) {
            ColumnModel old = was.get(name);
            ColumnModel now = is.get(name);
            if (old == null) {
                added.add(name + ": " + describe(now));
            } else if (now == null) {
                removed.add(name);
            } else if (!sameColumn(old, now)) {
                changed.add(new EntityChanges.ColumnChange(name, describe(old), describe(now)));
            }
        }
        return new EntityChanges.Columns(added, removed, changed);
    }

    private static boolean sameColumn(ColumnModel a, ColumnModel b) {
        return a.type().equals(b.type()) && a.primaryKey() == b.primaryKey() && a.foreignKey() == b.foreignKey()
                && a.unique() == b.unique() && a.nullable() == b.nullable() && Objects.equals(a.length(), b.length())
                && a.flags().equals(b.flags());
    }

    /** {@code "<type>, <null|not null>[, PK][, FK][, UK][, <length>]"}, then the flags. */
    static String describe(ColumnModel column) {
        StringBuilder out = new StringBuilder(column.type()).append(", ")
                .append(column.nullable() ? "null" : "not null");
        if (column.primaryKey()) {
            out.append(", PK");
        }
        if (column.foreignKey()) {
            out.append(", FK");
        }
        if (column.unique()) {
            out.append(", UK");
        }
        if (column.length() != null) {
            out.append(", ").append(column.length());
        }
        for (String flag : column.flags()) {
            out.append(", ").append(flag);
        }
        return out.toString();
    }

    static EntityChanges.Relations relations(List<RelationModel> before, List<RelationModel> after) {
        List<String> was = MermaidEntityDiagramFormat.sortedRelations(before).stream()
                .map(EntityChangesReportKind::line).distinct().toList();
        List<String> is = MermaidEntityDiagramFormat.sortedRelations(after).stream()
                .map(EntityChangesReportKind::line).distinct().toList();
        return new EntityChanges.Relations(is.stream().filter(r -> !was.contains(r)).toList(),
                was.stream().filter(r -> !is.contains(r)).toList());
    }

    /** As the diagram draws it, without the label's quotes: {@code ci_step }o--|| ci_run : run_id}. */
    static String line(RelationModel relation) {
        return relation.from() + (relation.optional() ? " }o--o| " : " }o--|| ") + relation.to() + " : "
                + relation.column();
    }

    private static Map<String, TableModel> byName(List<TableModel> tables) {
        Map<String, TableModel> byName = new LinkedHashMap<>();
        tables.forEach(t -> byName.putIfAbsent(t.name(), t));
        return byName;
    }

    /**
     * The fenced Mermaid block's lines, without the fences: what a Mermaid renderer is given. Null
     * when the file has no {@code erDiagram} block, which makes it a file that does not parse.
     */
    static String block(String text) {
        List<String> lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        StringBuilder block = null;
        for (String line : lines) {
            String stripped = line.strip();
            if (block == null) {
                if (stripped.startsWith(FENCE) && stripped.substring(FENCE.length()).strip().equals("mermaid")) {
                    block = new StringBuilder();
                }
                continue;
            }
            if (stripped.startsWith(FENCE)) {
                String body = block.toString();
                return body.strip().startsWith("erDiagram") ? body : null;
            }
            block.append(line).append('\n');
        }
        return null;
    }

    // --- the size cap ----------------------------------------------------------------------------

    /**
     * The payload under {@link #MAX_PAYLOAD_BYTES}: the {@code after} texts of UNCHANGED units go
     * first, largest first, then those of CURRENT units, until it fits; {@code truncated} says so.
     */
    static EntityChanges capped(EntityChanges report) {
        if (size(report) <= MAX_PAYLOAD_BYTES) {
            return report;
        }
        List<EntityChanges.Unit> units = new ArrayList<>(report.units());
        for (String status : List.of(EntityChanges.UNCHANGED, EntityChanges.CURRENT)) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < units.size(); i++) {
                if (units.get(i).status().equals(status) && units.get(i).after() != null) {
                    order.add(i);
                }
            }
            order.sort(Comparator.comparingInt((Integer i) -> units.get(i).after().length()).reversed());
            for (int i : order) {
                units.set(i, units.get(i).withAfter(null));
                EntityChanges cut = new EntityChanges(report.baseline(), units, true);
                if (size(cut) <= MAX_PAYLOAD_BYTES) {
                    return cut;
                }
            }
        }
        return new EntityChanges(report.baseline(), units, true);
    }

    static int size(EntityChanges report) {
        try {
            return ReportJson.MAPPER.writeValueAsBytes(report).length;
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // --- highlights ------------------------------------------------------------------------------

    @Override
    public List<Highlight> highlight(EntityChanges report, Optional<EntityChanges> baseline, StepContext step) {
        List<Highlight> highlights = new ArrayList<>();
        String version = report.baseline() == null ? "" : report.baseline().version();
        int added = 0;
        int changed = 0;
        int removed = 0;
        int narrowed = 0;
        boolean unitChanged = false;
        int currentTables = 0;
        int currentUnits = 0;
        for (EntityChanges.Unit unit : report.units()) {
            switch (unit.status()) {
                case EntityChanges.ADDED, EntityChanges.REMOVED, EntityChanges.CHANGED -> unitChanged = true;
                case EntityChanges.CURRENT -> {
                    currentUnits++;
                    currentTables += unit.tables().size();
                }
                default -> {
                }
            }
            for (EntityChanges.Table table : unit.tables()) {
                switch (table.status()) {
                    case EntityChanges.ADDED -> added++;
                    case EntityChanges.REMOVED -> removed++;
                    case EntityChanges.CHANGED -> {
                        changed++;
                        narrowed += table.columns().removed().size();
                        narrowed += (int) table.columns().changed().stream()
                                .filter(EntityChangesReportKind::narrows).count();
                    }
                    default -> {
                    }
                }
            }
        }
        if (unitChanged) {
            highlights.add(new Highlight(Highlight.WARN, "Entities changed since " + version + ": +" + added + " ~"
                    + changed + " " + MINUS + removed + " tables", "entities.tables.changed",
                    (double) (added + changed + removed), null));
        }
        if (narrowed > 0) {
            highlights.add(new Highlight(Highlight.WARN, "Columns removed or narrowed: " + narrowed,
                    "entities.columns.narrowed", (double) narrowed, null));
        }
        if (currentUnits > 0) {
            highlights.add(new Highlight(Highlight.INFO, "Entity diagram is new: " + currentTables
                    + (currentTables == 1 ? " table in " : " tables in ")
                    + currentUnits + (currentUnits == 1 ? " unit" : " units"), "entities.tables",
                    (double) currentTables, null));
        }
        if (highlights.isEmpty()) {
            highlights.add(new Highlight(Highlight.GOOD, "Entities unchanged since " + version, null, null, null));
        }
        return highlights;
    }

    /**
     * True when a changed column can lose data or refuse what it took: not null gained, another type,
     * or a shorter length. Read back from the descriptions, which is all the payload keeps.
     */
    static boolean narrows(EntityChanges.ColumnChange change) {
        String[] before = change.before().split(", ");
        String[] after = change.after().split(", ");
        if (!before[0].equals(after[0])) {
            return true;
        }
        if (before.length > 1 && after.length > 1 && before[1].equals("null") && after[1].equals("not null")) {
            return true;
        }
        Integer was = length(before);
        Integer is = length(after);
        return was != null && is != null && is < was;
    }

    private static Integer length(String[] parts) {
        for (int i = 2; i < parts.length; i++) {
            if (!parts[i].isEmpty() && parts[i].chars().allMatch(Character::isDigit)) {
                try {
                    return Integer.valueOf(parts[i]);
                } catch (NumberFormatException tooLong) {
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public String describe(EntityChanges report) {
        int units = report.units().size();
        long changed = report.units().stream().filter(u -> !u.status().equals(EntityChanges.UNCHANGED)
                && !u.status().equals(EntityChanges.CURRENT)).count();
        long current = report.units().stream().filter(u -> u.status().equals(EntityChanges.CURRENT)).count();
        String said = units + (units == 1 ? " unit" : " units");
        if (current > 0) {
            return said + ", new";
        }
        return said + ", " + changed + " changed";
    }
}
