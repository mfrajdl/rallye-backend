-- All role writers, bootstrap and future account deactivation/deletion use this lock.
CREATE TABLE admin_control (
    id integer PRIMARY KEY CHECK (id = 1),
    bootstrapped boolean NOT NULL DEFAULT false
);
INSERT INTO admin_control(id) VALUES (1);

CREATE TABLE user_account_role (
    account_id uuid NOT NULL REFERENCES user_account(id),
    role varchar(16) NOT NULL CHECK (role = 'ADMIN'),
    granted_at timestamptz(3) NOT NULL,
    PRIMARY KEY(account_id, role)
);

-- Pseudonymous references deliberately have no account FK: deleting an account
-- must neither delete audit history nor require keeping its personal data.
CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    occurred_at timestamptz(3) NOT NULL,
    actor_kind varchar(16) NOT NULL CHECK (actor_kind IN ('USER', 'OPERATOR', 'SYSTEM')),
    actor_id uuid,
    event_type varchar(48) NOT NULL,
    target_id uuid NOT NULL,
    CHECK ((actor_kind = 'USER' AND actor_id IS NOT NULL)
        OR (actor_kind <> 'USER' AND actor_id IS NULL))
);
CREATE INDEX audit_event_time ON audit_event(occurred_at DESC, id DESC);
CREATE INDEX audit_event_actor ON audit_event(actor_id, occurred_at DESC);
CREATE INDEX audit_event_target ON audit_event(target_id, occurred_at DESC);
CREATE INDEX audit_event_type ON audit_event(event_type, occurred_at DESC);

CREATE TABLE admin_role_command (
    actor_id uuid NOT NULL,
    command_key uuid NOT NULL,
    target_id uuid NOT NULL,
    grant_role boolean NOT NULL,
    created_at timestamptz(3) NOT NULL,
    expires_at timestamptz(3) NOT NULL,
    PRIMARY KEY(actor_id, command_key),
    CHECK (expires_at > created_at)
);
CREATE INDEX admin_role_command_expiry ON admin_role_command(expires_at);

CREATE FUNCTION guard_admin_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Write the singleton as well as locking it: snapshot-isolated writers must
    -- conflict rather than both remove administrators using an old snapshot.
    UPDATE admin_control SET bootstrapped = bootstrapped WHERE id = 1;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Admin roles must be inserted or deleted, not updated';
    ELSIF TG_OP = 'INSERT' THEN
        PERFORM id FROM user_account WHERE id = NEW.account_id FOR UPDATE;
        IF NOT EXISTS (SELECT 1 FROM user_account WHERE id = NEW.account_id
                       AND status = 'ACTIVE' AND email_verified_at IS NOT NULL) THEN
            RAISE EXCEPTION 'Admin target must be active and email verified';
        END IF;
        UPDATE admin_control SET bootstrapped = true WHERE id = 1;
        RETURN NEW;
    END IF;
    IF (SELECT bootstrapped FROM admin_control WHERE id = 1)
       AND NOT EXISTS (
           SELECT 1 FROM user_account_role r JOIN user_account a ON a.id = r.account_id
           WHERE r.role = 'ADMIN' AND r.account_id <> OLD.account_id
             AND a.status = 'ACTIVE' AND a.email_verified_at IS NOT NULL
       ) THEN
        RAISE EXCEPTION 'Cannot remove the last active administrator';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER user_account_role_guard BEFORE INSERT OR UPDATE OR DELETE
    ON user_account_role FOR EACH ROW EXECUTE FUNCTION guard_admin_role();

CREATE FUNCTION guard_admin_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    invalidating boolean;
BEGIN
    UPDATE admin_control SET bootstrapped = bootstrapped WHERE id = 1;
    IF EXISTS (SELECT 1 FROM user_account_role WHERE account_id = OLD.id AND role = 'ADMIN') THEN
        IF TG_OP = 'DELETE' THEN
            invalidating := true;
        ELSE
            invalidating := NEW.status <> 'ACTIVE' OR NEW.email_verified_at IS NULL;
        END IF;
        IF invalidating THEN
            IF NOT EXISTS (
                SELECT 1 FROM user_account_role r JOIN user_account a ON a.id = r.account_id
                WHERE r.role = 'ADMIN' AND a.id <> OLD.id
                  AND a.status = 'ACTIVE' AND a.email_verified_at IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'Cannot invalidate the last active administrator';
            END IF;
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER user_account_admin_guard BEFORE DELETE OR UPDATE OF status, email_verified_at
    ON user_account FOR EACH ROW EXECUTE FUNCTION guard_admin_account();
