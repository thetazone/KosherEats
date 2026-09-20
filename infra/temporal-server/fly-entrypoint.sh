#!/bin/bash
# Fly's private network (6PN) is IPv6-only: koshereats-api reaches this server at
# koshereats-temporal.internal:7233, which resolves to the machine's IPv6.
#
# The stock auto-setup entrypoint defaults BIND_ON_IP to `getent hosts $(hostname)`,
# which on Fly is the eth0 IPv4 (172.19.x.x) — NOT reachable over 6PN. So Temporal
# would bind to an address nothing on the private network can dial (connection
# reset). Pin BIND_ON_IP to the machine's private IPv6 (Fly injects FLY_PRIVATE_IP
# at runtime, so this stays correct across machine recreation / the standby).
set -eu
export BIND_ON_IP="${FLY_PRIVATE_IP}"
exec /etc/temporal/entrypoint.sh "$@"
