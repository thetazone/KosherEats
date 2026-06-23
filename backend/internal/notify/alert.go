package notify

import "log/slog"

// Alerter sends operational anomaly alerts (charge disputes, refunds,
// auto-refunds, permanently failed payouts) to a single admin address.
//
// It is deliberately decoupled from the email package: it depends only on the
// small emailSender interface below, which *email.Client satisfies. That keeps
// the notify package free of an email import (and any cycle risk) and makes
// Alerter trivial to fake in tests.
//
// When the admin address is empty OR no sender is wired, Alert is a no-op that
// still logs at WARN — so anomalies remain visible in logs even with mail off,
// and dev/test never attempts to send real mail.
type Alerter struct {
	to     string
	sender emailSender
}

// emailSender is the subset of *email.Client that Alerter needs. Matching the
// existing Send(to, subject, textBody, htmlBody string) error signature.
type emailSender interface {
	Send(to, subject, textBody, htmlBody string) error
}

// NewAlerter builds an Alerter. Pass cfg.AdminAlertEmail as to and the
// handler's *email.Client as sender. A nil sender or empty to yields a
// log-only Alerter (safe default for dev/test).
func NewAlerter(to string, sender emailSender) *Alerter {
	return &Alerter{to: to, sender: sender}
}

// Alert emails the admin with subject/body. It always logs the alert (at WARN)
// regardless of whether mail is sent, so the signal survives even in no-op
// mode. Mail-send errors are logged, not returned — an alert failing to send
// must never break the money-critical path that raised it.
func (a *Alerter) Alert(subject, body string) {
	if a == nil || a.to == "" || a.sender == nil {
		slog.Warn("admin-alert (mail disabled)",
			slog.String("subject", subject),
			slog.String("body", body))
		return
	}
	slog.Warn("admin-alert",
		slog.String("to", a.to),
		slog.String("subject", subject),
		slog.String("body", body))
	if err := a.sender.Send(a.to, subject, body, body); err != nil {
		slog.Error("admin-alert: send failed",
			slog.String("to", a.to),
			slog.String("subject", subject),
			slog.String("error", err.Error()))
	}
}
