# SharkPay monorepo Makefile. Requires Go >= 1.24 (services/ledger/go.mod)
# and Docker with the compose plugin for the dev stack.

GO ?= go
MODULES := packages/go/money services/ledger services/providers

.PHONY: test vet lint fmt tidy compose-up compose-down compose-ps clean help

help:
	@echo "targets: test vet lint fmt tidy compose-up compose-down clean"

## test: run all module tests with coverage
test:
	@for m in $(MODULES); do echo "== $$m"; (cd $$m && $(GO) test ./... -cover) || exit 1; done

## vet: static analysis across all modules
vet:
	@for m in $(MODULES); do echo "== $$m"; (cd $$m && $(GO) vet ./...) || exit 1; done

## lint: gofmt check (fails on unformatted files)
lint:
	@out=$$(gofmt -l packages services); if [ -n "$$out" ]; then \
		echo "gofmt needed on:"; echo "$$out"; exit 1; fi

## fmt: rewrite formatting
fmt:
	gofmt -w packages services

## tidy: go mod tidy for every module
tidy:
	@for m in $(MODULES); do (cd $$m && $(GO) mod tidy); done

## compose-up / compose-down: local dev stack (postgres, redpanda,
## temporal, wiremock, ledger, providers) — see docker-compose.yml
compose-up:
	docker compose up -d
	@echo "ledger: http://localhost:8090 · providers: http://localhost:8091 · wiremock: http://localhost:8081 · temporal-ui: http://localhost:8181"

compose-down:
	docker compose down -v

compose-ps:
	docker compose ps

clean:
	@for m in $(MODULES); do (cd $$m && $(GO) clean -testcache); done
