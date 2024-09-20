#!/usr/bin/env bash

TESTROOT="$( cd "$(dirname "$0")"/.. >/dev/null 2>&1 ; pwd -P )"
PROJECTROOT="$( cd "$(dirname "$0")"/../../.. >/dev/null 2>&1 ; pwd -P )"
source $TESTROOT/files/testfunctions.sh
source $TESTROOT/files/test.conf
prepareTest $(basename $0) $*

tempdir=$(makeTempDir $(basename $0))
graphs_path="${tempdir}/graphs"
mkdir $graphs_path
configCar=$(writeToFile $tempdir ors-config.yml "\
ors:
  endpoints:
    isochrones:
      maximum_intervals: 2
  engine:
    profile_default:
      enabled: false
      graph_path: graphs
      source_file:
      encoder_options:
        turn_costs: true
#      elevation: true
#      elevation_smoothing: false
#      encoder_flags_size: 8
#      instructions: true
#      optimize: false
#      traffic: false
#      maximum_distance: 100000
#      maximum_distance_dynamic_weights: 100000
#      maximum_distance_avoid_areas: 100000
#      maximum_waypoints: 50
#      maximum_snapping_radius: 400
#      maximum_distance_alternative_routes: 100000
#      maximum_distance_round_trip_routes: 100000
#      maximum_speed_lower_bound: 80
#      maximum_visited_nodes: 1000000
#      location_index_resolution: 500
#      location_index_search_iterations: 4
#      force_turn_costs: false
#      interpolate_bridges_and_tunnels: true
#      preparation:
#        min_network_size: 200
#        methods:
#          lm:
#            enabled: true
#            threads: 1
#            weightings: recommended,shortest
#            landmarks: 16
#      execution:
#        methods:
#          lm:
#            active_landmarks: 8
    graph_management:
      enabled: true
    profiles:
      bobby-car:
        enabled: true
        encoder_name: driving-car
        repo:
          repository_uri: '/home/ors/graph_repo'
          repository_name: vendor-xyz
          repository_profile_group: fastisochrones
          graph_extent: heidelberg
")

# TODO Change ors-config.yml profile_default to stupid values and check if the test still passes.

# TODO Why do I have to set turn_costs: false in profile_default or turn_costs: true in the profile to make the test pass?

# FIXME Response DirectionsRequest: "profile": "driving-car"
# FIXME Response DirectionsRequest: "build_date": "2024-09-19T16:34:55Z",
# FIXME Response DirectionsRequest: "graph_date": "2024-08-15T12:56:49Z"
# FIXME Response DirectionsRequestAvoidArea: "profile": "driving-car"
# FIXME Response DirectionsRequestAvoidArea: "build_date": "2024-09-19T16:34:55Z",
# FIXME Response DirectionsRequestAvoidArea: "graph_date": "2024-08-15T12:56:49Z"
# FIXME Response IsochronesRequest: "profile": "driving-car"
# FIXME Response IsochronesRequest: "build_date": "2024-09-19T16:34:55Z",
# FIXME Response IsochronesRequest: "graph_date": "2024-08-15T12:56:49Z"
# FIXME Response MatrixRequest: "profile": "driving-car"
# FIXME Response MatrixRequest: "build_date": "2024-09-19T16:34:55Z",
# FIXME Response MatrixRequest: "graph_date": null                                    <<<
# FIXME Response SnappingRequest: "profile": "driving-car"
# FIXME Response SnappingRequest: "build_date": "2024-09-19T16:34:55Z",
# FIXME Response SnappingRequest: "graph_date": "2024-08-15T12:56:49Z"

# --------
# The test asserts that ORS uses the profile properties coming from the repo and not the ones from the config file.
# All kinds of requests should work fine.
# 'source_file' is set to null, to avoid the graph is built locally.

podman run --replace --name "${CONTAINER}" -p "${HOST_PORT}":8082 \
  -v "${M2_FOLDER}":/root/.m2 \
  -v "${graphs_path}":"${CONTAINER_WORK_DIR}/graphs" \
  -v "${PROJECTROOT}/ors-engine/src/test/resources/test-filesystem-repos":"/home/ors/graph_repo" \
  -v "${configCar}":${CONTAINER_WORK_DIR}/ors-config.yml \
  "local/${IMAGE}:latest" &

awaitOrsReady 60 "${HOST_PORT}"

profiles=$(requestEnabledProfiles ${HOST_PORT})

assertEquals "bobby-car" "${profiles}"

sendSeveralRequestsExpecting200 "${HOST_PORT}" "bobby-car" "${tempdir}"
