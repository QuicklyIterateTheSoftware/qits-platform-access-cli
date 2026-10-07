package eu.wohlben.qits.cli.access.database;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.PrimitiveType;
import org.jboss.jandex.Type;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The JPA mapping of a set of entity classes, reconstructed from their class files the way Hibernate
 * reads them under Quarkus' defaults: the JPA-compliant implicit naming, and Hibernate's standard
 * physical naming (the name as the mapping spells it) unless the unit names
 * {@code CamelCaseToUnderscoresNamingStrategy}. {@code JpaMappingConformanceTest} holds the result
 * against Hibernate's own {@code Metadata}, under both, for a fixture of every construct below.
 * <p>
 * Drawn: {@code @Table(name, uniqueConstraints)}, {@code @Id}, {@code @IdClass},
 * {@code @GeneratedValue}, {@code @Column(name, nullable, length, unique, columnDefinition)},
 * {@code @Enumerated}, {@code @Lob}, {@code @JdbcTypeCode}, {@code @Convert}, {@code @Version},
 * {@code @Transient} (and {@code static} and {@code transient} fields), {@code @Embedded} /
 * {@code @Embeddable} (with {@code @AttributeOverride}), {@code @MappedSuperclass},
 * {@code @ElementCollection} / {@code @CollectionTable} (with {@code @OrderColumn} and
 * {@code @MapKeyColumn}), {@code @ManyToOne} / {@code @JoinColumn}, {@code @OneToMany(mappedBy)} and
 * the unidirectional {@code @OneToMany} with {@code @JoinColumn}, and Bean Validation's
 * {@code @NotNull}. Access follows where {@code @Id} sits.
 * <p>
 * Anything else is listed, never refused: {@code `Class.member` (`@Annotation`)} goes into the unit's
 * "not drawn" list, and nothing here throws on a mapping it does not know.
 */
final class JpaMapping {

    private static final String JPA = "jakarta.persistence.";

    static final DotName ENTITY = jpa("Entity");
    static final DotName MAPPED_SUPERCLASS = jpa("MappedSuperclass");
    static final DotName EMBEDDABLE = jpa("Embeddable");
    private static final DotName TABLE = jpa("Table");
    private static final DotName ID = jpa("Id");
    private static final DotName ID_CLASS = jpa("IdClass");
    private static final DotName EMBEDDED_ID = jpa("EmbeddedId");
    private static final DotName GENERATED_VALUE = jpa("GeneratedValue");
    private static final DotName COLUMN = jpa("Column");
    private static final DotName BASIC = jpa("Basic");
    private static final DotName ENUMERATED = jpa("Enumerated");
    private static final DotName MAP_KEY_ENUMERATED = jpa("MapKeyEnumerated");
    private static final DotName LOB = jpa("Lob");
    private static final DotName CONVERT = jpa("Convert");
    private static final DotName VERSION = jpa("Version");
    private static final DotName TRANSIENT = jpa("Transient");
    private static final DotName EMBEDDED = jpa("Embedded");
    private static final DotName ATTRIBUTE_OVERRIDE = jpa("AttributeOverride");
    private static final DotName ATTRIBUTE_OVERRIDES = jpa("AttributeOverrides");
    private static final DotName ELEMENT_COLLECTION = jpa("ElementCollection");
    private static final DotName COLLECTION_TABLE = jpa("CollectionTable");
    private static final DotName ORDER_COLUMN = jpa("OrderColumn");
    private static final DotName MAP_KEY_COLUMN = jpa("MapKeyColumn");
    private static final DotName MANY_TO_ONE = jpa("ManyToOne");
    private static final DotName ONE_TO_MANY = jpa("OneToMany");
    private static final DotName JOIN_COLUMN = jpa("JoinColumn");
    private static final DotName JOIN_COLUMNS = jpa("JoinColumns");
    private static final DotName ACCESS = jpa("Access");
    private static final DotName INHERITANCE = jpa("Inheritance");
    private static final DotName SECONDARY_TABLE = jpa("SecondaryTable");
    private static final DotName SECONDARY_TABLES = jpa("SecondaryTables");
    private static final DotName NOT_NULL = DotName.createSimple("jakarta.validation.constraints.NotNull");
    private static final DotName JDBC_TYPE_CODE = DotName.createSimple("org.hibernate.annotations.JdbcTypeCode");
    private static final DotName TEMPORAL = jpa("Temporal");

    /** Members that carry one of these are listed as not drawn, under its simple name. */
    private static final List<DotName> NOT_DRAWN = List.of(jpa("OneToOne"), jpa("ManyToMany"), EMBEDDED_ID,
            jpa("MapsId"), jpa("JoinTable"), jpa("MapKeyJoinColumn"), jpa("MapKeyJoinColumns"),
            DotName.createSimple("org.hibernate.annotations.Formula"),
            DotName.createSimple("org.hibernate.annotations.Any"),
            DotName.createSimple("org.hibernate.annotations.ManyToAny"),
            DotName.createSimple("org.hibernate.annotations.JoinFormula"),
            DotName.createSimple("org.hibernate.annotations.JoinColumnOrFormula"),
            DotName.createSimple("org.hibernate.annotations.JoinColumnsOrFormulas"));

    private static final DotName OBJECT = DotName.createSimple("java.lang.Object");
    private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> SIZED = Set.of("string", "bytes");

    private final JpaInputs inputs;
    private final boolean snakeCase;
    private final Map<DotName, Optional<Shape>> shapes = new HashMap<>();

    /**
     * {@code snakeCase} when the unit names {@code CamelCaseToUnderscoresNamingStrategy} as its
     * physical naming strategy; otherwise Hibernate's default, {@code PhysicalNamingStrategyStandardImpl},
     * which keeps every name as the mapping spells it. Quarkus sets none of its own.
     */
    JpaMapping(JpaInputs inputs, boolean snakeCase) {
        this.inputs = inputs;
        this.snakeCase = snakeCase;
    }

    /** What one unit's entities map to. */
    record Mapped(List<TableModel> tables, List<RelationModel> relations, List<String> notDrawn, int entities) {
    }

    // ---------------------------------------------------------------- the model being built

    private static final class TableBuilder {
        final String name;
        final String javaClass;
        final String origin;
        final List<String> keyOrder = new ArrayList<>();
        final Map<String, Col> columns = new LinkedHashMap<>();

        TableBuilder(String name, String javaClass, String origin) {
            this.name = name;
            this.javaClass = javaClass;
            this.origin = origin;
        }

        /** A column of the same name is one column, as it is to Hibernate: the two mappings merge. */
        Col add(Col column) {
            Col existing = columns.get(column.name);
            if (existing == null) {
                columns.put(column.name, column);
                return column;
            }
            existing.foreignKey |= column.foreignKey;
            existing.unique |= column.unique;
            existing.primaryKey |= column.primaryKey;
            existing.nullable &= column.nullable;
            for (String flag : column.flags) {
                if (!existing.flags.contains(flag)) {
                    existing.flags.add(flag);
                }
            }
            return existing;
        }

        void key(String column) {
            Col col = columns.get(column);
            if (col != null && !keyOrder.contains(column)) {
                keyOrder.add(column);
                col.primaryKey = true;
                col.nullable = false;
            }
        }

        TableModel build() {
            List<ColumnModel> ordered = new ArrayList<>();
            for (String key : keyOrder) {
                ordered.add(columns.get(key).model());
            }
            columns.values().stream().filter(col -> !keyOrder.contains(col.name))
                    .sorted(Comparator.comparing(col -> col.name)).forEach(col -> ordered.add(col.model()));
            return new TableModel(name, javaClass, origin, ordered);
        }
    }

    private static final class Col {
        final String name;
        String type;
        boolean primaryKey;
        boolean foreignKey;
        boolean unique;
        boolean nullable = true;
        Integer length;
        final List<String> flags = new ArrayList<>();

        Col(String name, String type) {
            this.name = name;
            this.type = type;
        }

        ColumnModel model() {
            List<String> ordered = new ArrayList<>();
            for (String flag : List.of(ColumnModel.LOB, ColumnModel.VERSION, ColumnModel.GENERATED)) {
                if (flags.contains(flag)) {
                    ordered.add(flag);
                }
            }
            return new ColumnModel(name, type, primaryKey, foreignKey, unique, nullable, length, ordered);
        }
    }

    /** A key column of an entity, as a foreign key pointing at it needs it. */
    private record KeyColumn(String logical, String physical, String type, Integer length) {
    }

    /** One persistent attribute: a field, or a getter's property. */
    private record Attribute(String name, Type type, ClassInfo declaring, Function<DotName, AnnotationInstance> annotations,
                             boolean primitive) {

        AnnotationInstance annotation(DotName name) {
            return annotations.apply(name);
        }

        boolean has(DotName name) {
            return annotations.apply(name) != null;
        }

        String member() {
            return simpleName(declaring.name()) + "." + name;
        }
    }

    /** An entity's place in the mapping: its table, its JPA name, its attributes and its key. */
    private record Shape(ClassInfo entity, String table, String entityName, boolean fieldAccess,
                         List<Attribute> attributes, List<Attribute> ids, List<KeyColumn> key, List<String> notDrawn,
                         boolean subclass) {
    }

    // ---------------------------------------------------------------- the whole unit

    Mapped map(Collection<ClassInfo> entities) {
        Map<String, TableBuilder> tables = new LinkedHashMap<>();
        List<RelationModel> relations = new ArrayList<>();
        Set<String> notDrawn = new TreeSet<>();
        List<Runnable> afterTables = new ArrayList<>();
        int drawn = 0;
        for (ClassInfo entity : entities) {
            try {
                Optional<Shape> shape = shape(entity.name());
                if (shape.isEmpty()) {
                    continue;
                }
                Shape s = shape.get();
                notDrawn.addAll(s.notDrawn());
                if (s.subclass()) {
                    continue;
                }
                mapEntity(s, tables, relations, notDrawn, afterTables);
                drawn++;
            } catch (RuntimeException unexpected) {
                // A class file shaped in a way nothing above foresaw: say so under the diagram rather
                // than hold every release of the repository behind a CLI release.
                tables.remove(shapes.getOrDefault(entity.name(), Optional.empty()).map(Shape::table).orElse(""));
                notDrawn.add("`" + simpleName(entity.name()) + "` (`" + unexpected.getClass().getSimpleName()
                        + "` while reading it)");
            }
        }
        afterTables.forEach(Runnable::run);
        // The unidirectional @OneToMany's column lands on the many side, which may sit in another unit.
        List<TableModel> built = tables.values().stream().filter(t -> t.javaClass != null)
                .map(TableBuilder::build).sorted(Comparator.comparing(TableModel::name)).toList();
        return new Mapped(built, MermaidEntityDiagramFormat.sortedRelations(dedupe(relations)), List.copyOf(notDrawn),
                drawn);
    }

    private static List<RelationModel> dedupe(List<RelationModel> relations) {
        return List.copyOf(new LinkedHashSet<>(relations));
    }

    private void mapEntity(Shape shape, Map<String, TableBuilder> tables, List<RelationModel> relations,
                           Set<String> notDrawn, List<Runnable> afterTables) {
        ClassInfo entity = shape.entity();
        TableBuilder table = new TableBuilder(shape.table(), entity.name().toString(), inputs.origin(entity.name()));
        tables.put(shape.table(), table);

        // The key first: an attribute that is both key and relation is one column, and collection
        // tables and foreign keys need the key's columns.
        List<Attribute> ids = sortedIds(shape.ids());
        for (Attribute id : ids) {
            for (Col col : columns(id, shape, notDrawn, relations, tables, afterTables, table)) {
                table.key(col.name);
            }
        }
        for (Attribute attribute : shape.attributes()) {
            if (shape.ids().contains(attribute)) {
                continue;
            }
            columns(attribute, shape, notDrawn, relations, tables, afterTables, table);
        }
        AnnotationInstance tableAnnotation = entity.declaredAnnotation(TABLE);
        if (tableAnnotation != null && tableAnnotation.value("uniqueConstraints") != null) {
            for (AnnotationInstance constraint : tableAnnotation.value("uniqueConstraints").asNestedArray()) {
                AnnotationValue names = constraint.value("columnNames");
                if (names == null) {
                    continue;
                }
                for (String column : names.asStringArray()) {
                    Col col = table.columns.get(physical(column));
                    if (col != null) {
                        col.unique = true;
                    }
                }
            }
        }
    }

    /** Hibernate binds a composite key's parts by attribute name, whatever order they were declared in. */
    private static List<Attribute> sortedIds(List<Attribute> ids) {
        List<Attribute> sorted = new ArrayList<>(ids);
        if (sorted.size() > 1) {
            sorted.sort(Comparator.comparing(Attribute::name));
        }
        return sorted;
    }

    /** Maps one attribute of an entity into its table (and collection tables); returns its own columns. */
    private List<Col> columns(Attribute attribute, Shape shape, Set<String> notDrawn, List<RelationModel> relations,
                              Map<String, TableBuilder> tables, List<Runnable> afterTables, TableBuilder table) {
        String unsupported = unsupported(attribute);
        if (unsupported != null) {
            notDrawn.add("`" + attribute.member() + "` (`@" + unsupported + "`)");
            return List.of();
        }
        if (attribute.has(ELEMENT_COLLECTION)) {
            elementCollection(attribute, shape, notDrawn, relations, tables);
            return List.of();
        }
        if (attribute.has(ONE_TO_MANY)) {
            oneToMany(attribute, shape, notDrawn, relations, tables, afterTables);
            return List.of();
        }
        if (attribute.has(MANY_TO_ONE)) {
            if (attribute.has(ID)) {
                notDrawn.add("`" + attribute.member() + "` (`@Id @ManyToOne`)");
                return List.of();
            }
            return manyToOne(attribute, shape, notDrawn, relations, table);
        }
        if (isCollection(attribute.type())) {
            notDrawn.add("`" + attribute.member() + "` (`collection without @ElementCollection or @OneToMany`)");
            return List.of();
        }
        Optional<ClassInfo> embeddable = embeddable(attribute);
        if (embeddable.isPresent()) {
            List<Col> added = new ArrayList<>();
            for (Col col : embedded(attribute, embeddable.get(), "", overrides(attribute, ""), shape.fieldAccess(),
                    notDrawn, 0)) {
                added.add(table.add(col));
            }
            return added;
        }
        if (attribute.has(EMBEDDED)) {
            notDrawn.add("`" + attribute.member() + "` (`@Embedded`)");
            return List.of();
        }
        if (isEntity(attribute.type().name())) {
            notDrawn.add("`" + attribute.member() + "` (`entity without @ManyToOne`)");
            return List.of();
        }
        Col col = basic(attribute, attribute.annotation(COLUMN), physical(attribute.name()), attribute.has(ID), false);
        return List.of(table.add(col));
    }

    private static String unsupported(Attribute attribute) {
        for (DotName name : NOT_DRAWN) {
            if (attribute.has(name)) {
                return simpleName(name);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- basic columns

    /**
     * A basic column: named by {@code @Column} or by {@code implicitName}, typed and constrained. In an
     * embeddable ({@code component}) a primitive does not make its column not null: the whole
     * embedded value may be null, and Hibernate leaves such columns nullable.
     */
    private Col basic(Attribute attribute, AnnotationInstance column, String implicitName, boolean id,
                      boolean component) {
        String name = column != null && !string(column, "name").isBlank() ? physical(string(column, "name")) : implicitName;
        Col col = new Col(name, logicalType(attribute, attribute.type(), column, false));
        if (column != null && column.value("length") != null && SIZED.contains(col.type) && !attribute.has(LOB)) {
            col.length = column.value("length").asInt();
        }
        col.unique = column != null && bool(column, "unique", false);
        boolean notNull = id || (attribute.primitive() && !component) || attribute.has(NOT_NULL)
                || (column != null && !bool(column, "nullable", true))
                || (attribute.has(BASIC) && !bool(attribute.annotation(BASIC), "optional", true));
        col.nullable = !notNull;
        if (attribute.has(LOB)) {
            col.flags.add(ColumnModel.LOB);
        }
        if (attribute.has(VERSION)) {
            col.flags.add(ColumnModel.VERSION);
        }
        if (attribute.has(GENERATED_VALUE)) {
            col.flags.add(ColumnModel.GENERATED);
        }
        return col;
    }

    /**
     * The logical type: a plain-word {@code columnDefinition} wins, then {@code @Convert},
     * {@code @JdbcTypeCode}, {@code @Lob}, an enum, and the Java type. {@code key} reads the map-key
     * annotations instead of the value's.
     */
    private String logicalType(Attribute attribute, Type type, AnnotationInstance column, boolean key) {
        if (column != null) {
            String definition = string(column, "columnDefinition").strip();
            if (PLAIN_WORD.matcher(definition).matches()) {
                return definition.toLowerCase(Locale.ROOT);
            }
        }
        if (!key) {
            AnnotationInstance convert = attribute.annotation(CONVERT);
            if (convert != null && !bool(convert, "disableConversion", false)) {
                return "converted";
            }
            AnnotationInstance code = attribute.annotation(JDBC_TYPE_CODE);
            if (code != null && code.value() != null) {
                String byCode = jdbcType(code.value().asInt(), type);
                if (byCode != null) {
                    return byCode;
                }
            }
        }
        String java = javaType(type);
        if (!key && attribute.has(LOB)) {
            return java.equals("bytes") ? "bytes" : "text";
        }
        if (java == null) {
            // An enum, or a class the Java types do not name.
            Optional<ClassInfo> info = inputs.classInfo(type.name());
            AnnotationInstance enumerated = attribute.annotation(key ? MAP_KEY_ENUMERATED : ENUMERATED);
            if (enumerated != null || info.map(ClassInfo::isEnum).orElse(false)) {
                boolean string = enumerated != null && enumerated.value() != null
                        && enumerated.value().asEnum().equals("STRING");
                return string ? "enum" : "enum_ordinal";
            }
            return simpleName(type.name()).toLowerCase(Locale.ROOT);
        }
        if (!key && java.equals("timestamp") && type.name().toString().equals("java.util.Date")) {
            AnnotationInstance temporal = attribute.annotation(TEMPORAL);
            if (temporal != null && temporal.value() != null) {
                return switch (temporal.value().asEnum()) {
                    case "DATE" -> "date";
                    case "TIME" -> "time";
                    default -> "timestamp";
                };
            }
        }
        return java;
    }

    /** The logical word for a Java type; null for anything that is not a plain value type. */
    private static String javaType(Type type) {
        if (type.kind() == Type.Kind.ARRAY) {
            Type component = type.asArrayType().constituent();
            String name = component.name().toString();
            if (type.asArrayType().dimensions() == 1) {
                if (name.equals("byte") || name.equals("java.lang.Byte")) {
                    return "bytes";
                }
                if (name.equals("char") || name.equals("java.lang.Character")) {
                    return "string";
                }
            }
            return "bytes";
        }
        return switch (type.name().toString()) {
            case "java.lang.String", "char", "java.lang.Character" -> "string";
            case "java.util.UUID" -> "uuid";
            case "long", "java.lang.Long" -> "long";
            case "int", "java.lang.Integer" -> "int";
            case "short", "java.lang.Short", "byte", "java.lang.Byte" -> "short";
            case "boolean", "java.lang.Boolean" -> "boolean";
            case "double", "java.lang.Double", "float", "java.lang.Float" -> "double";
            case "java.math.BigDecimal", "java.math.BigInteger" -> "decimal";
            case "java.time.Instant" -> "instant";
            case "java.time.LocalDate", "java.sql.Date" -> "date";
            case "java.time.LocalTime", "java.time.OffsetTime", "java.sql.Time" -> "time";
            case "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.util.Date",
                 "java.util.Calendar", "java.sql.Timestamp" -> "timestamp";
            default -> null;
        };
    }

    /** {@code org.hibernate.type.SqlTypes}' codes, as words; null leaves the Java type to decide. */
    private static String jdbcType(int code, Type type) {
        return switch (code) {
            case 3001 -> "json";
            case 2009 -> "xml";
            case -1, 4001, 2005, -16, 4002, 2011 -> "text";
            case 12, 1, -9, -15 -> "string";
            case 3000 -> "uuid";
            case -2, -3, -4, 4003, 2004 -> "bytes";
            case 4 -> "int";
            case -5 -> "long";
            case 5, -6 -> "short";
            case 16, -7 -> "boolean";
            case 8, 6, 7 -> "double";
            case 2, 3 -> "decimal";
            case 91 -> "date";
            case 92, 2013, 3007 -> "time";
            case 93, 2014, 3003, 3005 -> "instant".equals(javaType(type)) ? "instant" : "timestamp";
            case 2003, 4000 -> "array";
            default -> null;
        };
    }

    // ---------------------------------------------------------------- embeddables

    private Optional<ClassInfo> embeddable(Attribute attribute) {
        if (attribute.type().kind() == Type.Kind.PRIMITIVE || attribute.type().kind() == Type.Kind.ARRAY) {
            return Optional.empty();
        }
        Optional<ClassInfo> info = inputs.classInfo(attribute.type().name());
        if (info.isPresent() && info.get().declaredAnnotation(EMBEDDABLE) != null) {
            return info;
        }
        return Optional.empty();
    }

    /** {@code @AttributeOverride}s on the attribute, by the path below it (prefixed with {@code prefix}). */
    private static Map<String, AnnotationInstance> overrides(Attribute attribute, String prefix) {
        Map<String, AnnotationInstance> overrides = new HashMap<>();
        List<AnnotationInstance> all = new ArrayList<>();
        AnnotationInstance one = attribute.annotation(ATTRIBUTE_OVERRIDE);
        if (one != null) {
            all.add(one);
        }
        AnnotationInstance many = attribute.annotation(ATTRIBUTE_OVERRIDES);
        if (many != null && many.value() != null) {
            all.addAll(List.of(many.value().asNestedArray()));
        }
        for (AnnotationInstance override : all) {
            AnnotationValue column = override.value("column");
            if (column != null) {
                overrides.put(prefix + string(override, "name"), column.asNested());
            }
        }
        return overrides;
    }

    /** An embeddable's columns, flattened: each named by its own attribute, as JPA's default naming does. */
    private List<Col> embedded(Attribute owner, ClassInfo embeddable, String path, Map<String, AnnotationInstance> overrides,
                               boolean fieldAccess, Set<String> notDrawn, int depth) {
        List<Col> columns = new ArrayList<>();
        if (depth > 8) {
            return columns;
        }
        for (Attribute attribute : attributes(embeddable, fieldAccess, Set.of(EMBEDDABLE, MAPPED_SUPERCLASS))) {
            String unsupported = unsupported(attribute);
            if (unsupported != null || attribute.has(ELEMENT_COLLECTION) || attribute.has(ONE_TO_MANY)
                    || attribute.has(MANY_TO_ONE) || isCollection(attribute.type())) {
                String what = unsupported != null ? unsupported
                        : attribute.has(ELEMENT_COLLECTION) ? "ElementCollection"
                        : attribute.has(ONE_TO_MANY) ? "OneToMany"
                        : attribute.has(MANY_TO_ONE) ? "ManyToOne" : "ElementCollection";
                notDrawn.add("`" + attribute.member() + "` (`@" + what + "` in an embeddable)");
                continue;
            }
            String attributePath = path.isEmpty() ? attribute.name() : path + "." + attribute.name();
            Optional<ClassInfo> nested = embeddable(attribute);
            if (nested.isPresent()) {
                Map<String, AnnotationInstance> deeper = new HashMap<>(overrides);
                deeper.putAll(overrides(attribute, attributePath + "."));
                columns.addAll(embedded(owner, nested.get(), attributePath, deeper, fieldAccess, notDrawn, depth + 1));
                continue;
            }
            AnnotationInstance column = overrides.getOrDefault(attributePath, attribute.annotation(COLUMN));
            columns.add(basic(attribute, column, physical(attribute.name()), false, true));
        }
        return columns;
    }

    // ---------------------------------------------------------------- relations

    private List<Col> manyToOne(Attribute attribute, Shape shape, Set<String> notDrawn, List<RelationModel> relations,
                                TableBuilder table) {
        AnnotationInstance manyToOne = attribute.annotation(MANY_TO_ONE);
        DotName targetName = target(manyToOne, attribute.type());
        Optional<Shape> target = targetName == null ? Optional.empty() : shape(targetName);
        if (target.isEmpty() || target.get().key().isEmpty()) {
            notDrawn.add("`" + attribute.member() + "` (`@ManyToOne` to a class that is not an entity here)");
            return List.of();
        }
        boolean required = !bool(manyToOne, "optional", true) || attribute.has(NOT_NULL);
        List<Col> columns = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean optional = false;
        List<KeyColumn> key = target.get().key();
        List<AnnotationInstance> joins = joinColumns(attribute);
        for (int i = 0; i < key.size(); i++) {
            AnnotationInstance join = joinFor(joins, key, i);
            KeyColumn referenced = key.get(i);
            String name = join != null && !string(join, "name").isBlank() ? physical(string(join, "name"))
                    : physical(attribute.name() + "_" + referenced.logical());
            Col col = new Col(name, referenced.type());
            col.length = referenced.length();
            col.foreignKey = true;
            col.unique = join != null && bool(join, "unique", false);
            col.nullable = !(required || (join != null && !bool(join, "nullable", true)));
            Col merged = table.add(col);
            columns.add(merged);
            names.add(merged.name);
            optional |= merged.nullable;
        }
        relations.add(new RelationModel(table.name, target.get().table(), String.join(", ", names), optional));
        return columns;
    }

    /** The {@code @JoinColumn} for the i-th key column: by {@code referencedColumnName}, else by position. */
    private AnnotationInstance joinFor(List<AnnotationInstance> joins, List<KeyColumn> key, int i) {
        for (AnnotationInstance join : joins) {
            String referenced = string(join, "referencedColumnName");
            if (!referenced.isBlank() && (physical(referenced).equals(key.get(i).physical())
                    || referenced.equals(key.get(i).logical()))) {
                return join;
            }
        }
        if (i < joins.size() && string(joins.get(i), "referencedColumnName").isBlank()) {
            return joins.get(i);
        }
        return null;
    }

    private static List<AnnotationInstance> joinColumns(Attribute attribute) {
        List<AnnotationInstance> joins = new ArrayList<>();
        AnnotationInstance one = attribute.annotation(JOIN_COLUMN);
        if (one != null) {
            joins.add(one);
        }
        AnnotationInstance many = attribute.annotation(JOIN_COLUMNS);
        if (many != null && many.value() != null) {
            joins.addAll(List.of(many.value().asNestedArray()));
        }
        return joins;
    }

    private void oneToMany(Attribute attribute, Shape shape, Set<String> notDrawn, List<RelationModel> relations,
                           Map<String, TableBuilder> tables, List<Runnable> afterTables) {
        AnnotationInstance oneToMany = attribute.annotation(ONE_TO_MANY);
        if (!string(oneToMany, "mappedBy").isBlank()) {
            // The owning side, a @ManyToOne on the other entity, draws it.
            return;
        }
        List<AnnotationInstance> joins = joinColumns(attribute);
        if (joins.isEmpty()) {
            notDrawn.add("`" + attribute.member() + "` (`@OneToMany` with a join table)");
            return;
        }
        DotName targetName = target(oneToMany, elementType(attribute.type(), 0));
        Optional<Shape> target = targetName == null ? Optional.empty() : shape(targetName);
        if (target.isEmpty() || shape.key().isEmpty()) {
            notDrawn.add("`" + attribute.member() + "` (`@OneToMany` to a class that is not an entity here)");
            return;
        }
        List<KeyColumn> key = shape.key();
        List<Col> columns = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean optional = false;
        for (int i = 0; i < key.size(); i++) {
            AnnotationInstance join = joinFor(joins, key, i);
            KeyColumn referenced = key.get(i);
            String name = join != null && !string(join, "name").isBlank() ? physical(string(join, "name"))
                    : physical(attribute.name() + "_" + referenced.logical());
            Col col = new Col(name, referenced.type());
            col.length = referenced.length();
            col.foreignKey = true;
            col.nullable = join == null || bool(join, "nullable", true);
            columns.add(col);
            names.add(name);
            optional |= col.nullable;
        }
        String targetTable = target.get().table();
        relations.add(new RelationModel(targetTable, shape.table(), String.join(", ", names), optional));
        afterTables.add(() -> {
            TableBuilder many = tables.computeIfAbsent(targetTable, name -> new TableBuilder(name, null, null));
            columns.forEach(many::add);
        });
    }

    private void elementCollection(Attribute attribute, Shape owner, Set<String> notDrawn, List<RelationModel> relations,
                                   Map<String, TableBuilder> tables) {
        Type type = attribute.type();
        String collection = type.name().toString();
        boolean map = isMap(type);
        Type element = elementType(type, map ? 1 : 0);
        if (element == null || owner.key().isEmpty()) {
            notDrawn.add("`" + attribute.member() + "` (`@ElementCollection` of a type this cannot read)");
            return;
        }
        AnnotationInstance collectionTable = attribute.annotation(COLLECTION_TABLE);
        String tableName = collectionTable != null && !string(collectionTable, "name").isBlank()
                ? physical(string(collectionTable, "name"))
                : physical(owner.entityName() + "_" + attribute.name());
        TableBuilder table = new TableBuilder(tableName, owner.entity().name() + "." + attribute.name(),
                inputs.origin(owner.entity().name()));

        // The key: one column per key column of the owner.
        List<AnnotationInstance> joins = new ArrayList<>();
        if (collectionTable != null && collectionTable.value("joinColumns") != null) {
            joins.addAll(List.of(collectionTable.value("joinColumns").asNestedArray()));
        }
        List<String> keyNames = new ArrayList<>();
        for (int i = 0; i < owner.key().size(); i++) {
            KeyColumn referenced = owner.key().get(i);
            AnnotationInstance join = joinFor(joins, owner.key(), i);
            String name = join != null && !string(join, "name").isBlank() ? physical(string(join, "name"))
                    : physical(owner.entityName() + "_" + referenced.logical());
            Col col = new Col(name, referenced.type());
            col.length = referenced.length();
            col.foreignKey = true;
            col.nullable = false;
            table.add(col);
            keyNames.add(name);
        }

        // The element: a basic column, or an embeddable's columns.
        List<Col> elementColumns = new ArrayList<>();
        Optional<ClassInfo> embeddable = element.kind() == Type.Kind.CLASS || element.kind() == Type.Kind.PARAMETERIZED_TYPE
                ? inputs.classInfo(element.name()).filter(info -> info.declaredAnnotation(EMBEDDABLE) != null)
                : Optional.empty();
        if (embeddable.isPresent()) {
            for (Col col : embedded(attribute, embeddable.get(), "", overrides(attribute, ""), owner.fieldAccess(), notDrawn, 0)) {
                elementColumns.add(table.add(col));
            }
        } else if (isEntity(element.name())) {
            notDrawn.add("`" + attribute.member() + "` (`@ElementCollection` of an entity)");
            return;
        } else {
            Attribute value = new Attribute(attribute.name(), element, attribute.declaring(), attribute.annotations(),
                    element.kind() == Type.Kind.PRIMITIVE);
            elementColumns.add(table.add(basic(value, attribute.annotation(COLUMN), physical(attribute.name()), false,
                    false)));
        }

        // The index: a map's key column, or a list's order column.
        List<String> pk = new ArrayList<>(keyNames);
        if (map) {
            Type keyType = elementType(type, 0);
            AnnotationInstance mapKey = attribute.annotation(MAP_KEY_COLUMN);
            String name = mapKey != null && !string(mapKey, "name").isBlank() ? physical(string(mapKey, "name"))
                    : physical(attribute.name() + "_KEY");
            Attribute keyAttribute = new Attribute(attribute.name(), keyType, attribute.declaring(), attribute.annotations(),
                    false);
            Col col = new Col(name, keyType == null ? "string" : logicalType(keyAttribute, keyType, mapKey, true));
            if (mapKey != null && mapKey.value("length") != null && SIZED.contains(col.type)) {
                col.length = mapKey.value("length").asInt();
            }
            col.nullable = false;
            table.add(col);
            pk.add(name);
        } else if (attribute.has(ORDER_COLUMN)) {
            AnnotationInstance order = attribute.annotation(ORDER_COLUMN);
            String name = !string(order, "name").isBlank() ? physical(string(order, "name"))
                    : physical(attribute.name() + "_ORDER");
            Col col = new Col(name, "int");
            col.nullable = !bool(order, "nullable", true) ? false : col.nullable;
            table.add(col);
            pk.add(name);
        } else if (collection.equals("java.util.Set") || collection.equals("java.util.SortedSet")
                || collection.equals("java.util.NavigableSet")) {
            // A set's rows are told apart by the element itself: Hibernate keys it with the element's
            // columns, as long as none of them may be null.
            boolean allRequired = elementColumns.stream().noneMatch(col -> col.nullable);
            if (allRequired) {
                elementColumns.forEach(col -> pk.add(col.name));
            } else {
                pk.clear();
            }
        } else {
            // A bag: rows may repeat, so there is no key.
            pk.clear();
        }
        for (String column : pk) {
            table.key(column);
        }
        tables.put(tableName, table);
        relations.add(new RelationModel(tableName, owner.table(), String.join(", ", keyNames), false));
    }

    // ---------------------------------------------------------------- entities, their attributes and keys

    private boolean isEntity(DotName name) {
        return inputs.classInfo(name).map(info -> info.declaredAnnotation(ENTITY) != null).orElse(false);
    }

    /** The entity's shape, computed once; empty when the class is not an entity the inputs hold. */
    private Optional<Shape> shape(DotName name) {
        Optional<Shape> known = shapes.get(name);
        if (known != null) {
            return known;
        }
        shapes.put(name, Optional.empty());
        Optional<Shape> computed = inputs.classInfo(name).filter(info -> info.declaredAnnotation(ENTITY) != null)
                .map(this::computeShape);
        shapes.put(name, computed);
        return computed;
    }

    private Shape computeShape(ClassInfo entity) {
        List<String> notDrawn = new ArrayList<>();
        // The chain up to the first class that is not mapped: @MappedSuperclass members are inherited,
        // and an @Entity above this one makes it a subclass of an inheritance tree.
        List<ClassInfo> chain = chain(entity);
        boolean subclass = chain.stream().skip(1).anyMatch(info -> info.declaredAnnotation(ENTITY) != null);
        String simple = simpleName(entity.name());
        if (subclass) {
            notDrawn.add("`" + simple + "` (`@Inheritance`)");
        }
        if (entity.declaredAnnotation(INHERITANCE) != null) {
            notDrawn.add("`" + simple + "` (`@Inheritance`)");
        }
        if (entity.declaredAnnotation(SECONDARY_TABLE) != null || entity.declaredAnnotation(SECONDARY_TABLES) != null) {
            notDrawn.add("`" + simple + "` (`@SecondaryTable`)");
        }
        String entityName = string(entity.declaredAnnotation(ENTITY), "name");
        if (entityName.isBlank()) {
            entityName = simple;
        }
        AnnotationInstance table = entity.declaredAnnotation(TABLE);
        String tableName = table != null && !string(table, "name").isBlank() ? physical(string(table, "name"))
                : physical(entityName);
        boolean fieldAccess = fieldAccess(chain, entity);
        List<Attribute> attributes = attributes(entity, fieldAccess, Set.of(ENTITY, MAPPED_SUPERCLASS));
        List<Attribute> ids = attributes.stream().filter(attribute -> attribute.has(ID)).toList();
        List<KeyColumn> key = new ArrayList<>();
        for (Attribute id : sortedIds(ids)) {
            if (id.has(MANY_TO_ONE) || unsupported(id) != null) {
                continue;
            }
            AnnotationInstance column = id.annotation(COLUMN);
            String logical = column != null && !string(column, "name").isBlank() ? string(column, "name") : id.name();
            Integer length = column != null && column.value("length") != null ? column.value("length").asInt() : null;
            String type = logicalType(id, id.type(), column, false);
            key.add(new KeyColumn(logical, physical(logical), type, SIZED.contains(type) ? length : null));
        }
        return new Shape(entity, tableName, entityName, fieldAccess, attributes, ids, key, notDrawn, subclass);
    }

    /** The class and its superclasses, nearest first, as far as the inputs know them. */
    private List<ClassInfo> chain(ClassInfo start) {
        List<ClassInfo> chain = new ArrayList<>();
        Set<DotName> seen = new HashSet<>();
        ClassInfo current = start;
        while (current != null && seen.add(current.name()) && chain.size() < 32) {
            chain.add(current);
            DotName superName = current.superName();
            if (superName == null || superName.equals(OBJECT)) {
                break;
            }
            current = inputs.classInfo(superName).orElse(null);
        }
        return chain;
    }

    /** Field access unless {@code @Id} sits on a getter, or the class says {@code @Access(PROPERTY)}. */
    private boolean fieldAccess(List<ClassInfo> chain, ClassInfo entity) {
        AnnotationInstance access = entity.declaredAnnotation(ACCESS);
        if (access != null && access.value() != null) {
            return !access.value().asEnum().equals("PROPERTY");
        }
        for (ClassInfo info : chain) {
            for (FieldInfo field : info.fields()) {
                if (field.declaredAnnotation(ID) != null || field.declaredAnnotation(EMBEDDED_ID) != null) {
                    return true;
                }
            }
            for (MethodInfo method : info.methods()) {
                if (method.declaredAnnotation(ID) != null || method.declaredAnnotation(EMBEDDED_ID) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The persistent attributes of a class and of the mapped classes above it (those carrying one of
     * {@code mapped}), the topmost first: the fields that are neither static nor transient, or the
     * getters, and never a {@code @Transient} one.
     */
    private List<Attribute> attributes(ClassInfo start, boolean fieldAccess, Set<DotName> mapped) {
        List<ClassInfo> chain = chain(start);
        List<ClassInfo> mappedChain = new ArrayList<>();
        mappedChain.add(start);
        for (ClassInfo info : chain.subList(1, chain.size())) {
            if (mapped.stream().anyMatch(name -> info.declaredAnnotation(name) != null)) {
                mappedChain.add(info);
            }
        }
        List<Attribute> attributes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = mappedChain.size() - 1; i >= 0; i--) {
            ClassInfo info = mappedChain.get(i);
            if (fieldAccess) {
                for (FieldInfo field : info.fieldsInDeclarationOrder()) {
                    int flags = field.flags();
                    if (Modifier.isStatic(flags) || Modifier.isTransient(flags) || field.isSynthetic()
                            || field.declaredAnnotation(TRANSIENT) != null || !names.add(field.name())) {
                        continue;
                    }
                    attributes.add(new Attribute(field.name(), field.type(), info, field::declaredAnnotation,
                            field.type().kind() == Type.Kind.PRIMITIVE));
                }
            } else {
                for (MethodInfo method : info.methodsInDeclarationOrder()) {
                    String property = property(method);
                    if (property == null || method.declaredAnnotation(TRANSIENT) != null || !names.add(property)) {
                        continue;
                    }
                    attributes.add(new Attribute(property, method.returnType(), info, method::declaredAnnotation,
                            method.returnType().kind() == Type.Kind.PRIMITIVE));
                }
            }
        }
        return attributes;
    }

    /** {@code getName()} or {@code isActive()} gives the property's name; anything else null. */
    private static String property(MethodInfo method) {
        int flags = method.flags();
        if (Modifier.isStatic(flags) || method.isSynthetic() || method.isBridge() || method.parametersCount() != 0
                || method.returnType().kind() == Type.Kind.VOID) {
            return null;
        }
        String name = method.name();
        String bare;
        if (name.startsWith("get") && name.length() > 3) {
            bare = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2
                && method.returnType().kind() == Type.Kind.PRIMITIVE
                && method.returnType().asPrimitiveType().primitive() == PrimitiveType.Primitive.BOOLEAN) {
            bare = name.substring(2);
        } else {
            return null;
        }
        if (bare.length() > 1 && Character.isUpperCase(bare.charAt(0)) && Character.isUpperCase(bare.charAt(1))) {
            return bare;
        }
        return Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }

    // ---------------------------------------------------------------- small helpers

    /** The class a relation points at: its {@code targetEntity}, else the attribute's type. */
    private static DotName target(AnnotationInstance relation, Type type) {
        AnnotationValue explicit = relation.value("targetEntity");
        if (explicit != null && !explicit.asClass().name().toString().equals("void")) {
            return explicit.asClass().name();
        }
        return type == null ? null : type.name();
    }

    private static boolean isCollection(Type type) {
        String name = type.name().toString();
        return name.equals("java.util.List") || name.equals("java.util.Set") || name.equals("java.util.Collection")
                || name.equals("java.util.SortedSet") || name.equals("java.util.NavigableSet") || isMap(type);
    }

    private static boolean isMap(Type type) {
        String name = type.name().toString();
        return name.equals("java.util.Map") || name.equals("java.util.SortedMap") || name.equals("java.util.NavigableMap");
    }

    /** The i-th type argument of a parameterised type; null when it has none. */
    private static Type elementType(Type type, int index) {
        if (type.kind() != Type.Kind.PARAMETERIZED_TYPE) {
            return null;
        }
        List<Type> arguments = type.asParameterizedType().arguments();
        if (index >= arguments.size()) {
            return null;
        }
        Type argument = arguments.get(index);
        if (argument.kind() == Type.Kind.WILDCARD_TYPE) {
            Type bound = argument.asWildcardType().extendsBound();
            return bound == null ? null : bound;
        }
        return argument;
    }

    /** The physical name of a logical one, by the unit's naming strategy. */
    private String physical(String logical) {
        return snakeCase ? snakeCase(logical) : unquoted(logical);
    }

    /** {@code PhysicalNamingStrategyStandardImpl}: the name as written, without its quotes. */
    static String unquoted(String logical) {
        String text = logical.strip();
        if (text.length() >= 2 && ((text.startsWith("`") && text.endsWith("`"))
                || (text.startsWith("\"") && text.endsWith("\"")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /**
     * {@code CamelCaseToUnderscoresNamingStrategy}: dots become underscores, an underscore goes
     * before an upper-case letter that sits between a lower-case letter or digit and a lower-case
     * letter or digit, and the whole name is lower-cased. A quoted name keeps its text.
     */
    static String snakeCase(String logical) {
        String text = logical.strip();
        if (text.length() >= 2 && ((text.startsWith("`") && text.endsWith("`"))
                || (text.startsWith("\"") && text.endsWith("\"")))) {
            return text.substring(1, text.length() - 1);
        }
        StringBuilder builder = new StringBuilder(text.replace('.', '_'));
        for (int i = 1; i < builder.length() - 1; i++) {
            char before = builder.charAt(i - 1);
            char current = builder.charAt(i);
            char after = builder.charAt(i + 1);
            if ((Character.isLowerCase(before) || Character.isDigit(before)) && Character.isUpperCase(current)
                    && (Character.isLowerCase(after) || Character.isDigit(after))) {
                builder.insert(i++, '_');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    static String simpleName(DotName name) {
        String text = name.toString();
        return text.substring(text.lastIndexOf('.') + 1);
    }

    private static String string(AnnotationInstance annotation, String name) {
        if (annotation == null) {
            return "";
        }
        AnnotationValue value = annotation.value(name);
        return value == null ? "" : value.asString();
    }

    private static boolean bool(AnnotationInstance annotation, String name, boolean otherwise) {
        if (annotation == null) {
            return otherwise;
        }
        AnnotationValue value = annotation.value(name);
        return value == null ? otherwise : value.asBoolean();
    }

    private static DotName jpa(String simple) {
        return DotName.createSimple(JPA + simple);
    }
}
