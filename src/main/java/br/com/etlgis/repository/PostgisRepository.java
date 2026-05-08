package br.com.etlgis.repository;

import br.com.etlgis.model.LoadResult;
import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.FileDataStoreFinder;
import org.geotools.api.data.simple.SimpleFeatureCollection;
import org.geotools.api.data.simple.SimpleFeatureIterator;
import org.geotools.api.data.simple.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.io.WKBWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static br.com.etlgis.util.JsonUtils.attributesToJson;

public class PostgisRepository {
    private static final int BATCH_SIZE = 1_000;
    private final Logger logger;

    public PostgisRepository(Logger logger) {
        this.logger = logger;
    }

    public LoadResult loadShapefile(Path shpPath, String jdbcUrl, String user, String password, String schema, String table)
            throws IOException, SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             FileDataStore dataStore = FileDataStoreFinder.getDataStore(shpPath.toFile())) {
            conn.setAutoCommit(false);
            createTableIfNeeded(conn, schema, table);

            SimpleFeatureSource featureSource = dataStore.getFeatureSource();
            SimpleFeatureCollection collection = featureSource.getFeatures();
            String insertSql = "INSERT INTO %s.%s (source_id, attrs, geom) VALUES (?, ?::jsonb, ST_GeomFromWKB(?, ?))".formatted(schema, table);

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

                    stmt.setString(1, feature.getID());
                    stmt.setString(2, attributesToJson(feature));
                    stmt.setBytes(3, wkbWriter.write(geometry));
                    stmt.setInt(4, geometry.getSRID() > 0 ? geometry.getSRID() : 4326);
                    stmt.addBatch();

                    total++;
                    if (total % BATCH_SIZE == 0) {
                        stmt.executeBatch();
                        conn.commit();
                        logger.info("Inseridos " + total + " registros...");
                    }
                }

                stmt.executeBatch();
                conn.commit();
            }

            return new LoadResult(total, skipped);
        }
    }

    private void createTableIfNeeded(Connection conn, String schema, String table) throws SQLException {
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
}
