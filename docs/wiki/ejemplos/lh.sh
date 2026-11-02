#!/bin/sh
# Ejecuta SQL en DuckDB con el catálogo Iceberg ya abierto como `lh`. Lo que hace infra/smoke.sh, en reutilizable.
# Uso: docker compose run --rm -T tools sh docs/wiki/ejemplos/lh.sh "SHOW ALL TABLES"
set -eu
duckdb -cmd ".output /dev/null" -cmd "
INSTALL iceberg; LOAD iceberg; INSTALL httpfs; LOAD httpfs;
CREATE SECRET s3 (TYPE s3, KEY_ID '$S3_ACCESS_KEY', SECRET '$S3_SECRET_KEY', REGION '$S3_REGION', ENDPOINT 'seaweedfs:8333', URL_STYLE 'path', USE_SSL false);
CREATE SECRET pol (TYPE iceberg, CLIENT_ID '$POLARIS_CLIENT_ID', CLIENT_SECRET '$POLARIS_CLIENT_SECRET', OAUTH2_SERVER_URI 'http://polaris:8181/api/catalog/v1/oauth/tokens', OAUTH2_SCOPE 'PRINCIPAL_ROLE:ALL');
ATTACH 'lakehouse' AS lh (TYPE iceberg, URI 'http://polaris:8181/api/catalog', SECRET pol, ACCESS_DELEGATION_MODE 'none');
" -cmd ".output stdout" -c "$1"
