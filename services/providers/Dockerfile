# SharkPay provider gateway (services/providers).
# Multi-stage build; run with:
#   docker build -t sharkpay/providers ./services/providers
#   docker run -e HONEYCOIN_BASE_URL=... -e HONEYCOIN_SIGNING_KEY=... -p 8080:8080 sharkpay/providers
#
# NOTE: Docker is not available in the current sandbox — this file follows
# the standard multi-stage pattern and is exercised in CI (wiremock-based
# integration job) rather than locally.

FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
    -o /out/provider-gateway ./cmd/server

FROM alpine:3.20
RUN adduser -D -u 10001 shark && apk add --no-cache ca-certificates
USER shark
COPY --from=build /out/provider-gateway /usr/local/bin/provider-gateway
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["provider-gateway"]
