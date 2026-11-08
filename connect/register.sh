#!/bin/sh
# Idempotent: PUT creates the connector or updates its config. ${VAR} values come from the environment.
set -eu
C=http://connect:8083
NAME=$(jq -r .name /iceberg-sink.json)
jq 'map_values(gsub("\\$\\{(?<v>[A-Z0-9_]+)\\}"; $ENV[.v] // error("not set: \(.v)")))' /iceberg-sink.json > /tmp/config.json

# The port opens before the REST API is ready and the worker has joined its group: until then Connect refuses or
# answers 404/409/5xx. This PUT never gives a legitimate 404, so waiting cannot hide a wrong name.
i=0
while [ "$i" -lt 60 ]; do
  code=$(curl -sS -o /tmp/out -w '%{http_code}' -X PUT "$C/connectors/$NAME/config" \
    -H 'Content-Type: application/json' -d @/tmp/config.json || true)
  case "$code" in
    200|201) echo "$NAME: $code"; break ;;
    000|404|409|5*) i=$((i + 1)); sleep 2 ;;
    *) echo "$NAME: $code"; cat /tmp/out; exit 1 ;;
  esac
done
case "$code" in 200|201) ;; *) echo "$NAME: Connect not ready, last answer $code"; cat /tmp/out; exit 1 ;; esac

# Connect never restarts a FAILED task by itself (T-141: after the PC slept, the sink lost its group session and its task
# died). Re-running this init, as ./mk up does, revives it; on a healthy connector it restarts nothing. Right after a
# create the worker may still be rebalancing (404/409/5xx): retry a little, then leave it to ./mk smoke to report.
i=0
while [ "$i" -lt 15 ]; do
  code=$(curl -sS -o /tmp/out -w '%{http_code}' -X POST \
    "$C/connectors/$NAME/restart?includeTasks=true&onlyFailed=true" || true)
  case "$code" in
    202|204) echo "$NAME: restart of failed tasks requested ($code)"; exit 0 ;;
    000|404|409|5*) i=$((i + 1)); sleep 2 ;;
    *) break ;;
  esac
done
echo "$NAME: WARNING restart of failed tasks answered $code, check ./mk smoke"; cat /tmp/out
