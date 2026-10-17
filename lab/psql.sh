#!/bin/sh
# Opens a psql prompt inside the lab database.
#
#   lab/psql.sh                  as postgres, the administrator
#   lab/psql.sh querysmith_ro    as the tool's read-only login
#   lab/psql.sh shop_app         as the shop's application
#
# psql runs inside the container, so nothing needs installing on your machine.
# Connections made this way skip the password; connections from outside the
# container (your Java code, on port 5433) do not.
set -eu
cd "$(dirname "$0")/.."
exec docker compose exec db psql --username="${1:-postgres}" --dbname=shop
