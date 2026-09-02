/**
 * MoneyDisplay: the ONLY sanctioned way to render an amount — including the
 * money-safety guarantees (bigint exactness, refusal to round). Assertions
 * use the accessibility label, which carries the exact rendered string.
 * RNTL v14's render() is async — every render is awaited.
 */

import { describe, expect, it } from '@jest/globals';
import { render } from '@testing-library/react-native';
import React from 'react';

import { MoneyDisplay } from '../MoneyDisplay';

const labelOf = async (ui: React.ReactElement): Promise<string> => {
  const { getByTestId } = await render(ui);
  return String((getByTestId('money').props as { accessibilityLabel?: string }).accessibilityLabel);
};

describe('MoneyDisplay', () => {
  it('renders an exponent-2 amount with currency prefix and grouping', async () => {
    await expect(
      labelOf(<MoneyDisplay amountMinor={150000} exponent={2} currency="KES" testID="money" />),
    ).resolves.toBe('KES 1,500.00');
  });

  it('renders float-unsafe amounts exactly via bigint (2^53+1 probe)', async () => {
    const label = await labelOf(
      <MoneyDisplay amountMinor={9007199254740993n} exponent={0} currency="KES" testID="money" />,
    );
    expect(label).toBe('KES 9,007,199,254,740,993');
    expect(label).not.toContain('9,007,199,254,740,992');
  });

  it('renders the explicit sign when requested', async () => {
    await expect(
      labelOf(<MoneyDisplay amountMinor={1500} exponent={2} currency="KES" withSign testID="money" />),
    ).resolves.toBe('KES +15.00');
  });

  it('omits the currency prefix when not provided', async () => {
    await expect(
      labelOf(<MoneyDisplay amountMinor={1500} exponent={2} testID="money" />),
    ).resolves.toBe('15.00');
  });

  it('refuses to display a float-unsafe number (never invents digits)', async () => {
    await expect(
      render(<MoneyDisplay amountMinor={9007199254740993} exponent={0} testID="money" />),
    ).rejects.toThrow(RangeError);
  });
});
