package br.com.etlgis.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FileUtils {
    private FileUtils() {
    }

    public static void deleteRecursively(Path dir, Logger logger) {
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
                            logger.log(Level.WARNING, "Falha ao deletar arquivo temporário: " + path, e);
                        }
                    });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Falha ao limpar diretório temporário.", e);
        }
    }
}
