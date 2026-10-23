ALTER TABLE user_account DROP CONSTRAINT user_account_status_check;
ALTER TABLE user_account ADD CONSTRAINT user_account_status_check
    CHECK (status IN ('PENDING_EMAIL','ACTIVE','DISABLED','ANONYMIZED'));
ALTER TABLE user_account ADD COLUMN cancelled_at timestamptz(3);
ALTER TABLE user_account ALTER COLUMN email DROP NOT NULL;
ALTER TABLE user_account ALTER COLUMN normalized_email DROP NOT NULL;
ALTER TABLE user_account ALTER COLUMN display_name DROP NOT NULL;
ALTER TABLE user_account ADD CONSTRAINT user_account_identity_state_check CHECK (
    (status = 'ANONYMIZED' AND email IS NULL AND normalized_email IS NULL
        AND display_name IS NULL AND email_verified_at IS NULL AND cancelled_at IS NOT NULL)
    OR (status <> 'ANONYMIZED' AND email IS NOT NULL AND normalized_email IS NOT NULL
        AND display_name IS NOT NULL AND cancelled_at IS NULL));

-- NULL identifies pre-V6 rows. New writers always provide explicit related accounts.
ALTER TABLE admin_role_command ADD COLUMN related_account_ids uuid[];
ALTER TABLE admin_role_command ADD CONSTRAINT command_related_accounts_check CHECK (
    related_account_ids IS NULL OR (cardinality(related_account_ids) > 0
        AND array_ndims(related_account_ids) = 1 AND array_position(related_account_ids,NULL) IS NULL));
CREATE INDEX command_related_accounts ON admin_role_command USING gin(related_account_ids);
