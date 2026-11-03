import {
  isCanonicalJavaFloat32JsonNumberToken,
  isNonNegativeSafeJsonIntegerToken,
  isPositiveJsonNumberTokenAtMost,
  normalizeFloat32WireNumber,
  isSafeJsonIntegerToken,
} from './protocol-text.js';

const MAX_JSON_NESTING = 128;
const WALL_CLOCK_TIMESTAMP_FIELDS = new Set(['occurredAtUnixMillis', 'dispatchedAtUnixMillis']);
const MATHEMATICAL_INTEGER_FIELDS = new Set([
  'schemaVersion', 'revision', 'contractVersion', 'abilityPolicyRevision', 'policyRevision',
  'observedAt', 'executedAt', 'retryAfterMs', 'occurredAtUnixMillis', 'dispatchedAtUnixMillis',
  'requesterIntervalMs', 'characterIntervalMs', 'requesterCooldownMs', 'targetCooldownMs',
]);
const SIGNED_SAFE_INTEGER_FIELDS = new Set(['minX', 'maxXExclusive', 'minZ', 'maxZExclusive']);
const FLOAT32_WIRE_FIELDS = new Set(['health', 'maxHealth', 'healthRemoved', 'absorptionRemoved']);
const JSON_NUMBER_AT = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/uy;
type JsonParseContext = Readonly<{ source?: string }>;
type SourceAwareReviver = (this: unknown, key: string, value: unknown, context?: JsonParseContext) => unknown;
type JsonScope = { kind: 'object' | 'array'; names: Set<string> | null; property: string | null; coordinateContext: boolean };

/** Parse JSON only after checking every object scope for duplicate decoded names. */
export function parseStrictJson(source: string, maxNesting = MAX_JSON_NESTING): unknown {
  if (!Number.isSafeInteger(maxNesting) || maxNesting < 1 || maxNesting > MAX_JSON_NESTING) {
    throw new Error('JSON nesting limit is invalid');
  }
  const scopes: JsonScope[] = [];
  let index = 0;

  while (index < source.length) {
    const character = source[index];
    if (character === '"') {
      const start = index++;
      let closed = false;
      while (index < source.length) {
        const current = source.charCodeAt(index);
        if (current === 0x5c) {
          index += 2;
        } else if (current === 0x22) {
          index++;
          closed = true;
          break;
        } else {
          index++;
        }
      }
      if (!closed) throw new Error('malformed JSON string');

      let afterString = index;
      while (isJsonWhitespace(source.charCodeAt(afterString))) afterString++;
      const scope = scopes[scopes.length - 1];
      if (source[afterString] === ':' && scope?.kind === 'object') {
        const key: unknown = JSON.parse(source.slice(start, index));
        if (typeof key !== 'string') throw new Error('malformed JSON object key');
        if (scope.names?.has(key)) throw new Error('duplicate JSON object key');
        scope.names?.add(key);
        scope.property = key;
      } else if (scope !== undefined && scope.kind === 'object') {
        scope.property = null;
      }
      continue;
    }

    if (character === '{' || character === '[') {
      if (scopes.length >= maxNesting) throw new Error('JSON nesting limit exceeded');
      const parent = scopes[scopes.length - 1];
      const property = parent?.kind === 'object' ? takeObjectProperty(parent) : null;
      const coordinateContext = parent?.coordinateContext === true || character === '[' && property === 'coordinates';
      scopes.push({
        kind: character === '{' ? 'object' : 'array',
        names: character === '{' ? new Set<string>() : null,
        property: null,
        coordinateContext,
      });
    } else if ((character === '}' || character === ']') && scopes.length > 0) {
      scopes.pop();
    } else if (character === '-' || character !== undefined && character >= '0' && character <= '9') {
      JSON_NUMBER_AT.lastIndex = index;
      const match = JSON_NUMBER_AT.exec(source);
      if (match !== null) {
        const token = match[0];
        const parent = scopes[scopes.length - 1];
        const property = parent?.kind === 'object' ? takeObjectProperty(parent) : null;
        if (property !== null && MATHEMATICAL_INTEGER_FIELDS.has(property) &&
            !isNonNegativeSafeJsonIntegerToken(token)) {
          throw new Error('JSON integer field is not a mathematical safe integer');
        }
        if (property !== null && SIGNED_SAFE_INTEGER_FIELDS.has(property) && !isSafeJsonIntegerToken(token)) {
          throw new Error('JSON coordinate is not a mathematical safe integer');
        }
        if (parent?.coordinateContext === true && !isSafeJsonIntegerToken(token)) {
          throw new Error('JSON coordinate is not a mathematical safe integer');
        }
        if (property !== null && FLOAT32_WIRE_FIELDS.has(property) &&
            !isCanonicalJavaFloat32JsonNumberToken(token)) {
          throw new Error('JSON float32 field is not a canonical Java or ECMAScript number');
        }
        index += token.length;
        continue;
      }
      if (scopes[scopes.length - 1]?.kind === 'object') takeObjectProperty(scopes[scopes.length - 1]!);
    } else if (character === 't' || character === 'f' || character === 'n') {
      const parent = scopes[scopes.length - 1];
      if (parent?.kind === 'object') takeObjectProperty(parent);
    }
    index++;
  }

  const parseWithSource = JSON.parse as unknown as (text: string, reviver: SourceAwareReviver) => unknown;
  return parseWithSource(source, (key, value, context) => {
    if (typeof value === 'number' && typeof context?.source === 'string') {
      if (MATHEMATICAL_INTEGER_FIELDS.has(key) && !isNonNegativeSafeJsonIntegerToken(context.source)) {
        throw new Error('JSON integer field is not a mathematical safe integer');
      }
      if (SIGNED_SAFE_INTEGER_FIELDS.has(key) && !isSafeJsonIntegerToken(context.source)) {
        throw new Error('JSON coordinate is not a mathematical safe integer');
      }
      const maximum = key === 'maxRangeBlocks' ? 64 : key === 'maxDamage' ? 5 : undefined;
      if (maximum !== undefined && (!Number.isFinite(value) || value <= 0 ||
          !isPositiveJsonNumberTokenAtMost(context.source, maximum))) {
        throw new Error('bounded JSON number is outside its exact range');
      }
    }
    if (WALL_CLOCK_TIMESTAMP_FIELDS.has(key) && typeof value === 'number' &&
        (typeof context?.source !== 'string' || !isNonNegativeSafeJsonIntegerToken(context.source))) {
      throw new Error('wall-clock timestamp is not a mathematical safe integer');
    }
    if (FLOAT32_WIRE_FIELDS.has(key) && typeof value === 'number') {
      if (typeof context?.source !== 'string' || !isCanonicalJavaFloat32JsonNumberToken(context.source)) {
        throw new Error('JSON float32 field is not a canonical Java or ECMAScript float32 number');
      }
      const normalized = normalizeFloat32WireNumber(value);
      if (normalized === null) throw new Error('JSON float32 field could not normalize to binary32');
      return normalized;
    }
    return value;
  });
}

function takeObjectProperty(scope: JsonScope): string | null {
  if (scope.kind !== 'object') return null;
  const property = scope.property;
  scope.property = null;
  return property;
}

function isJsonWhitespace(character: number): boolean {
  return character === 0x20 || character === 0x09 || character === 0x0a || character === 0x0d;
}
