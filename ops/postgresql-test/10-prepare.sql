-- REVIEW ONLY until explicitly authorized. Run from a FRESH psql session connected to postgres.
-- Requires all files in this directory together (relative includes use \ir).
-- No -1 / --single-transaction: CREATE DATABASE cannot run in a transaction block.
\set ON_ERROR_STOP on
\ir 05-preconditions.sql
-- Serialize this runbook's administrators. Session close releases the lock after errors.
SELECT pg_advisory_lock(724119, 5434);
DO $prepare$
DECLARE test_role pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO test_role FROM pg_roles WHERE rolname = 'rallye_test';
    IF FOUND THEN
        IF test_role.rolsuper OR test_role.rolcreatedb OR test_role.rolcreaterole
            OR test_role.rolinherit OR test_role.rolreplication OR test_role.rolbypassrls THEN
            RAISE EXCEPTION 'Existing rallye_test role has unexpected attributes; refusing to repair it';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_auth_members
                   WHERE member = test_role.oid OR roleid = test_role.oid) THEN
            RAISE EXCEPTION 'Existing rallye_test role has memberships; investigate manually';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_database
                   WHERE datname IN ('rallye', 'rallye_dev')
                     AND (datdba = test_role.oid
                       OR has_database_privilege(test_role.oid, oid, 'CREATE'))) THEN
            RAISE EXCEPTION 'Existing role owns/can create in protected databases; refusing to continue';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_database d
                   CROSS JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
                   WHERE d.datname IN ('rallye', 'rallye_dev')
                     AND a.grantee = test_role.oid) THEN
            RAISE EXCEPTION 'Existing role has explicit application database grants; refusing to continue';
        END IF;
    ELSE
        -- Stage without login; set password interactively and enable login after verification.
        CREATE ROLE rallye_test NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database WHERE datname = 'rallye_test'
               AND (datdba <> (SELECT oid FROM pg_roles WHERE rolname = 'rallye_test')
                    OR NOT datallowconn OR encoding <> pg_char_to_encoding('UTF8'))) THEN
        RAISE EXCEPTION 'Existing rallye_test database has unexpected ownership/settings; no automatic repair';
    END IF;
END
$prepare$;
SELECT 'CREATE DATABASE rallye_test OWNER rallye_test TEMPLATE template0 ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'rallye_test')
\gexec
-- Only the NEW test database ACL is changed. No production/development ACL changes.
REVOKE ALL PRIVILEGES ON DATABASE rallye_test FROM PUBLIC;
SELECT pg_advisory_unlock(724119, 5434);
\echo 'Prepared. Newly created role remains NOLOGIN; existing login state was preserved. Next: test schema and application-table privilege checks.'
