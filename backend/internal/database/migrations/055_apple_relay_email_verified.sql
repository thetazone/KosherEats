-- One-time backfill: mark Apple private-relay consumers as email-verified.
--
-- Post-054 Apple sign-ups were created with email_verified = false because
-- the social path didn't trust Apple emails. That was wrong for relay
-- addresses: a @privaterelay.appleid.com address can only have come from a
-- verified Apple ID token (verifyAppleToken rejects missing/unverified
-- emails), and Apple forwards relay mail to the real inbox, so it's both
-- verified and deliverable. Users who swapped in a custom address via the
-- email-OTP flow are already email_verified = true, and UpdateProfile can't
-- produce a relay address — so this predicate only ever hits rows whose
-- email came straight from Apple.
--
-- The social-login re-auth self-heal fixes these rows on the next sign-in;
-- this backfill covers users holding active sessions who won't re-auth.
--
-- Deliberately NOT broadened to non-relay apple rows: an apple-auth user who
-- chose "Share My Email" (real address) is indistinguishable post-hoc from
-- one who later typed a custom, never-verified address into the profile
-- sheet, so flipping those blindly could mark an unproved address verified.
-- That cohort self-heals on their next sign-in (the re-auth UPDATE checks
-- the live token email) and the email OTP works for real addresses anyway.
UPDATE users SET email_verified = true
WHERE auth_provider = 'apple' AND email_verified = false
  AND email ILIKE '%@privaterelay.appleid.com';
