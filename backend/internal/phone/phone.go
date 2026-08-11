// Package phone normalizes loosely-formatted phone numbers into the E.164 form
// courier providers require.
package phone

import "strings"

// ToE164 converts a North American phone number into E.164 ("+15551234567").
//
// Couriers are strict where our own storage is not: a seller who typed
// "9178130167" in Settings is stored verbatim, and DoorDash answers
// 400 "Unknown phone number format. Make sure the phone number is valid and
// belongs to the same country as the address." Uber likewise requires
// pickup_phone_number on create. Because the quote succeeds without a phone but
// the create does not, a malformed number strands the order at ready *after*
// the consumer has been charged — so normalize at the provider edge, where no
// call site can forget.
//
// Formatting (spaces, parens, dashes, dots) is dropped. A bare 10-digit number
// gains the US "+1"; 11 digits starting with "1" gain "+". An input that
// already carries "+" keeps its country code untouched. Empty in, empty out —
// callers that legitimately have no phone still send nothing rather than a
// bogus "+".
func ToE164(s string) string {
	s = strings.TrimSpace(s)
	hasPlus := strings.HasPrefix(s, "+")

	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	digits := b.String()

	switch {
	case digits == "":
		return ""
	case hasPlus:
		// Already international — trust the caller's country code.
		return "+" + digits
	case len(digits) == 10:
		return "+1" + digits
	case len(digits) == 11 && digits[0] == '1':
		return "+" + digits
	default:
		// Unrecognized length: still emit a "+" so the provider sees a
		// well-formed-looking value and rejects it loudly, rather than us
		// silently sending a bare string it misreads.
		return "+" + digits
	}
}
