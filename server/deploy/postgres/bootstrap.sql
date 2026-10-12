-- Only executed by the official entrypoint when the data directory is empty.
\set app_password `cat /run/secrets/timekeeper_database_password`
CREATE ROLE timekeeper LOGIN PASSWORD :'app_password';
CREATE DATABASE timekeeper OWNER timekeeper;
