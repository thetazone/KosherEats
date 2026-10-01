package config

import "testing"

func TestTaxOnNYCRate(t *testing.T) {
	c := &Config{TaxRatePPM: defaultTaxRatePPM}
	cases := []struct{ cents, want int }{
		{0, 0},
		{-500, 0},
		{1, 0},        // 0.08875¢ rounds down
		{6, 1},        // 0.5325¢ rounds up
		{1000, 89},    // $10.00 → 88.75¢ → 89¢
		{2000, 178},   // $20.00 → 177.5¢ → 178¢ (half up)
		{4000, 355},   // $40.00 → 355¢ exactly
		{12345, 1096}, // $123.45 → 1095.6¢ → 1096¢
	}
	for _, tc := range cases {
		if got := c.TaxOn(tc.cents); got != tc.want {
			t.Errorf("TaxOn(%d) = %d, want %d", tc.cents, got, tc.want)
		}
	}
}

func TestGetEnvPercentPPM(t *testing.T) {
	cases := []struct {
		val  string
		want int
	}{
		{"", defaultTaxRatePPM},
		{"8.875", 88_750},
		{" 8.875% ", 88_750},
		{"9", 90_000},
		{"0", 0},
		{"abc", defaultTaxRatePPM},
		{"-1", defaultTaxRatePPM},
		{"30", defaultTaxRatePPM},
	}
	for _, tc := range cases {
		t.Setenv("TAX_RATE_PERCENT", tc.val)
		if got := getEnvPercentPPM("TAX_RATE_PERCENT", defaultTaxRatePPM); got != tc.want {
			t.Errorf("TAX_RATE_PERCENT=%q → %d ppm, want %d", tc.val, got, tc.want)
		}
	}
}
