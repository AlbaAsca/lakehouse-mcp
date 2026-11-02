FROM alpine:3.22
RUN apk add --no-cache curl jq aws-cli
COPY init.sh /init.sh
ENTRYPOINT ["/bin/sh", "/init.sh"]
