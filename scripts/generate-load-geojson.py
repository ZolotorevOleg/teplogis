#!/usr/bin/env python3
"""Generate a strict synthetic input for map and read-load checks."""
import argparse
import json


def feature(object_id, object_type, geometry, **properties):
    values = {"id": object_id, "object_type": object_type}
    values.update(properties)
    return {"type": "Feature", "properties": values, "geometry": geometry}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output")
    parser.add_argument("--count", type=int, default=50000)
    args = parser.parse_args()
    if args.count < 5:
        parser.error("count must be at least 5")
    seed = [
        feature("load-source", "source", {"type": "Point", "coordinates": [37.6, 55.75]}),
        feature("load-network", "heat_network", {"type": "LineString", "coordinates": [[37.6, 55.75], [37.601, 55.75]]}, diameter=200),
        feature("load-chamber", "heat_chamber", {"type": "Point", "coordinates": [37.601, 55.75]}),
        feature("load-oks", "oks_connection_point", {"type": "Point", "coordinates": [37.602, 55.751]}, flow_tph=20),
        feature("load-park", "restriction", {"type": "Polygon", "coordinates": [[[37.603, 55.75], [37.604, 55.75], [37.604, 55.751], [37.603, 55.75]]]}, restriction_type="park"),
    ]
    with open(args.output, "w", encoding="utf-8") as target:
        target.write('{"type":"FeatureCollection","features":[')
        first = True
        for item in seed:
            if not first:
                target.write(",")
            json.dump(item, target, ensure_ascii=False, separators=(",", ":"))
            first = False
        for index in range(5, args.count):
            longitude = 37.605 + (index % 250) * .000001
            latitude = 55.752 + ((index // 250) % 250) * .000001
            item = feature("load-chamber-%d" % index, "heat_chamber", {"type": "Point", "coordinates": [longitude, latitude]})
            target.write(",")
            json.dump(item, target, ensure_ascii=False, separators=(",", ":"))
        target.write("]}")


if __name__ == "__main__":
    main()
