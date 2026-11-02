#!/bin/sh
# Maven lives in the tools image, so this launcher replaces the usual wrapper download.
cd "$(dirname "$0")" && exec docker compose run --rm tools mvn "$@"
