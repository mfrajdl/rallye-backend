CREATE TABLE user_account (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    normalized_email varchar(254) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING_EMAIL', 'ACTIVE', 'DISABLED')),
    created_at timestamptz(3) NOT NULL,
    updated_at timestamptz(3) NOT NULL,
    email_verified_at timestamptz(3),
    CHECK (normalized_email = lower(btrim(email)))
);
CREATE TABLE user_identity (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES user_account(id),
    provider varchar(12) NOT NULL CHECK (provider IN ('LOCAL', 'GOOGLE', 'APPLE')),
    issuer varchar(255) NOT NULL,
    subject varchar(255) NOT NULL,
    password_hash varchar(512),
    created_at timestamptz(3) NOT NULL,
    UNIQUE (provider, issuer, subject),
    CHECK ((provider = 'LOCAL' AND password_hash IS NOT NULL) OR
           (provider <> 'LOCAL' AND password_hash IS NULL))
);
CREATE UNIQUE INDEX user_identity_local_account ON user_identity(account_id) WHERE provider = 'LOCAL';
CREATE INDEX user_identity_account ON user_identity(account_id);
CREATE TABLE user_action_token (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES user_account(id),
    purpose varchar(24) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    token_hash char(64) NOT NULL UNIQUE,
    created_at timestamptz(3) NOT NULL,
    expires_at timestamptz(3) NOT NULL,
    consumed_at timestamptz(3),
    CHECK (expires_at > created_at)
);
CREATE INDEX user_action_token_account ON user_action_token(account_id, purpose);
CREATE TABLE user_session (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES user_account(id),
    token_hash char(64) NOT NULL UNIQUE,
    remember_me boolean NOT NULL,
    created_at timestamptz(3) NOT NULL,
    last_used_at timestamptz(3) NOT NULL,
    expires_at timestamptz(3) NOT NULL,
    revoked_at timestamptz(3),
    CHECK (expires_at > last_used_at)
);
CREATE INDEX user_session_account ON user_session(account_id);
