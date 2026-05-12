package pos

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"io"
	"os"
)

// AES-GCM encryption for POS OAuth tokens before they hit the database.
// Key comes from POS_ENCRYPTION_KEY env var as a base64-encoded 32-byte value.
// Empty key disables encryption — tokens are stored plain. That's OK in dev
// because there's no real merchant secret to protect, but in prod a missing
// key is a configuration error and writes will fail.

var errKeyMissing = errors.New("pos: POS_ENCRYPTION_KEY not set")

// loadKey reads POS_ENCRYPTION_KEY and returns 32 raw bytes. Returns
// errKeyMissing when unset so callers can distinguish "no key" from "bad
// key" and decide whether to soft-fail (dev) or hard-fail (prod).
func loadKey() ([]byte, error) {
	raw := os.Getenv("POS_ENCRYPTION_KEY")
	if raw == "" {
		return nil, errKeyMissing
	}
	key, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return nil, errors.New("pos: POS_ENCRYPTION_KEY is not valid base64")
	}
	if len(key) != 32 {
		return nil, errors.New("pos: POS_ENCRYPTION_KEY must decode to 32 bytes")
	}
	return key, nil
}

// Encrypt returns ciphertext = nonce || aes-gcm(plaintext). The nonce is
// prepended so Decrypt can read it back without separate storage. Returns
// the plaintext unchanged when POS_ENCRYPTION_KEY is empty (dev mode).
func Encrypt(plaintext []byte) ([]byte, error) {
	key, err := loadKey()
	if errors.Is(err, errKeyMissing) {
		return plaintext, nil
	}
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	return gcm.Seal(nonce, nonce, plaintext, nil), nil
}

// Decrypt reverses Encrypt. When POS_ENCRYPTION_KEY is empty, returns the
// input unchanged — the symmetric dev-mode counterpart to Encrypt's no-op.
func Decrypt(ciphertext []byte) ([]byte, error) {
	key, err := loadKey()
	if errors.Is(err, errKeyMissing) {
		return ciphertext, nil
	}
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(ciphertext) < gcm.NonceSize() {
		return nil, errors.New("pos: ciphertext too short")
	}
	nonce, body := ciphertext[:gcm.NonceSize()], ciphertext[gcm.NonceSize():]
	return gcm.Open(nil, nonce, body, nil)
}
