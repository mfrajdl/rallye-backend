-- Fail on duplicate provider identities; never merge or delete user data.
ALTER TABLE user_identity ADD CONSTRAINT user_identity_account_provider_unique UNIQUE(account_id,provider);
CREATE TABLE external_auth_flow (
 id uuid PRIMARY KEY,
 provider varchar(12) NOT NULL CHECK(provider IN ('GOOGLE','APPLE')),
 purpose varchar(24) NOT NULL CHECK(purpose IN ('LOGIN','LINK','REAUTHENTICATE')),
 account_id uuid REFERENCES user_account(id) ON DELETE CASCADE,
 session_id uuid REFERENCES user_session(id) ON DELETE CASCADE,
 state_hash char(64) NOT NULL UNIQUE,
 nonce_hash char(64) NOT NULL,
 correlation_hash char(64) NOT NULL,
 callback_uri varchar(2048) NOT NULL,
 return_uri varchar(2048) NOT NULL,
 remember_me boolean NOT NULL,
 mobile_challenge varchar(43),
 created_at timestamptz(3) NOT NULL,
 expires_at timestamptz(3) NOT NULL,
 consumed_at timestamptz(3),
 CHECK(expires_at=created_at+interval '10 minutes'),
 CHECK(consumed_at IS NULL OR consumed_at>=created_at),
 CHECK((purpose='LOGIN' AND account_id IS NULL AND session_id IS NULL)
    OR (purpose<>'LOGIN' AND account_id IS NOT NULL AND session_id IS NOT NULL)),
 CHECK(mobile_challenge IS NULL OR mobile_challenge ~ '^[A-Za-z0-9_-]{43}$')
);
CREATE INDEX external_auth_flow_account ON external_auth_flow(account_id);
-- No grant/handoff issuance yet. These constraints define future protocol primitives.
CREATE TABLE external_auth_grant (
 id uuid PRIMARY KEY,
 account_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
 session_id uuid REFERENCES user_session(id) ON DELETE CASCADE,
 kind varchar(16) NOT NULL CHECK(kind IN ('REAUTH','MOBILE')),
 purpose varchar(64) NOT NULL,
 token_hash char(64) NOT NULL UNIQUE,
 pkce_challenge varchar(43),
 created_at timestamptz(3) NOT NULL,
 expires_at timestamptz(3) NOT NULL,
 consumed_at timestamptz(3),
 CHECK((kind='MOBILE' AND pkce_challenge ~ '^[A-Za-z0-9_-]{43}$' AND pkce_challenge IS NOT NULL
      AND expires_at=created_at+interval '60 seconds')
    OR (kind='REAUTH' AND session_id IS NOT NULL AND pkce_challenge IS NULL
      AND expires_at=created_at+interval '5 minutes')),
 CHECK(consumed_at IS NULL OR consumed_at>=created_at)
);
