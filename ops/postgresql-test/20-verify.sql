-- READ ONLY. Run as cluster administrator connected to postgres. Failure means STOP.
\set ON_ERROR_STOP on
BEGIN READ ONLY;
\ir 05-preconditions.sql
DO $verify$
DECLARE test_role pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO test_role FROM pg_roles WHERE rolname = 'rallye_test';
    IF NOT FOUND THEN RAISE EXCEPTION 'Missing test role'; END IF;
    IF test_role.rolsuper OR test_role.rolcreatedb OR test_role.rolcreaterole
       OR test_role.rolinherit OR test_role.rolreplication OR test_role.rolbypassrls THEN
        RAISE EXCEPTION 'Unsafe test role attributes';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members
               WHERE member = test_role.oid OR roleid = test_role.oid) THEN
        RAISE EXCEPTION 'Unexpected membership involving test role';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_database
                   WHERE datname = 'rallye_test' AND datdba = test_role.oid AND datallowconn) THEN
        RAISE EXCEPTION 'Missing test database or unexpected owner';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database
               WHERE datname IN ('rallye', 'rallye_dev')
                 AND (datdba = test_role.oid
                   OR has_database_privilege(test_role.oid, oid, 'CREATE'))) THEN
        RAISE EXCEPTION 'Test role owns/can create in protected databases';
    END IF;
    -- Explicit grants to the test role are forbidden; PUBLIC CONNECT/TEMP are accepted.
    IF EXISTS (SELECT 1 FROM pg_database d
               CROSS JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
               WHERE d.datname IN ('rallye', 'rallye_dev')
                 AND a.grantee = test_role.oid) THEN
        RAISE EXCEPTION 'Unexpected explicit database grant to rallye_test';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database d
               CROSS JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
               WHERE d.datname = 'rallye_test' AND a.grantee = 0) THEN
        RAISE EXCEPTION 'Test database still grants privileges to PUBLIC';
    END IF;
END
$verify$;
SELECT d.datname,
       has_database_privilege('rallye_test', d.oid, 'CONNECT') AS can_connect,
       has_database_privilege('rallye_test', d.oid, 'CREATE') AS can_create,
       has_database_privilege('rallye_test', d.oid, 'TEMPORARY') AS can_temp
FROM pg_database d WHERE datname IN ('rallye', 'rallye_dev', 'rallye_test') ORDER BY d.datname;
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
       rolinherit, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname = 'rallye_test';
COMMIT;
