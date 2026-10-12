#!/bin/sh
set -eu
psql "$1" --set=ON_ERROR_STOP=1 --set=tk_password="$TIMEKEEPER_DATABASE_PASSWORD" <<'SQL'
CREATE ROLE timekeeper LOGIN PASSWORD :'tk_password';
CREATE DATABASE timekeeper OWNER timekeeper;
REVOKE ALL ON DATABASE timekeeper FROM PUBLIC;
SQL
