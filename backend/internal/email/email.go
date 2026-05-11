// Package email wraps SMTP for outbound transactional mail. Currently used
// for seller-approval notifications (admin gets the new-application alert,
// the seller gets the decision). Reads credentials from environment so
// production and local development can use different mailboxes.
//
// Dev stub mode: if SMTP_HOST is empty, Send() no-ops and logs the message
// body so local tests don't bounce mail.
package email

import (
	"bytes"
	"fmt"
	"log"
	"net/smtp"
	"os"
	"strings"
	"time"
)

type Client struct {
	host    string
	port    string
	user    string
	pass    string
	from    string
	enabled bool
}

func New() *Client {
	c := &Client{
		host: os.Getenv("SMTP_HOST"),
		port: os.Getenv("SMTP_PORT"),
		user: os.Getenv("SMTP_USER"),
		pass: os.Getenv("SMTP_PASS"),
		from: os.Getenv("SMTP_FROM"),
	}
	if c.port == "" {
		c.port = "587"
	}
	if c.from == "" {
		c.from = c.user
	}
	if c.host != "" && c.user != "" && c.pass != "" && c.from != "" {
		c.enabled = true
	} else {
		log.Println("[email] SMTP env vars missing — running in stub mode (no real mail will be sent)")
	}
	return c
}

// Send delivers an HTML email with a plain-text fallback. Returns nil on
// success or when running in stub mode; otherwise the SMTP error so the
// caller can surface a 5xx.
func (c *Client) Send(to, subject, textBody, htmlBody string) error {
	if !c.enabled {
		log.Printf("[email stub] to=%s subject=%q\n--text--\n%s\n--html--\n%s", to, subject, textBody, htmlBody)
		return nil
	}

	boundary := fmt.Sprintf("ke_boundary_%d", time.Now().UnixNano())
	var msg bytes.Buffer
	msg.WriteString(fmt.Sprintf("From: %s\r\n", c.from))
	msg.WriteString(fmt.Sprintf("To: %s\r\n", to))
	msg.WriteString(fmt.Sprintf("Subject: %s\r\n", subject))
	msg.WriteString("MIME-Version: 1.0\r\n")
	msg.WriteString(fmt.Sprintf("Content-Type: multipart/alternative; boundary=%q\r\n\r\n", boundary))

	msg.WriteString(fmt.Sprintf("--%s\r\n", boundary))
	msg.WriteString("Content-Type: text/plain; charset=\"UTF-8\"\r\n\r\n")
	msg.WriteString(textBody)
	msg.WriteString("\r\n\r\n")

	msg.WriteString(fmt.Sprintf("--%s\r\n", boundary))
	msg.WriteString("Content-Type: text/html; charset=\"UTF-8\"\r\n\r\n")
	msg.WriteString(htmlBody)
	msg.WriteString("\r\n\r\n")

	msg.WriteString(fmt.Sprintf("--%s--\r\n", boundary))

	auth := smtp.PlainAuth("", c.user, c.pass, c.host)
	addr := c.host + ":" + c.port
	recipients := []string{strings.TrimSpace(to)}
	if err := smtp.SendMail(addr, auth, c.from, recipients, msg.Bytes()); err != nil {
		return fmt.Errorf("smtp send: %w", err)
	}
	log.Printf("[email] sent to=%s subject=%q", to, subject)
	return nil
}

// AdminEmail returns the address that receives admin notifications
// (e.g. seller-approval alerts). Defaults to the SMTP_FROM if unset.
func (c *Client) AdminEmail() string {
	if v := os.Getenv("ADMIN_EMAIL"); v != "" {
		return v
	}
	return c.from
}

// Enabled reports whether real mail will be sent. Useful for tests / local
// dev where the caller may want to skip composing expensive templates.
func (c *Client) Enabled() bool { return c.enabled }
