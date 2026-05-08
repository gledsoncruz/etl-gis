package br.com.etlgis.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipService {
    private final Logger logger;

    public ZipService(Logger logger) {
        this.logger = logger;
    }

    public Path unzipAndFindShp(Path zipPath, Path tempDir) throws IOException {
        logger.info("Extraindo zip.");
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

        logger.info(() -> "Shapefile identificado: " + shpPath);
        return shpPath;
    }
}
