// Kosher trust guards.
//
// Kosher certification is KosherEats' core trust differentiator: a heksher
// badge and a viewable certificate photo are the signals a user relies on to
// decide a restaurant is genuinely kosher. Some restaurant records carry
// PLACEHOLDER data instead of a real certification — a literal
// "TBD - to be provided by merchant" string, or a stock-image URL from a
// placeholder service like placehold.co. Rendered naively, that placeholder
// masquerades as a verified heksher, which is worse than showing nothing.
//
// These pure helpers detect the obvious placeholders so the UI can degrade
// gracefully (a muted "Certification pending" chip, no broken "View
// Certificate" button) instead of presenting fake trust signals.
//
// SCOPE: this guards DATA SHAPE only — a missing/placeholder string, a known
// placeholder host. It deliberately does NOT judge image CONTENT. A real
// upload host serving the wrong photo (e.g. a nature shot instead of a
// certificate) still passes hasRealCertificatePhoto — that is a data-quality
// problem for the merchant/admin to fix, not something a URL can detect.
// When in doubt, these helpers treat a value as REAL: over-hiding a
// legitimate certification is a worse failure than showing a generic one.

// Case-insensitive exact matches that mean "no real certification".
const PLACEHOLDER_EXACT = new Set([
  "",
  "tbd",
  "pending",
  "n/a",
  "na",
  "none",
  "unknown",
  "-",
  "—",
]);

// Substrings that, when present, mark the value as a placeholder regardless of
// surrounding text (e.g. "TBD - to be provided by merchant").
const PLACEHOLDER_SUBSTRINGS = ["to be provided", "to be determined"];

/**
 * True when a certification value is missing or an obvious placeholder.
 * Matches case-insensitively on the trimmed value. Real agency names (OU, OK,
 * Kof-K, the generic-but-legitimate "Kosher", any substantive free text) are
 * NOT flagged — when in doubt this returns false.
 */
export function isPlaceholderCertification(
  cert: string | null | undefined
): boolean {
  if (cert == null) return true;
  const normalized = cert.trim().toLowerCase();
  if (PLACEHOLDER_EXACT.has(normalized)) return true;
  return PLACEHOLDER_SUBSTRINGS.some((s) => normalized.includes(s));
}

/**
 * The certification string to display, or "Certification pending" when the
 * value is missing/placeholder so a placeholder string never renders as a
 * badge.
 */
export function certificationLabel(cert: string | null | undefined): string {
  return isPlaceholderCertification(cert) ? "Certification pending" : cert!.trim();
}

// Hostnames of known placeholder / stock-image services. A certificate served
// from one of these is not a real uploaded document. Matched against the URL's
// hostname (and any subdomain of these) case-insensitively.
const PLACEHOLDER_IMAGE_HOSTS = [
  "placehold.co",
  "placeholder.com",
  "via.placeholder.com",
  "dummyimage.com",
  "images.unsplash.com",
  "source.unsplash.com",
  "loremflickr.com",
  "picsum.photos",
  "example.com",
];

function isPlaceholderImageHost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  return PLACEHOLDER_IMAGE_HOSTS.some(
    (blocked) => host === blocked || host.endsWith(`.${blocked}`)
  );
}

/**
 * True when a certificate URL points at a real uploaded document. False when
 * the URL is empty or its host is a known placeholder/stock-image service.
 * Real upload hosts (*.r2.dev, *.s3.amazonaws.com, *.cloudfront.net, …) and
 * any other real-looking https URL return true. An unparseable URL returns
 * false. Does NOT inspect image content — a real host with a wrong photo
 * still returns true (a data problem, not this guard's job).
 */
export function hasRealCertificatePhoto(
  url: string | null | undefined
): boolean {
  if (url == null || url.trim() === "") return false;
  try {
    const { hostname } = new URL(url.trim());
    return !isPlaceholderImageHost(hostname);
  } catch {
    return false;
  }
}
