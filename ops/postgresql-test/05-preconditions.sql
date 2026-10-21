-- READ ONLY. Also included by preparation/verification. No ACL modifications.
\set ON_ERROR_STOP on
DO $preconditions$
BEGIN
    IF current_database() <> 'postgres' THEN
        RAISE EXCEPTION 'Connect explicitly to maintenance database postgres';
    END IF;
    IF current_setting('server_version_num')::int / 10000 <> 17 THEN
        RAISE EXCEPTION 'PostgreSQL 17 required';
    END IF;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'An existing cluster superuser is required for reviewed administration';
    END IF;
    IF (SELECT count(*) FROM pg_database WHERE datname IN ('rallye', 'rallye_dev')) <> 2 THEN
        RAISE EXCEPTION 'Expected protected databases not found; verify cluster identity';
    END IF;
END
$preconditions$;
