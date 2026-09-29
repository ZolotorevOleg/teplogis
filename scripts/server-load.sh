#!/bin/sh
set -eu

root=${1:-/opt/lct-heat}
base=${2:-http://127.0.0.1:8089}
container=${3:-lct-heat_backend_1}
cd "$root"

python3 scripts/generate-load-geojson.py load-50000.geojson --count 50000
curl -fsS -H 'Content-Type: application/geo+json' --data-binary @load-50000.geojson \
  "$base/api/v1/imports" > synthetic-import.json
import_id=$(python3 -c 'import json; print(json.load(open("synthetic-import.json"))["id"])')
curl -fsS -X POST "$base/api/v1/imports/$import_id/geometry" > synthetic-prepare.json
python3 scripts/load-test.py --base-url "$base" --import-id "$import_id" \
  --users 50 --requests 500 --map-limit 2000 --output load-result.json

curl -fsS "$base/actuator/health/liveness" > liveness.json
curl -fsS "$base/actuator/health/readiness" > readiness.json
curl -fsS -D headers.txt -o /dev/null -H 'X-Request-ID: server-load-smoke' \
  "$base/api/v1/imports/$import_id"

docker exec "$container" sh -c \
  'printf x > /data/uploads/geometry-load-stale.wkb && touch -d "2 days ago" /data/uploads/geometry-load-stale.wkb'
docker restart -t 35 "$container" >/dev/null
attempt=0
until curl --max-time 3 -fsS "$base/actuator/health/readiness" >/dev/null; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] || exit 1
  sleep 2
done
docker exec "$container" test ! -e /data/uploads/geometry-load-stale.wkb
docker exec "$container" test -f "/data/uploads/$import_id.geojson"
curl -fsS "$base/api/v1/imports/$import_id" > after-restart.json

printf 'IMPORT_ID=%s\n' "$import_id"
cat synthetic-prepare.json
printf '\nLOAD\n'
cat load-result.json
printf 'READINESS\n'
cat readiness.json
printf '\nHEADERS\n'
grep -Ei 'HTTP/|x-request-id|x-content-type|x-frame|referrer|permissions|cache-control' headers.txt
printf 'RESTART_AND_CLEANUP=PASS\n'
