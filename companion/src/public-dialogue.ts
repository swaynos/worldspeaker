import {
  isReplyPayloadTerminalContent,
  type ReplyPayload,
} from 'openclaw/plugin-sdk/reply-payload';
import { MAX_COMPLETED_ANSWER_UNITS } from './protocol.js';
import { isSafePublicText } from './protocol-text.js';

const SAFE_METADATA_FLAGS = new Set([
  'isError', 'isReasoning', 'isReasoningSnapshot', 'isCommentary', 'isCompactionNotice',
  'isFallbackNotice', 'isStatusNotice',
]);

/** Accepts one complete text-only final and returns the exact safe chat body. */
export function completedPublicText(payload: ReplyPayload, kind: string): string | null {
  if (kind !== 'final' || payload === null || typeof payload !== 'object') return null;
  try {
    if (!isReplyPayloadTerminalContent(payload)) return null;
  } catch {
    return null;
  }

  const record = payload as unknown as Record<string, unknown>;
  for (const [key, value] of Object.entries(record)) {
    if (key === 'text') continue;
    if (!SAFE_METADATA_FLAGS.has(key) || value !== false) return null;
  }
  if (typeof record.text !== 'string') return null;

  const text = record.text;
  return isSafeCompletedText(text) ? text : null;
}

export function isSafeCompletedText(text: string): boolean {
  return text.length > 0 && text.length <= MAX_COMPLETED_ANSWER_UNITS && isSafePublicText(text);
}
