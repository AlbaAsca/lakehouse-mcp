#!/bin/sh
# The S3 identity comes from the environment so no credentials live in the image.
set -eu
cat > /tmp/s3.json <<EOF
{"identities":[{"name":"lakehouse","credentials":[{"accessKey":"$S3_ACCESS_KEY","secretKey":"$S3_SECRET_KEY"}],"actions":["Admin","Read","List","Tagging","Write"]}]}
EOF
exec weed server -dir=/data -s3 -s3.port=8333 -s3.config=/tmp/s3.json -volume.max=20
