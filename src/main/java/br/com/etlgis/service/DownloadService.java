package br.com.etlgis.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DownloadService {
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private final Logger logger;

    public DownloadService(Logger logger) {
        this.logger = logger;
    }

    public void downloadWithRetry(String url, Path targetFile) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                logger.info("Download tentativa " + attempt + " de " + MAX_DOWNLOAD_ATTEMPTS);
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

                logger.info("Download concluído com sucesso.");
                return;
            } catch (Exception e) {
                lastError = e;
                logger.log(Level.WARNING, "Falha no download.", e);
                Thread.sleep(1_000L * attempt);
            }
        }

        throw new IOException("Não foi possível baixar o arquivo após 3 tentativas.", lastError);
    }
}
