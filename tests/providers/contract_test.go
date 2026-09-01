package conformance

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"reflect"
	"regexp"
	"testing"

	"github.com/Roy-Wanyoike/SharkPay/services/providers"
)

// wireMockDir is the dev/CI stub directory this harness must stay
// structurally aligned with.
const wireMockDir = "../wiremock/mappings"

// wiremockMapping is the subset of the WireMock mapping schema this suite
// pins (request matchers + canned response).
type wiremockMapping struct {
	Name    string `json:"name"`
	Request struct {
		Method         string `json:"method"`
		URLPath        string `json:"urlPath"`
		URLPathPattern string `json:"urlPathPattern"`
	} `json:"request"`
	Response struct {
		Status   int               `json:"status"`
		JSONBody map[string]any    `json:"jsonBody"`
		Headers  map[string]string `json:"headers"`
	} `json:"response"`
}

// loadMapping reads a WireMock mapping, keeping numbers as json.Number so
// the drift comparison is float-free.
func loadMapping(t *testing.T, file string) wiremockMapping {
	t.Helper()
	raw, err := os.ReadFile(file)
	if err != nil {
		t.Fatalf("cannot read WireMock mapping %s (the conformance harness validates itself against it): %v", file, err)
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var m wiremockMapping
	if err := dec.Decode(&m); err != nil {
		t.Fatalf("WireMock mapping %s is not valid JSON: %v", file, err)
	}
	return m
}

// decodeBodyUseNumber parses a response body with json.Number semantics.
func decodeBodyUseNumber(t *testing.T, body []byte, context string) map[string]any {
	t.Helper()
	doc, err := decodeUseNumber(body)
	if err != nil {
		t.Fatalf("%s: body is not the contract JSON: %v (body %s)", context, err, body)
	}
	return doc
}

// wireContractScenarios pin the fake against the ACTUAL WireMock mapping
// files: same paths, methods, statuses, header and body shapes. If a
// mapping changes without the fake (or vice versa) these fail loudly, so
// dev/CI stubs and the conformance stubs can never drift apart.
var wireContractScenarios = []scenario{
	{
		name: "mapping-initiate-response-matches-fake-exactly",
		doc:  "POST /v1/transfers on a fresh fake must answer 202 with EXACTLY the jsonBody of honeycoin-initiate-transfer.json (same keys, same values, integer literals) and the mapping's Content-Type header.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{})
			t.Cleanup(fake.Close)
			mapping := loadMapping(t, wireMockDir+"/honeycoin-initiate-transfer.json")

			body := []byte(`{"amount_minor":150000,"currency":"KES","exponent":2,"rail":"honeycoin","destination":{"type":"msisdn","details":{"msisdn":"+254700000001"}}}`)
			resp, err := fake.DoSigned("POST", PathTransfers, body, "drift-initiate")
			if err != nil {
				t.Fatalf("signed initiate failed: %v", err)
			}
			if resp.StatusCode != mapping.Response.Status {
				t.Fatalf("initiate status drift: fake = HTTP %d, mapping %s = HTTP %d", resp.StatusCode, mapping.Name, mapping.Response.Status)
			}
			if got, want := resp.Header.Get("Content-Type"), mapping.Response.Headers["Content-Type"]; got != want {
				t.Fatalf("initiate Content-Type drift: fake = %q, mapping = %q", got, want)
			}
			gotBody := decodeBodyUseNumber(t, resp.Body, "fake initiate response")
			wantBody := mapping.Response.JSONBody
			if !reflect.DeepEqual(gotBody, wantBody) {
				t.Fatalf("initiate response body drift:\n  fake    = %v\n  mapping = %v\n(the conformance fake and tests/wiremock/mappings/%s.json must agree byte-for-byte on the canned shape)", gotBody, wantBody, "honeycoin-initiate-transfer")
			}
		},
	},
	{
		name: "mapping-transfer-status-response-matches-fake-exactly",
		doc:  "GET /v1/transfers/{ref} for the canned transfer in CONFIRMED state must answer 200 with EXACTLY the jsonBody of honeycoin-transfer-status.json.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{})
			t.Cleanup(fake.Close)
			mapping := loadMapping(t, wireMockDir+"/honeycoin-transfer-status.json")

			init, err := fake.DoSigned("POST", PathTransfers,
				[]byte(`{"amount_minor":150000,"currency":"KES","exponent":2,"rail":"honeycoin","destination":{"type":"msisdn"}}`), "drift-status")
			if err != nil || init.StatusCode != 202 {
				t.Fatalf("setup initiate failed: status=%d err=%v", init.StatusCode, err)
			}
			if ok := fake.SetTransferStatus(CannedTransferID, CannedPollStatus); !ok {
				t.Fatalf("canned transfer %s missing from the fake", CannedTransferID)
			}
			resp, err := fake.DoSigned("GET", PathTransfers+"/"+CannedTransferID, nil, "")
			if err != nil {
				t.Fatalf("signed status poll failed: %v", err)
			}
			if resp.StatusCode != mapping.Response.Status {
				t.Fatalf("status poll status drift: fake = HTTP %d, mapping %s = HTTP %d", resp.StatusCode, mapping.Name, mapping.Response.Status)
			}
			gotBody := decodeBodyUseNumber(t, resp.Body, "fake status response")
			if !reflect.DeepEqual(gotBody, mapping.Response.JSONBody) {
				t.Fatalf("status response body drift:\n  fake    = %v\n  mapping = %v", gotBody, mapping.Response.JSONBody)
			}
		},
	},
	{
		name: "mapping-endpoint-paths-and-methods-match-fake-routes",
		doc:  "The mappings' request matchers (POST /v1/transfers, GET /v1/transfers/[^/]+) must be exactly the endpoints the fake (and therefore the adapter) speaks — no extra or renamed routes.",
		run: func(t *testing.T) {
			fake := NewFakeHoneyCoin(ServerConfig{})
			t.Cleanup(fake.Close)
			initMapping := loadMapping(t, wireMockDir+"/honeycoin-initiate-transfer.json")
			statusMapping := loadMapping(t, wireMockDir+"/honeycoin-transfer-status.json")

			if initMapping.Request.Method != "POST" || initMapping.Request.URLPath != PathTransfers {
				t.Fatalf("initiate mapping matcher = %s %s, want POST %s (the fake's route)", initMapping.Request.Method, initMapping.Request.URLPath, PathTransfers)
			}
			if statusMapping.Request.Method != "GET" {
				t.Fatalf("status mapping method = %s, want GET", statusMapping.Request.Method)
			}
			pattern := regexp.MustCompile(statusMapping.Request.URLPathPattern)
			if !pattern.MatchString(PathTransfers + "/" + CannedTransferID) {
				t.Fatalf("status mapping pattern %s does not match the fake's GET path %s", statusMapping.Request.URLPathPattern, PathTransfers+"/"+CannedTransferID)
			}
			// The observed traffic must match the mapping matchers.
			if _, err := fake.DoSigned("POST", PathTransfers, []byte(`{"amount_minor":150000,"currency":"KES","exponent":2}`), "drift-routes"); err != nil {
				t.Fatalf("signed initiate failed: %v", err)
			}
			reqs := fake.RecordedRequests()
			if len(reqs) != 1 || reqs[0].Method != initMapping.Request.Method || reqs[0].Path != initMapping.Request.URLPath {
				t.Fatalf("observed initiate traffic %s %s does not match mapping matcher %s %s", reqs[0].Method, reqs[0].Path, initMapping.Request.Method, initMapping.Request.URLPath)
			}
		},
	},
	{
		name: "mapping-contract-e2e-through-the-real-adapter",
		doc:  "End-to-end tie: the real adapter initiates against the fake and receives the mapping's canned id (hct_stub_000001); polling the CONFIRMED state maps to SUCCEEDED — mapping stub, conformance fake and adapter all speak one contract.",
		run: func(t *testing.T) {
			env := newEnv(t)
			ref := mustInitiate(t, env, "tx-mapping-e2e")
			if ref.Ref != CannedTransferID {
				t.Fatalf("adapter received ref %q, want the mapping's canned id %q (dev/CI WireMock stubs and this fake must hand out the same identity)", ref.Ref, CannedTransferID)
			}
			if ok := env.Fake.SetTransferStatus(ref.Ref, CannedPollStatus); !ok {
				t.Fatalf("fake lost transfer %q", ref.Ref)
			}
			st, err := env.Adapter.Poll(context.Background(), ref)
			if err != nil || st != providers.StatusSucceeded {
				t.Fatalf("poll of the mapping's CONFIRMED status must map to SUCCEEDED, got %s (err %v)", st, err)
			}
		},
	},
}

func TestWireContractDriftScenarios(t *testing.T) {
	runScenarios(t, wireContractScenarios)
}
