-- REVIEW ONLY until authorized. Connect explicitly to rallye_test as cluster administrator.
\set ON_ERROR_STOP on
BEGIN;
DO $guard$
BEGIN
    IF current_database() <> 'rallye_test'
       OR current_setting('server_version_num')::int / 10000 <> 17 THEN
        RAISE EXCEPTION 'This script may run only in PostgreSQL 17 database rallye_test';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_database
                   WHERE datname = current_database()
                     AND datdba = (SELECT oid FROM pg_roles WHERE rolname = 'rallye_test')) THEN
        RAISE EXCEPTION 'Unexpected test database owner';
    END IF;
END
$guard$;
-- PostgreSQL 17 template0 normally uses pg_database_owner for public schema.
REVOKE ALL PRIVILEGES ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO rallye_test;
COMMIT;
