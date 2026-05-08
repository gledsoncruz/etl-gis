package br.com.etlgis.app;

import br.com.etlgis.model.EtlRequest;
import br.com.etlgis.service.EtlService;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Application {
    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());

    public static void main(String[] args) {
        try {
            EtlRequest request = EtlRequest.fromArgs(args);
            new EtlService(LOGGER).execute(request);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Falha no ETL.", e);
            System.exit(2);
        }
    }
}
