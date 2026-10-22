-- Historical identifiers deliberately have no FK to crew/user_account: their
-- permitted deletion must not remove or block the transfer history.
CREATE TABLE crew_transfer (
    id uuid PRIMARY KEY,
    crew_id uuid NOT NULL,
    from_account_id uuid NOT NULL,
    to_account_id uuid NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED','EXPIRED')),
    created_at timestamptz(3) NOT NULL,
    expires_at timestamptz(3) NOT NULL,
    resolved_at timestamptz(3),
    resolved_by uuid,
    resolution_reason varchar(48),
    CHECK (from_account_id <> to_account_id),
    CHECK (status NOT IN ('ACCEPTED','REJECTED') OR resolved_by = to_account_id),
    CHECK (expires_at = created_at + interval '168 hours'),
    CHECK (resolved_at IS NULL OR resolved_at >= created_at),
    CHECK ((status = 'PENDING' AND resolved_at IS NULL AND resolved_by IS NULL AND resolution_reason IS NULL)
        OR (status <> 'PENDING' AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL
            AND length(btrim(resolution_reason)) > 0
            AND ((status = 'EXPIRED' AND resolved_by IS NULL AND resolved_at >= expires_at)
                OR (status IN ('ACCEPTED','REJECTED','CANCELLED') AND resolved_by IS NOT NULL AND resolved_at < expires_at))))
);
CREATE UNIQUE INDEX crew_transfer_one_pending ON crew_transfer(crew_id) WHERE status = 'PENDING';
CREATE INDEX crew_transfer_crew_history ON crew_transfer(crew_id,created_at DESC,id DESC);
CREATE INDEX crew_transfer_from_account ON crew_transfer(from_account_id,created_at DESC);
CREATE INDEX crew_transfer_to_account ON crew_transfer(to_account_id,created_at DESC);
