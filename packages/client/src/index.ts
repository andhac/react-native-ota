/**
 * Public JS entry for `react-native-ota`.
 *
 * MS0: placeholder only — no OTA behavior.
 * Future: M9 Public API facade over M3 (see docs/ARCHITECTURE.md).
 */

export const VERSION = '0.0.0' as const;

/**
 * Library is not configured yet. Real configure/sync APIs arrive in MS5.
 */
export function isOtaReady(): boolean {
  return false;
}
