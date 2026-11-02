#!/bin/sh
# Idempotent: creates the bucket, the Polaris catalog and the namespace the sink writes to; grants root the content privileges.
set -eu
export AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" AWS_DEFAULT_REGION="$S3_REGION"
S3="aws --endpoint-url http://seaweedfs:8333 s3api"
$S3 head-bucket --bucket "$S3_BUCKET" 2>/dev/null || $S3 create-bucket --bucket "$S3_BUCKET" >/dev/null

P=http://polaris:8181
M=/api/management/v1
TOKEN=$(curl -fsS "$P/api/catalog/v1/oauth/tokens" -H "Polaris-Realm: $POLARIS_REALM" \
  -d grant_type=client_credentials -d "client_id=$POLARIS_CLIENT_ID" -d "client_secret=$POLARIS_CLIENT_SECRET" \
  -d scope=PRINCIPAL_ROLE:ALL | jq -r .access_token)
api() { curl -sS -o /tmp/out -w '%{http_code}' -X "$1" "$P$2" -H "Authorization: Bearer $TOKEN" \
  -H "Polaris-Realm: $POLARIS_REALM" -H 'Content-Type: application/json' -d "$3"; }
check() { case "$2" in 2*|409) echo "$1: $2" ;; *) echo "$1: $2"; cat /tmp/out; exit 1 ;; esac; }

BODY=$(jq -n --arg b "$S3_BUCKET" --arg r "$S3_REGION" '{catalog:{name:"lakehouse",type:"INTERNAL",
  properties:{"default-base-location":("s3://"+$b+"/warehouse")},
  storageConfigInfo:{storageType:"S3",allowedLocations:["s3://"+$b+"/"],region:$r,endpoint:"http://seaweedfs:8333",
    pathStyleAccess:true,stsUnavailable:true}}}')
check catalog "$(api POST $M/catalogs "$BODY")"
check grant "$(api PUT $M/catalogs/lakehouse/catalog-roles/catalog_admin/grants '{"grant":{"type":"catalog","privilege":"CATALOG_MANAGE_CONTENT"}}')"
check role "$(api PUT $M/principal-roles/service_admin/catalog-roles/lakehouse '{"catalogRole":{"name":"catalog_admin"}}')"
check namespace "$(api POST /api/catalog/v1/lakehouse/namespaces '{"namespace":["ecommerce"]}')"
