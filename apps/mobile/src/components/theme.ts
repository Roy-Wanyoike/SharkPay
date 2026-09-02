/**
 * Minimal design tokens for the SharkPay mobile design system.
 * (A shared tokens package with apps/web is a post-V1 integration step —
 * noted in the app README.)
 */

import { Platform } from 'react-native';

export const palette = {
  brand: '#0B6BCB',
  brandPressed: '#095AA8',
  brandSoft: '#E3EEFB',
  success: '#15803D',
  successSoft: '#DCFCE7',
  danger: '#DC2626',
  dangerPressed: '#B91C1C',
  dangerSoft: '#FEE2E2',
  warning: '#B45309',
  warningSoft: '#FEF3C7',
  neutral: '#64748B',
  neutralSoft: '#F1F5F9',
  text: '#0F172A',
  textMuted: '#5B6B7F',
  textOnBrand: '#FFFFFF',
  surface: '#FFFFFF',
  background: '#F5F7FA',
  border: '#E2E8F0',
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const radius = {
  sm: 6,
  md: 10,
  lg: 16,
  pill: 999,
} as const;

export const typography = {
  fontFamily: Platform.select({ ios: 'System', default: 'sans-serif' }),
  title: 28,
  heading: 20,
  body: 16,
  label: 14,
  caption: 12,
  moneyLarge: 34,
  moneyMedium: 22,
  moneySmall: 15,
} as const;
