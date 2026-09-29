#!/bin/sh
# Run from the project root on a Linux Docker host.
set -eu
root=$(pwd)
docker build --target build -t lct-heat-tests:0.9.1 backend
docker run --rm --add-host host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$root/test-data:/test-data:ro" \
  --entrypoint mvn lct-heat-tests:0.9.1 -B -Dapi.version=1.44 verify
