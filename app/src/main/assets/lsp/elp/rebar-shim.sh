#!/bin/sh
# App-authored immutable private shim. Captured absolute paths remain data.
set -eu
: "${DROIDE_ELP_SELECTED_ESCRIPT:?Selected escript missing}"
: "${DROIDE_ELP_SELECTED_REBAR:?This owner does not permit Rebar execution}"
exec "$DROIDE_ELP_SELECTED_ESCRIPT" "$DROIDE_ELP_SELECTED_REBAR" "$@"
