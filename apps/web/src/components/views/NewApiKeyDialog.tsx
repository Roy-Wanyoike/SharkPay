"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Input } from "@/components/ui/Input";
import { Select } from "@/components/ui/Select";
import { useToast } from "@/components/ui/Toast";

const SCOPES = [
  "payments:read",
  "payments:write",
  "payouts:read",
  "payouts:write",
  "wallets:read",
  "webhooks:manage",
] as const;

/**
 * API key creation dialog. The typed createApiKey SDK call is ready; there
 * is no merged api-keys contract yet, so the handler explains that instead
 * of pretending. When the contract lands, this dialog calls the SDK and the
 * secret is displayed exactly once (ApiError envelope handles conflicts).
 */
export function NewApiKeyDialog() {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [environment, setEnvironment] = useState<"test" | "live">("test");
  const toast = useToast();

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (name.trim().length < 3) {
      toast.push({
        variant: "danger",
        title: "Name too short",
        description: "Give the key a recognisable name (3+ characters).",
      });
      return;
    }
    toast.push({
      variant: "info",
      title: "Key creation pending the api-keys contract",
      description: `“${name.trim()}” (${environment}) is valid — POST /api-keys lands with the merged contract (sp_test_/sp_live_ prefixes).`,
    });
    setOpen(false);
    setName("");
  };

  return (
    <>
      <Button variant="primary" icon="keys" onClick={() => setOpen(true)}>
        Create key
      </Button>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Create API key"
        description="Scoped programmatic access. The secret is shown once at creation."
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" form="api-key-form">
              Create key
            </Button>
          </>
        }
      >
        <form id="api-key-form" onSubmit={onSubmit} className="space-y-4">
          <Input
            label="Key name"
            name="name"
            required
            placeholder="Ledger automation"
            value={name}
            onChange={(event) => setName(event.target.value)}
            hint="Shown in lists and audit events."
          />
          <Select
            label="Environment"
            name="environment"
            value={environment}
            onChange={(event) => setEnvironment(event.target.value === "live" ? "live" : "test")}
          >
            <option value="test">test (sp_test_…)</option>
            <option value="live">live (sp_live_…)</option>
          </Select>
          <fieldset className="space-y-2">
            <legend className="text-xs font-medium text-fg-muted">Scopes</legend>
            <div className="grid grid-cols-2 gap-2">
              {SCOPES.map((scope) => (
                <label
                  key={scope}
                  className="flex items-center gap-2 rounded-lg border border-border-subtle bg-surface-2 px-3 py-2 text-xs text-fg"
                >
                  <input type="checkbox" name="scopes" value={scope} className="accent-[var(--sp-accent)]" />
                  <span className="font-mono">{scope}</span>
                </label>
              ))}
            </div>
          </fieldset>
        </form>
      </Dialog>
    </>
  );
}
