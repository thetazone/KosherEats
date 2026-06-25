-- SECURITY remediation: neutralize derivable synthetic password hashes.
--
-- Phone-OTP and OAuth accounts were provisioned with a synthetic password that
-- was a pure function of public data (phone-OTP: bcrypt("phone-"+phone); OAuth:
-- bcrypt("oauth-"+provider_id)). Combined with a /login handler that matched any
-- account by (email, role, vertical) with no auth_provider guard, an attacker who
-- knew a victim's phone number could compute their synthetic email + password and
-- log in as them — a deterministic account-takeover that bypassed the OTP/OAuth
-- flow entirely.
--
-- The handler is fixed two ways: Login now rejects auth_provider != email, and
-- new phone/OAuth accounts get a crypto-random synthetic password. This migration
-- closes the residual risk on EXISTING rows by overwriting their (derivable) hash
-- with a value that can never satisfy bcrypt.CompareHashAndPassword. These
-- accounts authenticate via OTP/OAuth and never use password_hash, so blanking it
-- is safe. Idempotent.
UPDATE users
   SET password_hash = '', updated_at = NOW()
 WHERE auth_provider IS NOT NULL
   AND auth_provider <> 'email'
   AND password_hash <> '';
