import type { AbilityPolicy } from './ability-policy.js';
import { consumeCurrentAbilityPolicy, type AbilityContextExpectation } from './transport.js';
import {
  MAX_ABILITY_FRAME_BYTES,
  MAX_ABILITY_JSON_DEPTH,
  OBSERVE_PLAYER_ABILITY,
  STRIKE_LIGHTNING_ABILITY,
  validateAbilityInputSchema,
  validateAbilityResultSchema,
  type AbilityReasonCode,
  type AbilityResult,
  type ValidatedAbilityInput,
} from './ability-catalogue.js';
import { parseStrictJson } from './strict-json.js';

export type AbilityValidationDecision =
  | Readonly<{ status: 'VALID'; intent: ValidatedAbilityInput }>
  | Readonly<{ status: 'REJECTED'; reasonCode: 'invalid_input' | 'unsupported_contract' | 'stale_context' }>;
type RejectionReason = Extract<AbilityValidationDecision, { status: 'REJECTED' }>['reasonCode'];

/** Opaque owner-local handle. Production issuance is private to WorldspeakerTransport. */
export type AbilityTrustedContextHandle = object;

/** Strictly parses untrusted intent. This function does not establish request authority. */
export function validateAbilityIntent(rawBytes: Uint8Array): AbilityValidationDecision {
  if (!(rawBytes instanceof Uint8Array) || rawBytes.byteLength < 1 || rawBytes.byteLength > MAX_ABILITY_FRAME_BYTES) {
    return rejected('invalid_input');
  }
  let parsed: unknown;
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(rawBytes);
    if (text.startsWith('\ufeff')) return rejected('invalid_input');
    parsed = parseStrictJson(text, MAX_ABILITY_JSON_DEPTH);
  } catch {
    return rejected('invalid_input');
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return rejected('invalid_input');
  const envelope = parsed as Record<string, unknown>;
  const keys = Object.keys(envelope);
  if (keys.some((key) => !['abilityId', 'contractVersion', 'arguments'].includes(key)) ||
      keys.length !== 3 || !Object.hasOwn(envelope, 'abilityId') || !Object.hasOwn(envelope, 'contractVersion') ||
      !Object.hasOwn(envelope, 'arguments')) return rejected('invalid_input');
  if (typeof envelope.abilityId !== 'string' ||
      ![OBSERVE_PLAYER_ABILITY, STRIKE_LIGHTNING_ABILITY].includes(envelope.abilityId as typeof OBSERVE_PLAYER_ABILITY)) {
    return rejected('unsupported_contract');
  }
  if (envelope.contractVersion !== 1) return rejected('unsupported_contract');
  const intent = validateAbilityInputSchema(envelope);
  if (intent === null) return rejected('invalid_input');

  return Object.freeze({ status: 'VALID', intent });
}

export function validateAbilityResultBytes(rawBytes: Uint8Array): AbilityResult | null {
  if (!(rawBytes instanceof Uint8Array) || rawBytes.byteLength < 1 || rawBytes.byteLength > MAX_ABILITY_FRAME_BYTES) return null;
  let parsed: unknown;
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(rawBytes);
    if (text.startsWith('\ufeff')) return null;
    parsed = parseStrictJson(text, MAX_ABILITY_JSON_DEPTH);
  } catch {
    return null;
  }
  return validateAbilityResultSchema(parsed) ? parsed : null;
}

export type AbilityGateDecision = Readonly<{
  state: 'rejected';
  reasonCode: 'permission_denied' | 'stale_context' | 'execution_unavailable';
  effectStarted: false;
}>;

/** Future integrations must implement these seams outside this deny-only gate. */
export interface AbilityCurrentPolicySource {
  currentPolicy(context: AbilityTrustedContextHandle): AbilityPolicy | null;
}
export interface AbilityCurrentPreconditions {
  recheck(context: AbilityTrustedContextHandle, intent: ValidatedAbilityInput): boolean;
}
export interface AbilityAtomicReservations {
  reserve(context: AbilityTrustedContextHandle, intent: ValidatedAbilityInput): boolean;
}
export interface AbilityBoundedTickScheduler {
  schedule(context: AbilityTrustedContextHandle, operation: () => void): boolean;
}
export interface AbilityDurableEvidence {
  prepare(context: AbilityTrustedContextHandle, intent: ValidatedAbilityInput): Promise<boolean>;
}

/** This story deliberately has no allow-producing executor, world reader, reservation, or persistence provider. */
export function evaluateAbilityExecution(
  context: AbilityTrustedContextHandle | null,
  expected: AbilityContextExpectation,
  intent: ValidatedAbilityInput,
): AbilityGateDecision {
  let policyState: 'stale' | 'denied' | 'conditional';
  try {
    const safeIntent = validateAbilityInputSchema(intent);
    if (safeIntent === null) return deny('permission_denied');
    policyState = consumeCurrentAbilityPolicy(context, expected, safeIntent);
  } catch {
    return deny('stale_context');
  }
  if (policyState === 'stale') return deny('stale_context');
  if (policyState === 'denied') return deny('permission_denied');
  // A policy grant is only a condition for future work; no selected operation is implemented or qualified here.
  return deny('execution_unavailable');
}

function rejected(reasonCode: RejectionReason): AbilityValidationDecision {
  return Object.freeze({ status: 'REJECTED', reasonCode });
}

function deny(reasonCode: AbilityGateDecision['reasonCode']): AbilityGateDecision {
  return Object.freeze({ state: 'rejected', reasonCode, effectStarted: false });
}
