-- READ ONLY. Run with psql -X -v ON_ERROR_STOP=1 as the existing cluster administrator.
\set ON_ERROR_STOP on
BEGIN READ ONLY;
SELECT version(), current_database(), current_user;
SELECT name, setting FROM pg_settings
WHERE name IN ('server_version_num', 'port', 'listen_addresses', 'password_encryption') ORDER BY name;
SELECT datname, pg_get_userbyid(datdba) AS owner, datallowconn,
       pg_encoding_to_char(encoding) AS encoding, datcollate, datctype
FROM pg_database ORDER BY datname;
-- Informational: PUBLIC CONNECT/TEMPORARY are accepted. Expand NULL ACL defaults.
SELECT d.datname, CASE WHEN a.grantee = 0 THEN 'PUBLIC'
                      ELSE pg_get_userbyid(a.grantee) END AS grantee,
       a.privilege_type, a.is_grantable
FROM pg_database d
CROSS JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
WHERE d.datname IN ('rallye', 'rallye_dev', 'rallye_test')
ORDER BY d.datname, grantee, a.privilege_type;
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
       rolinherit, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname = 'rallye_test';
SELECT pg_get_userbyid(roleid) AS granted_role,
       pg_get_userbyid(member) AS member, admin_option, inherit_option, set_option
FROM pg_auth_members
WHERE roleid = (SELECT oid FROM pg_roles WHERE rolname = 'rallye_test')
   OR member = (SELECT oid FROM pg_roles WHERE rolname = 'rallye_test');
COMMIT;
