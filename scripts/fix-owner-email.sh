#!/usr/bin/env bash
#
# fix-owner-email — swap a restaurant owner's LOGIN EMAIL without touching the
# restaurant or its imported menu. Use this when a restaurant was onboarded with
# a placeholder email and you later have the real owner's address.
#
# It changes exactly one column (users.email) on the restaurant's owner account.
# The restaurant, its menu, metadata, cover, and approval state are untouched.
# Afterwards the real owner claims the account in the seller app via
# "Forgot password?" — a 6-digit code is sent to the NEW email.
#
# Usage:
#   scripts/fix-owner-email.sh [-y] "<restaurant name>" "<new-owner-email>"
#   -y / --yes   skip the confirmation prompt
#
# Examples:
#   scripts/fix-owner-email.sh "Holon Kosher Grocery" "owner@holonkosher.com"
#   scripts/fix-owner-email.sh -y "Pizza Kids" "manager@pizzakids.com"
#
# DB target (defaults to the local docker Postgres):
#   KE_DB_URL=postgres://user:pass@host:5432/db   run host `psql` against any DB
#   KE_CONTAINER=mamiye-eats-postgres-1           docker container (default)
#   KE_DBNAME=koshereats                          database name (default)
#
set -euo pipefail

KE_CONTAINER="${KE_CONTAINER:-mamiye-eats-postgres-1}"
KE_DBNAME="${KE_DBNAME:-koshereats}"

usage() {
  sed -n '3,23p' "$0" | sed 's/^# \{0,1\}//'
}

# SQL is read from STDIN, not `-c`: psql only performs :'var' interpolation in
# its normal read loop (stdin/files), NOT for -c commands. Flags (-v, -At, ...)
# are forwarded as args. ON_ERROR_STOP makes any SQL error a non-zero exit,
# which `set -e` then catches.
psql_run() {
  if [ -n "${KE_DB_URL:-}" ]; then
    psql "$KE_DB_URL" -v ON_ERROR_STOP=1 "$@"
  else
    docker exec -i "$KE_CONTAINER" psql -U postgres -d "$KE_DBNAME" -v ON_ERROR_STOP=1 "$@"
  fi
}

lc() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
die() { printf 'error: %s\n' "$1" >&2; exit 1; }

# ---- args ----
ASSUME_YES=0
if [ "${1:-}" = "-y" ] || [ "${1:-}" = "--yes" ]; then ASSUME_YES=1; shift; fi
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi
if [ "$#" -ne 2 ]; then usage; exit 1; fi
REST="$1"
NEW_EMAIL="$2"

# ---- validate the new email shape (one @, a dot in the domain) ----
case "$NEW_EMAIL" in
  *@*.*) : ;;
  *) die "not a valid email address: $NEW_EMAIL" ;;
esac

# ---- connectivity check (clear message instead of a raw docker/psql error) ----
if ! printf 'SELECT 1;\n' | psql_run -At >/dev/null 2>&1; then
  if [ -n "${KE_DB_URL:-}" ]; then
    die "cannot reach the database at KE_DB_URL (is psql installed and the URL correct?)"
  else
    die "cannot reach docker container '$KE_CONTAINER' (is it running? set KE_DB_URL to target another DB)"
  fi
fi

# ---- resolve the restaurant -> owner (substring match, exact wins ties) ----
ids=(); names=(); owners=(); emails=()
while IFS=$'\t' read -r rid rname oid oemail; do
  [ -z "$rid" ] && continue
  ids+=("$rid"); names+=("$rname"); owners+=("$oid"); emails+=("$oemail")
done < <(printf '%s\n' \
  "SELECT r.id, r.name, u.id, u.email
     FROM restaurants r JOIN users u ON u.id = r.owner_id
    WHERE r.name ILIKE '%' || :'rname' || '%'
    ORDER BY r.name;" | psql_run -At -F $'\t' -v rname="$REST")

if [ "${#ids[@]}" -eq 0 ]; then
  die "no restaurant matches \"$REST\""
fi

if [ "${#ids[@]}" -gt 1 ]; then
  # Disambiguate by an exact (case-insensitive) name match.
  match=-1
  for i in "${!ids[@]}"; do
    if [ "$(lc "${names[$i]}")" = "$(lc "$REST")" ]; then
      if [ "$match" -ge 0 ]; then match=-1; break; fi
      match=$i
    fi
  done
  if [ "$match" -ge 0 ]; then
    ids=("${ids[$match]}"); names=("${names[$match]}")
    owners=("${owners[$match]}"); emails=("${emails[$match]}")
  else
    printf 'Multiple restaurants match "%s":\n' "$REST" >&2
    for n in "${names[@]}"; do printf '  - %s\n' "$n" >&2; done
    die "be more specific (use the exact name)"
  fi
fi

RID="${ids[0]}"; RNAME="${names[0]}"; OWNER="${owners[0]}"; CUR_EMAIL="${emails[0]}"

# ---- no-op if already set ----
if [ "$(lc "$CUR_EMAIL")" = "$(lc "$NEW_EMAIL")" ]; then
  printf 'Owner of "%s" is already %s — nothing to do.\n' "$RNAME" "$NEW_EMAIL"
  exit 0
fi

# ---- reject if the new email belongs to a different account (unique login) ----
taken=$(printf '%s\n' \
  "SELECT email FROM users
    WHERE lower(email) = lower(:'nemail') AND id <> :'owner'::uuid
    LIMIT 1;" | psql_run -At -v nemail="$NEW_EMAIL" -v owner="$OWNER")
if [ -n "$taken" ]; then
  die "$NEW_EMAIL already belongs to another account — pick a different address"
fi

# ---- confirm ----
printf '\n'
printf '  Restaurant : %s\n' "$RNAME"
printf '  Owner id   : %s\n' "$OWNER"
printf '  Email      : %s  ->  %s\n' "$CUR_EMAIL" "$NEW_EMAIL"
printf '\n'
if [ "$ASSUME_YES" -ne 1 ]; then
  printf 'Apply this email change? [y/N] '
  read -r ans
  case "$ans" in
    y|Y|yes|YES) : ;;
    *) echo "Aborted — no changes made."; exit 0 ;;
  esac
fi

# ---- apply ----
printf '%s\n' \
  "UPDATE users SET email = :'nemail' WHERE id = :'owner'::uuid;" \
  | psql_run -q -v nemail="$NEW_EMAIL" -v owner="$OWNER"

printf '\n✅ Updated. "%s" owner login is now %s\n' "$RNAME" "$NEW_EMAIL"
printf '   Next: have the owner open the seller app → "Forgot password?" to set\n'
printf '   their own password (a 6-digit code is sent to %s).\n' "$NEW_EMAIL"
