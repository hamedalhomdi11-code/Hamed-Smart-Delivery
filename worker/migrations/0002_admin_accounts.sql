CREATE TABLE IF NOT EXISTS admin_credentials (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    username TEXT NOT NULL COLLATE NOCASE,
    salt TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    iterations INTEGER NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS admin_sessions (
    token_hash TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    created_at_ms INTEGER NOT NULL,
    last_seen_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_sessions_expiry ON admin_sessions(expires_at_ms);
