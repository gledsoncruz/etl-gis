package br.com.etlgis;

import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.FileDataStoreFinder;
import org.geotools.api.data.simple.SimpleFeatureCollection;
import org.geotools.api.data.simple.SimpleFeatureIterator;
import org.geotools.api.data.simple.SimpleFeatureSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.io.WKBWriter;
import org.geotools.api.feature.simple.SimpleFeature;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ShapefileToPostgisEtl {
    private static final Logger LOGGER = Logger.getLogger(ShapefileToPostgisEtl.class.getName());
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 1_000;

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("Uso: java -jar etl-gis.jar <url_shapefile_zip> <jdbc_url> <db_user> <db_password> <schema.tabela>");
            System.exit(1);
        }

        String url = args[0];
        String jdbcUrl = args[1];
        String dbUser = args[2];
        String dbPassword = args[3];
        String schemaTable = args[4];

        Instant start = Instant.now();
        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("etl-shape-");
            Path zipPath = tempDir.resolve("source.zip");

            downloadWithRetry(url, zipPath);
            Path shpPath = unzipAndFindShp(zipPath, tempDir);
            loadShapefileIntoPostgis(shpPath, jdbcUrl, dbUser, dbPassword, schemaTable);

            Duration elapsed = Duration.between(start, Instant.now());
            LOGGER.info(() -> String.format("ETL finalizado com sucesso em %d segundos.", elapsed.getSeconds()));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Falha no ETL.", e);
            System.exit(2);
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    private static void downloadWithRetry(String url, Path targetFile) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                LOGGER.info(() -> "Download tentativa " + attempt + " de " + MAX_DOWNLOAD_ATTEMPTS);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .GET()
                        .build();

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Resposta HTTP inesperada: " + response.statusCode());
                }

                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(targetFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                }

                LOGGER.info("Download concluído com sucesso.");
                return;
            } catch (Exception e) {
                lastError = e;
                LOGGER.log(Level.WARNING, "Falha no download.", e);
                Thread.sleep(1_000L * attempt);
            }
        }

        throw new IOException("Não foi possível baixar o arquivo após 3 tentativas.", lastError);
    }

    private static Path unzipAndFindShp(Path zipPath, Path tempDir) throws IOException {
        LOGGER.info("Extraindo zip.");
        Path extractDir = tempDir.resolve("extract");
        Files.createDirectories(extractDir);

        Path shpPath = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                Path outputPath = extractDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(extractDir)) {
                    throw new IOException("Entrada zip inválida: " + entry.getName());
                }

                Files.createDirectories(outputPath.getParent());
                try (OutputStream out = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    zis.transferTo(out);
                }

                if (outputPath.toString().toLowerCase(Locale.ROOT).endsWith(".shp")) {
                    shpPath = outputPath;
                }

                zis.closeEntry();
            }
        }

        if (shpPath == null) {
            throw new IOException("Nenhum arquivo .shp encontrado no zip.");
        }

        LOGGER.info(() -> "Shapefile identificado: " + shpPath);
        return shpPath;
    }

    private static void loadShapefileIntoPostgis(Path shpPath, String jdbcUrl, String user, String password, String schemaTable)
            throws IOException, SQLException {
        String[] parts = schemaTable.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("schema.tabela inválido. Exemplo: public.mapa");
        }

        String schema = parts[0];
        String table = parts[1];

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             FileDataStore dataStore = FileDataStoreFinder.getDataStore(shpPath.toFile())) {

            conn.setAutoCommit(false);
            createTableIfNeeded(conn, schema, table);

            SimpleFeatureSource featureSource = dataStore.getFeatureSource();
            SimpleFeatureCollection collection = featureSource.getFeatures();

            String insertSql = String.format(
                    "INSERT INTO %s.%s (source_id, attrs, geom) VALUES (?, ?::jsonb, ST_GeomFromWKB(?, ?))",
                    schema, table
            );

            WKBWriter wkbWriter = new WKBWriter();
            int total = 0;
            int skipped = 0;

            try (PreparedStatement stmt = conn.prepareStatement(insertSql);
                 SimpleFeatureIterator it = collection.features()) {
                while (it.hasNext()) {
                    SimpleFeature feature = it.next();
                    Object geomObj = feature.getDefaultGeometry();
                    if (!(geomObj instanceof Geometry geometry)) {
                        skipped++;
                        continue;
                    }

                    if (!geometry.isValid()) {
                        geometry = GeometryFixer.fix(geometry);
                    }

                    if (geometry == null || geometry.isEmpty() || !geometry.isValid()) {
                        skipped++;
                        continue;
                    }

                    String attrsJson = attributesToJson(feature);
                    byte[] wkb = wkbWriter.write(geometry);
                    int srid = geometry.getSRID() > 0 ? geometry.getSRID() : 4326;

                    stmt.setString(1, feature.getID());
                    stmt.setString(2, attrsJson);
                    stmt.setBytes(3, wkb);
                    stmt.setInt(4, srid);
                    stmt.addBatch();

                    total++;
                    if (total % BATCH_SIZE == 0) {
                        stmt.executeBatch();
                        conn.commit();
                        LOGGER.info(() -> "Inseridos " + total + " registros...");
                    }
                }

                stmt.executeBatch();
                conn.commit();
            }

            LOGGER.info(() -> String.format("Carga finalizada. Inseridos=%d, ignorados=%d", total, skipped));
        }
    }

    private static void createTableIfNeeded(Connection conn, String schema, String table) throws SQLException {
        String ddlSchema = "CREATE SCHEMA IF NOT EXISTS " + schema;
        String ddlTable = """
                CREATE TABLE IF NOT EXISTS %s.%s (
                    id BIGSERIAL PRIMARY KEY,
                    source_id TEXT,
                    attrs JSONB,
                    geom geometry
                )
                """.formatted(schema, table);
        String idxGeom = "CREATE INDEX IF NOT EXISTS " + table + "_geom_idx ON " + schema + "." + table + " USING GIST (geom)";

        try (Statement st = conn.createStatement()) {
            st.execute(ddlSchema);
            st.execute(ddlTable);
            st.execute(idxGeom);
            conn.commit();
        }
    }

    private static String attributesToJson(SimpleFeature feature) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var prop : feature.getProperties()) {
            String name = prop.getName().toString();
            if ("the_geom".equalsIgnoreCase(name) || "geom".equalsIgnoreCase(name)) {
                continue;
            }
            Object value = prop.getValue();
            if (!first) {
                json.append(',');
            }
            json.append('"').append(escapeJson(name)).append('"').append(':');
            if (value == null) {
                json.append("null");
            } else {
                json.append('"').append(escapeJson(String.valueOf(value))).append('"');
            }
            first = false;
        }
        json.append('}');
        return json.toString();
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Falha ao deletar arquivo temporário: " + path, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Falha ao limpar diretório temporário.", e);
        }
    }
}
