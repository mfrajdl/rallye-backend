\set ON_ERROR_STOP on
-- psql -v account_id=<UUID of an existing active, verified account> -f this-file.sql
-- Run only after Flyway V2. Do not pass passwords on the command line.
BEGIN;
SELECT set_config('rallye.bootstrap_account_id', :'account_id', true);
DO $$
DECLARE
    target uuid := current_setting('rallye.bootstrap_account_id')::uuid;
    initialized boolean;
BEGIN
    SELECT bootstrapped INTO STRICT initialized FROM admin_control WHERE id=1 FOR UPDATE;
    PERFORM id FROM user_account WHERE id=target FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM user_account WHERE id=target AND status='ACTIVE' AND email_verified_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Bootstrap target must be active and email verified';
    END IF;
    IF NOT initialized AND EXISTS (SELECT 1 FROM user_account_role WHERE role='ADMIN') THEN
        RAISE EXCEPTION 'Inconsistent admin_control: ADMIN exists but bootstrapped is false; investigate before continuing';
    END IF;
    IF initialized THEN
        IF EXISTS (SELECT 1 FROM user_account_role WHERE account_id=target AND role='ADMIN') THEN
            RETURN; -- Repeat for the same existing administrator: no change and no extra audit.
        END IF;
        RAISE EXCEPTION 'First administrator already initialized; use authenticated role management';
    END IF;
    INSERT INTO user_account_role(account_id,role,granted_at) VALUES (target,'ADMIN',clock_timestamp());
    INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id)
        VALUES (gen_random_uuid(),clock_timestamp(),'OPERATOR',NULL,'ADMIN_BOOTSTRAPPED',target);
END;
$$;
COMMIT;
