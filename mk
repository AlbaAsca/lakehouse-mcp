#!/bin/sh
cd "$(dirname "$0")" && exec docker compose run --rm tools make "$@"
