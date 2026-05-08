package br.com.etlgis.model;

public record EtlRequest(
        String shapefileUrl,
        String jdbcUrl,
        String dbUser,
        String dbPassword,
        String schema,
        String table
) {
    public static EtlRequest fromArgs(String[] args) {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Uso: java -jar etl-gis.jar <url_shapefile_zip> <jdbc_url> <db_user> <db_password> <schema.tabela>"
            );
        }

        String[] parts = args[4].split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("schema.tabela inválido. Exemplo: public.mapa");
        }

        return new EtlRequest(args[0], args[1], args[2], args[3], parts[0], parts[1]);
    }
}
