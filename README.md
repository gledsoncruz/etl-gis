# ETL de Shapefile para PostGIS (Java puro)

Este projeto implementa um ETL em **Java puro** (sem Spring) com foco em:

- Download de shapefile (zip) por URL
- Retry de download (3 tentativas)
- Validação/correção de geometria
- Carga em tabela PostGIS em lote (batch)
- Limpeza de arquivos temporários ao final
- Logs de progresso
- Controle de memória via processamento stream + batches

## Requisitos

- Java 17+
- Maven 3.9+
- PostgreSQL com extensão PostGIS habilitada

## Build

```bash
mvn clean package
```

Jar gerado (fat-jar):

- `target/etl-gis-1.0.0.jar`

## Execução

```bash
java -jar target/etl-gis-1.0.0.jar \
  "https://host/arquivo.zip" \
  "jdbc:postgresql://localhost:5432/gis" \
  "postgres" \
  "postgres" \
  "public.minha_tabela"
```

## Comportamento da carga

- Cria schema/tabela automaticamente se não existir.
- Cria índice GIST para `geom`.
- Salva:
  - `source_id`: id da feature no shapefile
  - `attrs`: atributos em JSONB
  - `geom`: geometria via `ST_GeomFromWKB`

## Observações de performance/memória

- Download e extração com stream (sem carregar tudo em memória).
- Leitura de features com iterator (processamento contínuo).
- Inserção em lotes (`BATCH_SIZE=1000`) com commit incremental.
- Geometrias inválidas são corrigidas com `GeometryFixer`; se continuarem inválidas/vazias, são ignoradas.

## Logs

Logs são emitidos com `java.util.logging`, incluindo:

- Tentativas de download
- Progresso de inserção por lote
- Resumo final (inseridos/ignorados)
