PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS driver_accounts (
    id TEXT PRIMARY KEY,
    auth0_sub TEXT NOT NULL UNIQUE,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    valid_from_ms INTEGER NOT NULL,
    valid_until_ms INTEGER NOT NULL,
    duration_value INTEGER NOT NULL CHECK (duration_value > 0),
    duration_unit TEXT NOT NULL CHECK (duration_unit IN ('days', 'weeks', 'months')),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    disabled_at_ms INTEGER,
    last_auth_check_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_driver_accounts_status_validity
    ON driver_accounts(status, valid_from_ms, valid_until_ms);

CREATE TABLE IF NOT EXISTS admin_devices (
    id TEXT PRIMARY KEY,
    label TEXT NOT NULL,
    public_jwk_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    enrolled_at_ms INTEGER NOT NULL,
    last_seen_at_ms INTEGER
);

CREATE TABLE IF NOT EXISTS admin_nonces (
    nonce TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES admin_devices(id),
    expires_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_nonces_expiry ON admin_nonces(expires_at_ms);

CREATE TABLE IF NOT EXISTS audit_log (
    id TEXT PRIMARY KEY,
    actor_device_id TEXT,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log(created_at_ms);
CREATE INDEX IF NOT EXISTS idx_audit_log_target ON audit_log(target_type, target_id);
