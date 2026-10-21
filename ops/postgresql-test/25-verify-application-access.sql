-- READ ONLY. Run separately in BOTH rallye and rallye_dev as existing administrator.
-- Queries catalog privileges only; never reads/writes application rows or calls routines.
\set ON_ERROR_STOP on
BEGIN READ ONLY;
DO $verify$
DECLARE test_oid oid;
BEGIN
    IF current_database() NOT IN ('rallye', 'rallye_dev')
       OR current_setting('server_version_num')::int / 10000 <> 17 THEN
        RAISE EXCEPTION 'Run only in protected PostgreSQL 17 application databases';
    END IF;
    SELECT oid INTO test_oid FROM pg_roles WHERE rolname = 'rallye_test';
    IF test_oid IS NULL THEN RAISE EXCEPTION 'Missing rallye_test role'; END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace n
               WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
                 AND (n.nspowner = test_oid OR has_schema_privilege(test_oid, n.oid, 'CREATE'))) THEN
        RAISE EXCEPTION 'Test role owns/can create in an application schema';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
                 AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                 AND (c.relowner = test_oid
                   OR has_table_privilege(test_oid, c.oid,
                       'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                   OR has_any_column_privilege(test_oid, c.oid, 'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
        RAISE EXCEPTION 'Test role has application table/view/column privileges (possibly via PUBLIC)';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
                 AND c.relkind = 'S'
                 AND (c.relowner = test_oid
                   OR has_sequence_privilege(test_oid, c.oid, 'SELECT,UPDATE,USAGE'))) THEN
        RAISE EXCEPTION 'Test role has application sequence privileges';
    END IF;
    -- Definer routines could expose tables despite denied direct table privileges.
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
                 AND (p.proowner = test_oid
                   OR (p.prosecdef AND has_function_privilege(test_oid, p.oid, 'EXECUTE')))) THEN
        RAISE EXCEPTION 'Review application routine ownership/accessible SECURITY DEFINER routine';
    END IF;
END
$verify$;
SELECT current_database() AS checked_database,
       count(*) AS checked_tables_and_views,
       'PASS: no application table/column privileges for rallye_test' AS result
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'f');
COMMIT;
