package br.com.etlgis.service;

import br.com.etlgis.model.EtlRequest;
import br.com.etlgis.model.LoadResult;
import br.com.etlgis.repository.PostgisRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

import static br.com.etlgis.util.FileUtils.deleteRecursively;

public class EtlService {
    private final Logger logger;
    private final DownloadService downloadService;
    private final ZipService zipService;
    private final PostgisRepository postgisRepository;

    public EtlService(Logger logger) {
        this.logger = logger;
        this.downloadService = new DownloadService(logger);
        this.zipService = new ZipService(logger);
        this.postgisRepository = new PostgisRepository(logger);
    }

    public void execute(EtlRequest request) throws Exception {
        Instant start = Instant.now();
        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("etl-shape-");
            Path zipPath = tempDir.resolve("source.zip");

            downloadService.downloadWithRetry(request.shapefileUrl(), zipPath);
            Path shpPath = zipService.unzipAndFindShp(zipPath, tempDir);
            LoadResult result = postgisRepository.loadShapefile(
                    shpPath,
                    request.jdbcUrl(),
                    request.dbUser(),
                    request.dbPassword(),
                    request.schema(),
                    request.table()
            );

            Duration elapsed = Duration.between(start, Instant.now());
            logger.info(() -> String.format(
                    "ETL finalizado com sucesso em %d segundos. Inseridos=%d, ignorados=%d",
                    elapsed.getSeconds(),
                    result.inserted(),
                    result.skipped())
            );
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir, logger);
            }
        }
    }
}
