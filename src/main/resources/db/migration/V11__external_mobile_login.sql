-- Bootstrap only: no handoff issuance, exchange or session creation.
CREATE TABLE mobile_login_attempt (
 id uuid PRIMARY KEY,
 provider varchar(12) NOT NULL CHECK(provider IN ('GOOGLE','APPLE')),
 pkce_challenge varchar(43) NOT NULL CHECK(pkce_challenge ~ '^[A-Za-z0-9_-]{43}$'),
 remember_me boolean NOT NULL,
 return_id varchar(100) NOT NULL,
 return_uri varchar(2048) NOT NULL,
 profile_uri varchar(2048) NOT NULL,
 fallback_uri varchar(2048) NOT NULL,
 ticket_hash char(64) NOT NULL UNIQUE CHECK(ticket_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz(3) NOT NULL,
 expires_at timestamptz(3) NOT NULL,
 launch_consumed_at timestamptz(3),
 flow_id uuid UNIQUE REFERENCES external_auth_flow(id),
 CHECK(expires_at=created_at+interval '10 minutes'),
 CHECK((launch_consumed_at IS NULL)=(flow_id IS NULL)),
 CHECK(launch_consumed_at IS NULL OR (launch_consumed_at>=created_at AND launch_consumed_at<expires_at))
);
CREATE INDEX mobile_login_attempt_expiry ON mobile_login_attempt(expires_at);
