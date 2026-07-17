import { NextRequest, NextResponse } from "next/server";

// Server-side proxy for the US Census geocoder (free, no API key). The browser
// can't call it directly (no CORS on census.gov), but server-side there's no
// CORS to satisfy. Normalizes the Census response shape down to
// { lat, lng, matched } for AddressGeocodeField.
//
// Error contract:
//   400 { error: "missing_query" }        — ?q= absent or blank
//   404 { error: "address_not_found" }    — geocoder returned no match
//   502 { error: "geocoder_unavailable" } — upstream timeout / error / bad body
//
// Hits carry Cache-Control: public, max-age=86400 — a street address's
// coordinates don't move, so per-query URLs are safe to cache for a day.

// Reading request.nextUrl already opts this handler out of static
// optimization, but be explicit so a future refactor can't accidentally
// freeze one query's answer into the build.
export const dynamic = "force-dynamic";

const CENSUS_ONELINE_URL =
  "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress";
const UPSTREAM_TIMEOUT_MS = 8_000;

// The slice of the Census payload we consume. coordinates.y is latitude,
// coordinates.x is longitude (lon-first "x/y" convention).
interface CensusResponse {
  result?: {
    addressMatches?: Array<{
      matchedAddress?: string;
      coordinates?: { x?: number; y?: number };
    }>;
  };
}

export async function GET(request: NextRequest) {
  const q = request.nextUrl.searchParams.get("q")?.trim() ?? "";
  if (!q) {
    return NextResponse.json({ error: "missing_query" }, { status: 400 });
  }

  const upstreamUrl =
    `${CENSUS_ONELINE_URL}?address=${encodeURIComponent(q)}` +
    `&benchmark=Public_AR_Current&format=json`;

  let data: CensusResponse;
  try {
    const upstream = await fetch(upstreamUrl, {
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      // Next's fetch cache must not hold upstream bodies server-side — the
      // browser-facing Cache-Control below is the caching story here.
      cache: "no-store",
    });
    if (!upstream.ok) {
      return NextResponse.json({ error: "geocoder_unavailable" }, { status: 502 });
    }
    // Census returns HTML error pages on some failures — a non-JSON body is
    // an upstream failure, not a "no match".
    data = (await upstream.json()) as CensusResponse;
  } catch {
    // Timeout (AbortSignal), network error, or JSON parse failure.
    return NextResponse.json({ error: "geocoder_unavailable" }, { status: 502 });
  }

  const match = data.result?.addressMatches?.[0];
  const lat = match?.coordinates?.y;
  const lng = match?.coordinates?.x;
  if (!match || typeof lat !== "number" || typeof lng !== "number") {
    return NextResponse.json({ error: "address_not_found" }, { status: 404 });
  }

  return NextResponse.json(
    { lat, lng, matched: match.matchedAddress ?? q },
    { headers: { "Cache-Control": "public, max-age=86400" } }
  );
}
