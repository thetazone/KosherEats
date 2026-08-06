// Certification strings come from merchant onboarding and may hold the
// importer's placeholder ("TBD - to be provided by merchant") until the
// merchant supplies the real hashgacha. Never show the raw placeholder —
// callers render a neutral "Cert pending" treatment when this returns null.
export function certLabel(cert: string | null | undefined): string | null {
  if (!cert) return null;
  const trimmed = cert.trim();
  if (!trimmed || trimmed.toUpperCase().startsWith("TBD")) return null;
  return trimmed;
}
