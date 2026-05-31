// Package ctxkeys defines context-key constants shared between handlers and
// middleware without creating an import cycle.
package ctxkeys

type Key string

const UserKey Key = "user"
