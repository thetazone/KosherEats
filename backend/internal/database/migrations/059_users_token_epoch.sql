-- Session revocation on password reset.
--
-- Refresh tokens are stateless 7-day HS256 JWTs and /auth/refresh mints a
-- brand-new 7-day refresh token on every call, so a leaked refresh token used
-- to grant *indefinite* account access: the standard remediation (reset the
-- password) rewrote password_hash only and left every previously-issued token
-- valid. There was no session store, jti or denylist anywhere to revoke them.
--
-- token_epoch is the cheap fix: it is stamped into both the access and refresh
-- token claims at mint time, verified against this column on every
-- authenticated request and on every refresh, and bumped by ResetPassword.
-- Bumping it invalidates every token issued before the reset in one write.
--
-- DEFAULT 0 matches what the claim helper reads for pre-059 tokens that carry
-- no epoch claim at all, so existing sessions survive the deploy and only stop
-- working once their account actually resets its password.
-- Additive; can't abort boot.
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_epoch INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.token_epoch IS
  'Monotonic session generation. Embedded in JWT claims as "epoch"; bumped on password reset to revoke all outstanding access + refresh tokens.';
