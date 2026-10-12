#!/bin/sh
set -eu
PATRONI_SUPERUSER_PASSWORD=$(cat /run/secrets/postgres_admin_password)
PATRONI_REPLICATION_PASSWORD=$(cat /run/secrets/postgres_replication_password)
PATRONI_RESTAPI_PASSWORD=$(cat /run/secrets/patroni_api_password)
TIMEKEEPER_DATABASE_PASSWORD=$(cat /run/secrets/timekeeper_database_password)
export PATRONI_SUPERUSER_PASSWORD PATRONI_REPLICATION_PASSWORD PATRONI_RESTAPI_PASSWORD TIMEKEEPER_DATABASE_PASSWORD
mkdir -p /var/lib/postgresql/data
chown postgres:postgres /var/lib/postgresql/data
chmod 700 /var/lib/postgresql/data
exec gosu postgres patroni /etc/patroni.yml
