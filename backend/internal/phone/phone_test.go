package phone

import "testing"

func TestToE164(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		// The case that stranded order 356a73e9: a seller-typed bare 10-digit
		// number that DoorDash rejected with "Unknown phone number format".
		{"bare ten digits", "9178130167", "+19178130167"},
		{"formatted us", "(917) 813-0167", "+19178130167"},
		{"dashed us", "917-813-0167", "+19178130167"},
		{"dotted us", "917.813.0167", "+19178130167"},
		{"spaced us", " 917 813 0167 ", "+19178130167"},
		{"leading one", "19178130167", "+19178130167"},
		{"already e164", "+19178130167", "+19178130167"},
		{"already e164 formatted", "+1 (917) 813-0167", "+19178130167"},
		{"non us keeps country code", "+442071838750", "+442071838750"},
		{"empty", "", ""},
		{"whitespace only", "   ", ""},
		{"letters only", "call us", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := ToE164(tt.in); got != tt.want {
				t.Errorf("ToE164(%q) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}

// A number already in E.164 must survive a second pass unchanged — the
// normalizer runs at the provider edge and callers may have normalized already.
func TestToE164Idempotent(t *testing.T) {
	for _, in := range []string{"9178130167", "+19178130167", "+442071838750", ""} {
		once := ToE164(in)
		if twice := ToE164(once); twice != once {
			t.Errorf("ToE164 not idempotent for %q: %q then %q", in, once, twice)
		}
	}
}

// ToE164 runs at the provider edge on values typed by sellers into Settings and
// by consumers at signup, so it sees far more than clean 10-digit input. These
// cases pin what the providers actually receive.
func TestToE164EdgeCases(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		// Punctuation and unicode a real form submission can carry.
		{"unicode dashes", "917–813—0167", "+19178130167"},
		{"parens and slashes", "(917)/813/0167", "+19178130167"},
		{"tel uri", "tel:+19178130167", "+19178130167"},
		{"trailing period", "917-813-0167.", "+19178130167"},

		// An extension is digits too, so it lengthens the number past 11 and
		// lands in the unrecognized-length branch: no "+1" is added and the
		// provider rejects the value loudly. That is deliberate — truncating to a
		// wrong-but-plausible number would dispatch a courier to call a stranger.
		{"extension lands in the unrecognized-length branch", "917-813-0167 x42", "+917813016742"},

		// Wrong lengths still get a "+" so the provider's error names a phone
		// problem rather than us sending a bare string it misparses.
		{"too short", "5551234", "+5551234"},
		{"seven digits is not assumed local", "813-0167", "+8130167"},
		{"too long", "191781301670000", "+191781301670000"},

		// A leading "+" is authoritative: never re-apply the US country code.
		{"plus with a ten digit body keeps the caller's intent", "+9178130167", "+9178130167"},
		{"plus israel", "+972501234567", "+972501234567"},
		{"plus uk with spaces", "+44 20 7183 8750", "+442071838750"},

		// Empty in, empty out — a caller with genuinely no phone must send
		// nothing rather than a bogus "+".
		{"plus only", "+", ""},
		{"punctuation only", "()-. ", ""},
		{"plus and punctuation only", "+()", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := ToE164(tt.in); got != tt.want {
				t.Errorf("ToE164(%q) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}

// The output must always be either empty or "+" followed by digits only —
// anything else is a payload the courier APIs reject outright.
func TestToE164OutputShape(t *testing.T) {
	inputs := []string{
		"9178130167", "(917) 813-0167", "+44 20 7183 8750", "call 917-813-0167 now",
		"tel:+19178130167", "917.813.0167 ext 9", "", "   ", "abc", "+", "0000000000",
	}
	for _, in := range inputs {
		got := ToE164(in)
		if got == "" {
			continue
		}
		if got[0] != '+' {
			t.Errorf("ToE164(%q) = %q, want a leading +", in, got)
			continue
		}
		if len(got) == 1 {
			t.Errorf("ToE164(%q) = %q, a bare + is never a valid phone", in, got)
			continue
		}
		for i, r := range got[1:] {
			if r < '0' || r > '9' {
				t.Errorf("ToE164(%q) = %q has a non-digit %q at position %d", in, got, r, i+1)
				break
			}
		}
	}
}

// The empty return is now load-bearing beyond this package: dispatch's
// pre-flight check (missingRequiredPhone) treats ToE164(x) == "" as "this order
// has no usable phone" and fails the dispatch permanently BEFORE any paid
// provider call. That contract only holds if empty means exactly "no digits to
// send" — never a value a provider could have accepted.
//
// So: every input with at least one digit must produce something non-empty (we
// send it and let the provider judge), and every input with none must produce
// exactly "" (we refuse locally rather than send an empty phone field).
func TestToE164EmptyMeansNoDigits(t *testing.T) {
	hasDigit := func(s string) bool {
		for _, r := range s {
			if r >= '0' && r <= '9' {
				return true
			}
		}
		return false
	}

	inputs := []string{
		"9178130167", "(917) 813-0167", "+442071838750", "1", "0", "5551234",
		"917.813.0167 ext 9", "tel:+19178130167", "191781301670000",
		"", "   ", "abc", "N/A", "n/a", "none", "call us", "+", "+()", "()-. ",
		"TBD", "--", "unknown",
	}
	for _, in := range inputs {
		got := ToE164(in)
		switch {
		case hasDigit(in) && got == "":
			t.Errorf("ToE164(%q) = \"\" but the input has digits — dispatch would refuse a "+
				"phone the provider might have accepted", in)
		case !hasDigit(in) && got != "":
			t.Errorf("ToE164(%q) = %q but the input has no digits — dispatch would pass this "+
				"to a provider as an empty phone field and eat a 400 on create", in, got)
		}
	}
}
