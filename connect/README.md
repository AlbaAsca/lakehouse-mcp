Iceberg Kafka Connect sink configuration (`iceberg-sink.json`) and the one-shot `connect-init` image that registers it (`register.sh`). The worker image is built in `infra/connect`.

- `${VAR}` values are replaced from the environment when registering; an unset variable stops the registration.
- `event_time` goes through `TimestampConverter`: the Avro converter hands `timestamp-micros` over as a plain long, which would create a long column that `day()` cannot partition.
- `register.sh` keeps retrying while Connect starts up (no answer, 404, 409 or 5xx) and stops at once on any other error.
- The dead letter topic only takes records that cannot be read or transformed. Errors while writing still stop the task.
