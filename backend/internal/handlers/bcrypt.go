package handlers

import "golang.org/x/crypto/bcrypt"

// bcryptHash wraps the default bcrypt hash so admin + register handlers
// use the same cost factor. Extracted as a helper to keep tests simple.
func bcryptHash(password string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(b), nil
}
