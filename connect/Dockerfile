FROM alpine:3.22
RUN apk add --no-cache curl jq
COPY iceberg-sink.json /iceberg-sink.json
COPY register.sh /register.sh
ENTRYPOINT ["/bin/sh", "/register.sh"]
