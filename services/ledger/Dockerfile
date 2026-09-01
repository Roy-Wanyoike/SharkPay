# SharkPay ledger service — multi-stage build.
# Build:  docker build -t sharkpay/ledger .
# Run:    docker run -e DATABASE_URL=postgres://... -e LISTEN_ADDR=:8080 sharkpay/ledger
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/ledger ./cmd/server

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/ledger /ledger
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/ledger"]
