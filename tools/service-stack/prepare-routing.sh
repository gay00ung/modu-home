#!/bin/sh
set -eu

routing_dataset=${1:?A dataset basename is required}
case "$routing_dataset" in
    [!a-zA-Z0-9]*|*[!a-zA-Z0-9._-]*|"")
        printf '%s\n' 'Use a dataset basename without directory components' >&2
        exit 2
        ;;
esac
case "$routing_dataset" in
    *.osrm) ;;
    *) printf '%s\n' 'Dataset basename must end in .osrm' >&2; exit 2 ;;
esac

routing_target="/data/$routing_dataset"
routing_source="/data/${routing_dataset%.osrm}.osm.pbf"
if [ ! -r "$routing_source" ]; then
    printf '%s\n' 'Copy the matching .osm.pbf source into the data directory first' >&2
    exit 2
fi
for routing_existing in "$routing_target" "$routing_target".*; do
    if [ -e "$routing_existing" ] || [ -L "$routing_existing" ]; then
        printf '%s\n' 'Use a fresh directory to preserve the existing dataset' >&2
        exit 2
    fi
done

osrm-extract -p /opt/car.lua -t 2 -d osmosis -o "$routing_target" "$routing_source"
osrm-partition -t 2 "$routing_target"
osrm-customize -t 2 "$routing_target"
