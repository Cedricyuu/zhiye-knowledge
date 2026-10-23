import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Offline, one-way import into the empty project MySQL DB. The H2 source is opened read-only. */
class MigrateH2ToMysql {
    private static final String URL = "jdbc:mysql://127.0.0.1:13307/ai_knowledge?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("kb_user", "id"); KEYS.put("kb_session", "token_hash");
        KEYS.put("kb_document", "id"); KEYS.put("kb_chunk", "id");
        KEYS.put("kb_answer", "id"); KEYS.put("kb_embedding", "chunk_id");
        KEYS.put("kb_exercise", "id");
    }
    record Snapshot(long rows, String sha256) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Pass the knowledge project root."); }
        Path root = Path.of(args[0]).toRealPath();
        Path sourceFile = root.resolve("data/knowledge.mv.db");
        String password = Objects.requireNonNull(System.getenv("KB_MIGRATION_DB_PASSWORD"), "Project DB password is missing");
        try (Connection target = DriverManager.getConnection(URL, "knowledge", password)) {
            try (Statement ddl = target.createStatement()) {
                for (String sql : Files.readString(root.resolve("backend/src/main/resources/schema.sql")).split(";")) {
                    if (!sql.isBlank()) { ddl.execute(sql); }
                }
            }
            for (String table : KEYS.keySet()) {
                try (var s = target.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    r.next();
                    if (r.getLong(1) != 0) { throw new IllegalStateException("Target MySQL is not empty. Import refused; existing rows were not changed."); }
                }
            }
            if (!Files.exists(sourceFile)) {
                System.out.println("No H2 file exists; the empty project MySQL schema is ready.");
                return;
            }
            String sourceUrl = "jdbc:h2:file:" + root.resolve("data/knowledge").toString().replace('\\', '/')
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
            Map<String, Snapshot> snapshots = new LinkedHashMap<>();
            try (Connection source = DriverManager.getConnection(sourceUrl, "sa", "")) {
                target.setAutoCommit(false);
                try {
                    for (var table : KEYS.entrySet()) {
                        if (table.getKey().equals("kb_exercise") && !tableExists(source, table.getKey())) { continue; }
                        snapshots.put(table.getKey(), copy(source, target, table.getKey(), table.getValue()));
                    }
                    for (var table : KEYS.entrySet()) {
                        if (!snapshots.containsKey(table.getKey())) { continue; }
                        Snapshot actual = snapshot(target, table.getKey(), table.getValue());
                        if (!actual.equals(snapshots.get(table.getKey()))) {
                            throw new IllegalStateException("Row/hash verification failed for " + table.getKey() + "; import rolled back.");
                        }
                    }
                    target.commit();
                } catch (Exception failure) { target.rollback(); throw failure; }
            }
            StringJoiner report = new StringJoiner(",\n", "{\n", "\n}\n");
            snapshots.forEach((table, s) -> report.add("  \"" + table + "\": {\"rows\":" + s.rows + ",\"sha256\":\"" + s.sha256 + "\"}"));
            Files.writeString(root.resolve(".runtime/mysql-migration.json"), report.toString());
            snapshots.forEach((table, s) -> System.out.println("Verified " + table + ": " + s.rows + " rows"));
        }
    }

    private static boolean tableExists(Connection db, String table) throws SQLException {
        try (var found = db.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) { return found.next(); }
    }

    private static Snapshot copy(Connection source, Connection target, String table, String key) throws Exception {
        try (var select = source.createStatement(); var rows = select.executeQuery("SELECT * FROM " + table + " ORDER BY " + key)) {
            var meta = rows.getMetaData();
            var columns = new StringJoiner(","); var placeholders = new StringJoiner(",");
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String column = meta.getColumnName(i);
                if (!column.matches("[a-z_][a-z0-9_]*")) { throw new IllegalStateException("Unexpected source column: " + table + "." + column); }
                columns.add("`" + column + "`"); placeholders.add("?");
            }
            var hash = MessageDigest.getInstance("SHA-256"); long count = 0;
            try (var insert = target.prepareStatement("INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")")) {
                while (rows.next()) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object value = value(rows, meta, i);
                        insert.setObject(i, value); update(hash, value);
                    }
                    insert.addBatch();
                    if (++count % 200 == 0) { insert.executeBatch(); }
                }
                insert.executeBatch();
            }
            return new Snapshot(count, HexFormat.of().formatHex(hash.digest()));
        }
    }
    private static Snapshot snapshot(Connection db, String table, String key) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256"); long count = 0;
        try (var s = db.createStatement(); var rows = s.executeQuery("SELECT * FROM " + table + " ORDER BY " + key)) {
            var meta = rows.getMetaData();
            while (rows.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) { update(hash, value(rows, meta, i)); }
                count++;
            }
        }
        return new Snapshot(count, HexFormat.of().formatHex(hash.digest()));
    }
    private static Object value(ResultSet row, ResultSetMetaData meta, int column) throws SQLException {
        return switch (meta.getColumnType(column)) {
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> row.getObject(column, LocalDateTime.class);
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> row.getString(column);
            default -> row.getObject(column);
        };
    }
    private static void update(MessageDigest hash, Object value) {
        if (value == null) { hash.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
    }
}
