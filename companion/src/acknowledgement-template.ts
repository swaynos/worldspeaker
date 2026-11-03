import { hasPublicBodyUnicodeProperty } from './generated/public-body-unicode.js';

export const MAX_ACKNOWLEDGEMENT_TEMPLATE_UNITS = 256;

const PLAYER_PLACEHOLDER = '{player}';
const REPRESENTATIVE_PLAYER_NAME = 'TestPlayer';
const LEGACY_FORMAT_CODES = new Set('0123456789abcdefklmnorxABCDEFKLMNORX');

/**
 * Mirrors CompanionConfig's canonical acknowledgement policy and its
 * VanillaDialogue.isUsableAcknowledgementTemplate rendering check.
 * The original template is checked and returned unchanged; substitutions are
 * global, and only the supported {player} token may use braces.
 */
export function isValidAcknowledgementTemplate(value: unknown): value is string {
  if (typeof value !== 'string' || value.length < 1 || value.length > MAX_ACKNOWLEDGEMENT_TEMPLATE_UNITS ||
      hasUnsafeControl(value)) return false;

  const withoutPlayer = value.replaceAll(PLAYER_PLACEHOLDER, '');
  if (withoutPlayer.includes('{') || withoutPlayer.includes('}')) return false;

  return isUsableAcknowledgementTemplate(value);
}

/** TypeScript equivalent of Java's bounded production-render usability check. */
export function isUsableAcknowledgementTemplate(template: string): boolean {
  const substitutedBody = template.replaceAll(PLAYER_PLACEHOLDER, REPRESENTATIVE_PLAYER_NAME);
  if (!hasVisibleText(substitutedBody)) return false;
  return hasVisibleText(cleanAcknowledgementBody(substitutedBody));
}

function hasUnsafeControl(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0)!;
    if (codePoint <= 0x1f && codePoint !== 0x09 && codePoint !== 0x0a ||
        codePoint >= 0x7f && codePoint <= 0x9f) return true;
  }
  return false;
}

function hasVisibleText(value: string): boolean {
  for (const character of value) {
    if (hasPublicBodyUnicodeProperty('visibleBase', character.codePointAt(0)!)) return true;
  }
  return false;
}

/** Mirrors the production renderer's removals before it checks for visible output. */
function cleanAcknowledgementBody(value: string): string {
  const characters = Array.from(value);
  let clean = '';
  for (let index = 0; index < characters.length; index += 1) {
    const character = characters[index]!;
    const codePoint = character.codePointAt(0)!;

    if (codePoint >= 0xd800 && codePoint <= 0xdfff) continue;
    if (codePoint === 0x00a7) {
      const next = characters[index + 1];
      if (next !== undefined && LEGACY_FORMAT_CODES.has(next)) index += 1;
      continue;
    }
    if (isBidiControl(codePoint)) continue;
    if (codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f ||
        codePoint === 0x2028 || codePoint === 0x2029) {
      if (codePoint === 0x0a || codePoint === 0x0d || codePoint === 0x09 ||
          codePoint === 0x2028 || codePoint === 0x2029) clean += ' ';
      continue;
    }
    clean += character;
  }
  return clean;
}

function isBidiControl(codePoint: number): boolean {
  return codePoint === 0x061c || codePoint === 0x200e || codePoint === 0x200f ||
    codePoint >= 0x202a && codePoint <= 0x202e || codePoint >= 0x2066 && codePoint <= 0x206f;
}
