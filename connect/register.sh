#!/bin/sh
# Idempotent: PUT creates the connector or updates its config. ${VAR} values come from the environment.
set -eu
C=http://connect:8083
NAME=$(jq -r .name /iceberg-sink.json)
jq 'map_values(gsub("\\$\\{(?<v>[A-Z0-9_]+)\\}"; $ENV[.v] // error("not set: \(.v)")))' /iceberg-sink.json > /tmp/config.json

# The port opens before the worker joins its group: until then Connect refuses or answers 409/5xx.
i=0
while [ "$i" -lt 60 ]; do
  code=$(curl -sS -o /tmp/out -w '%{http_code}' -X PUT "$C/connectors/$NAME/config" \
    -H 'Content-Type: application/json' -d @/tmp/config.json || true)
  case "$code" in
    200|201) echo "$NAME: $code"; exit 0 ;;
    000|409|5*) i=$((i + 1)); sleep 2 ;;
    *) echo "$NAME: $code"; cat /tmp/out; exit 1 ;;
  esac
done
echo "$NAME: Connect not ready, last answer $code"; cat /tmp/out; exit 1
