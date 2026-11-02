#!/bin/sh
# Phase 0 check: every service answers, the connector plugin is loaded and DuckDB round-trips an Iceberg table via Polaris.
set -eu
curl -fsS http://schema-registry:8081/subjects >/dev/null && echo "schema-registry ok"
curl -fsS http://connect:8083/connector-plugins | jq -e 'map(.class)|index("org.apache.iceberg.connect.IcebergSinkConnector")' >/dev/null && echo "iceberg sink plugin ok"
duckdb -c "
INSTALL iceberg; LOAD iceberg; INSTALL httpfs; LOAD httpfs;
CREATE SECRET s3 (TYPE s3, KEY_ID '$S3_ACCESS_KEY', SECRET '$S3_SECRET_KEY', REGION '$S3_REGION', ENDPOINT 'seaweedfs:8333', URL_STYLE 'path', USE_SSL false);
CREATE SECRET pol (TYPE iceberg, CLIENT_ID '$POLARIS_CLIENT_ID', CLIENT_SECRET '$POLARIS_CLIENT_SECRET', OAUTH2_SERVER_URI 'http://polaris:8181/api/catalog/v1/oauth/tokens', OAUTH2_SCOPE 'PRINCIPAL_ROLE:ALL');
ATTACH 'lakehouse' AS lh (TYPE iceberg, URI 'http://polaris:8181/api/catalog', SECRET pol);
CREATE SCHEMA IF NOT EXISTS lh.smoke;
DROP TABLE IF EXISTS lh.smoke.t;
CREATE TABLE lh.smoke.t (id INT, v VARCHAR);
INSERT INTO lh.smoke.t VALUES (1,'ok');
SELECT * FROM lh.smoke.t;
DROP TABLE lh.smoke.t;
"
echo "duckdb -> polaris -> seaweedfs ok"
