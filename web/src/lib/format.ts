// Shared money formatting for the consumer web app. Every money value in the
// system is integer cents — this module is the ONE place cents become display
// dollars (no inline `/ 100` math outside web/src/lib/).

/** 1234 → "$12.34" */
export function formatUSD(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

/** 1234 → "12.34" — plain dollars string for form inputs (no "$" sign). */
export function centsToDollars(cents: number): string {
  return (cents / 100).toFixed(2);
}

/**
 * Signed delta for modifier price adjustments:
 * 200 → "+$2.00", -150 → "−$1.50", 0 → "" (mirrors iOS
 * Modifier.priceDeltaFormatted).
 */
export function formatUSDDelta(cents: number): string {
  if (cents === 0) return "";
  return `${cents > 0 ? "+" : "−"}$${(Math.abs(cents) / 100).toFixed(2)}`;
}

/** percent% of a cents amount, rounded to whole cents (tip presets). */
export function percentOfCents(cents: number, percent: number): number {
  return Math.round((cents * percent) / 100);
}
