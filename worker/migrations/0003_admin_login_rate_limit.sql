CREATE TABLE IF NOT EXISTS admin_login_attempts (
    ip_hash TEXT PRIMARY KEY,
    failures INTEGER NOT NULL,
    window_started_ms INTEGER NOT NULL,
    blocked_until_ms INTEGER NOT NULL DEFAULT 0
);
