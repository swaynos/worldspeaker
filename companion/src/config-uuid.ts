/** Canonical syntax for UUIDs in v1 startup configuration only.
 * Dialogue event/request UUIDs have their own wire-contract validator.
 */
const CANONICAL_CONFIG_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;

export function isCanonicalConfigUuid(value: unknown): value is string {
  return typeof value === 'string' && value.length === 36 && CANONICAL_CONFIG_UUID.test(value);
}
