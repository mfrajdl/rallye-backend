-- Extend the existing command ledger; existing ADMIN keys retain their original expiry.
ALTER TABLE admin_role_command ALTER COLUMN grant_role DROP NOT NULL;
ALTER TABLE admin_role_command ADD COLUMN operation varchar(48) NOT NULL DEFAULT 'ADMIN_ROLE';
ALTER TABLE admin_role_command ADD COLUMN request_data jsonb;
ALTER TABLE admin_role_command ADD COLUMN result_data jsonb;

CREATE TABLE crew (
    id uuid PRIMARY KEY,
    captain_account_id uuid REFERENCES user_account(id),
    name varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    registration_plate varchar(32),
    description varchar(2000),
    photo_id uuid UNIQUE,
    photo_type varchar(16) CHECK (photo_type IN ('image/jpeg','image/png')),
    photo_size integer CHECK (photo_size BETWEEN 1 AND 5242880),
    photo_width integer CHECK (photo_width BETWEEN 1 AND 4096),
    photo_height integer CHECK (photo_height BETWEEN 1 AND 4096),
    created_at timestamptz(3) NOT NULL,
    updated_at timestamptz(3) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((photo_id IS NULL AND photo_type IS NULL AND photo_size IS NULL AND photo_width IS NULL AND photo_height IS NULL)
        OR (photo_id IS NOT NULL AND photo_type IS NOT NULL AND photo_size IS NOT NULL AND photo_width IS NOT NULL AND photo_height IS NOT NULL))
);
CREATE INDEX crew_captain ON crew(captain_account_id,created_at,id);
ALTER TABLE audit_event ADD COLUMN target_type varchar(16) NOT NULL DEFAULT 'ACCOUNT';
ALTER TABLE audit_event ADD COLUMN previous_captain_id uuid;
ALTER TABLE audit_event ADD COLUMN new_captain_id uuid;
