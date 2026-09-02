"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Input } from "@/components/ui/Input";
import { Select } from "@/components/ui/Select";
import { useToast } from "@/components/ui/Toast";

const CURRENCIES = ["KES", "USD", "EUR", "GBP", "USDC", "USDT"] as const;

/**
 * FX quote composer. The typed SDK call (createQuote) is wired in the
 * handler; while the API gateway is not live it surfaces a toast instead of
 * firing into the void — the foundation keeps every mutation opt-in and
 * observable.
 */
export function NewQuoteDialog({ live }: { live: boolean }) {
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("150000");
  const [base, setBase] = useState<string>("KES");
  const [quote, setQuote] = useState<string>("USD");
  const toast = useToast();

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!live) {
      toast.push({
        variant: "info",
        title: "FX quoting is not live yet",
        description:
          "POST /fx/quotes is typed and ready in the SDK; enable the API gateway to quote for real.",
      });
      setOpen(false);
      return;
    }
    const amountMinor = Number.parseInt(amount, 10);
    if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0) {
      toast.push({
        variant: "danger",
        title: "Invalid amount",
        description: "Enter a positive integer amount in minor units.",
      });
      return;
    }
    if (base === quote) {
      toast.push({
        variant: "warning",
        title: "Pick two different currencies",
        description: "A quote needs distinct base and quote currencies.",
      });
      return;
    }
    toast.push({
      variant: "info",
      title: "Quote request queued",
      description: `Will request ${amountMinor} ${base} → ${quote} once submit wiring lands with the live API.`,
    });
    setOpen(false);
  };

  return (
    <>
      <Button variant="primary" icon="fx" onClick={() => setOpen(true)}>
        New quote
      </Button>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Request FX quote"
        description="TTL'd indicative quote (rate includes the mark-up policy)."
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" form="fx-quote-form">
              Request quote
            </Button>
          </>
        }
      >
        <form id="fx-quote-form" onSubmit={onSubmit} className="space-y-4">
          <Input
            label="Amount (minor units)"
            name="amount_minor"
            inputMode="numeric"
            required
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            hint="e.g. 15000000 = 150,000.00 KES"
          />
          <div className="grid grid-cols-2 gap-4">
            <Select label="Base currency" name="base_currency" value={base} onChange={(event) => setBase(event.target.value)}>
              {CURRENCIES.map((currency) => (
                <option key={currency} value={currency}>
                  {currency}
                </option>
              ))}
            </Select>
            <Select label="Quote currency" name="quote_currency" value={quote} onChange={(event) => setQuote(event.target.value)}>
              {CURRENCIES.map((currency) => (
                <option key={currency} value={currency}>
                  {currency}
                </option>
              ))}
            </Select>
          </div>
        </form>
      </Dialog>
    </>
  );
}
