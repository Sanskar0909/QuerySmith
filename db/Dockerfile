# The lab database: stock Postgres plus HypoPG.
#
# pg_stat_statements already ships inside the official image (it is part of
# Postgres "contrib"), so it only needs switching on, which docker-compose.yml
# does. HypoPG is a separate project, so it has to be installed here.
FROM postgres:18

# The official image is Debian with the Postgres project's own package
# repository (PGDG) already configured. HypoPG is published there as
# postgresql-<major>-hypopg, so the major version below must match the FROM line.
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-18-hypopg \
 && rm -rf /var/lib/apt/lists/*
