FROM golang:1.25-alpine AS builder

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /koshereats-api ./cmd/api

FROM alpine:3.19
RUN apk --no-cache add ca-certificates
WORKDIR /app
COPY --from=builder /koshereats-api .
# Copy migrations so the auto-migrate-on-boot runner in main.go can find them.
# Path must match `internal/database/migrations` relative to the runtime cwd.
COPY --from=builder /app/internal/database/migrations ./internal/database/migrations
EXPOSE 8080
CMD ["./koshereats-api"]
