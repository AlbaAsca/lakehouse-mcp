#!/bin/sh
# Llama a la API de Polaris con un token del usuario raíz, sin imprimir el token.
# Uso: docker compose run --rm -T tools sh docs/wiki/ejemplos/polaris.sh /api/management/v1/catalogs
set -eu
P=http://polaris:8181
TOKEN=$(curl -fsS "$P/api/catalog/v1/oauth/tokens" -H "Polaris-Realm: $POLARIS_REALM" \
  -d grant_type=client_credentials -d "client_id=$POLARIS_CLIENT_ID" -d "client_secret=$POLARIS_CLIENT_SECRET" \
  -d scope=PRINCIPAL_ROLE:ALL | jq -r .access_token)
curl -fsS "$P$1" -H "Authorization: Bearer $TOKEN" -H "Polaris-Realm: $POLARIS_REALM" | jq .
