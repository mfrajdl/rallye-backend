CREATE TABLE external_registration (
 id uuid PRIMARY KEY,
 provider varchar(12) NOT NULL CHECK(provider IN ('GOOGLE','APPLE')),
 issuer varchar(255) NOT NULL,
 subject varchar(255) NOT NULL,
 proposed_email varchar(254),
 email_verified boolean NOT NULL,
 display_name varchar(100),
 remember_me boolean NOT NULL,
 return_uri varchar(2048) NOT NULL,
 token_hash char(64) NOT NULL UNIQUE,
 created_at timestamptz(3) NOT NULL,
 expires_at timestamptz(3) NOT NULL,
 consumed_at timestamptz(3),
 CHECK(expires_at=created_at+interval '15 minutes')
);
CREATE INDEX external_registration_identity ON external_registration(provider,issuer,subject);
-- Deliberately no FK to user_account: cancellation must retain the minimum encrypted revocation material.
CREATE TABLE apple_revocation_credential (
 id uuid PRIMARY KEY,
 account_id uuid,
 registration_id uuid REFERENCES external_registration(id) ON DELETE SET NULL,
 client_id varchar(255) NOT NULL,
 key_id varchar(64) NOT NULL,
 iv bytea NOT NULL CHECK(octet_length(iv)=12),
 ciphertext bytea NOT NULL CHECK(octet_length(ciphertext)>16),
 created_at timestamptz(3) NOT NULL
);
CREATE INDEX apple_credential_account ON apple_revocation_credential(account_id);
