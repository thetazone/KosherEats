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
