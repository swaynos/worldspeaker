import {
  isReplyPayloadTerminalContent,
  type ReplyPayload,
} from 'openclaw/plugin-sdk/reply-payload';
import { MAX_COMPLETED_ANSWER_UNITS } from './protocol.js';
import { isSafePublicText } from './protocol-text.js';

const SAFE_METADATA_FLAGS = new Set([
  'isError', 'isReasoning', 'isReasoningSnapshot', 'isCommentary', 'isCompactionNotice',
  'isFallbackNotice', 'isStatusNotice',
  'replyToTag', 'replyToCurrent', 'audioAsVoice', 'videoAsNote', 'sensitiveMedia', 'trustedLocalMedia',
]);

const OPTIONAL_UNDEFINED_FIELDS = new Set([
  'mediaUrl', 'mediaUrls', 'attachments', 'presentation', 'presentationTextMode',
  'delivery', 'interactive', 'btw', 'location', 'spokenText',
  'ttsSupplement', 'channelData', 'fallbackText',
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
    if (key === 'replyToId') {
      if (typeof value !== 'string' && value !== undefined) return null;
      continue;
    }
    if (SAFE_METADATA_FLAGS.has(key)) {
      if (value !== false && value !== undefined) return null;
      continue;
    }
    if (OPTIONAL_UNDEFINED_FIELDS.has(key) && value === undefined) continue;
    return null;
  }
  if (typeof record.text !== 'string') return null;

  const text = record.text;
  return isSafeCompletedText(text) ? text : null;
}

export function isSafeCompletedText(text: string): boolean {
  return text.length > 0 && text.length <= MAX_COMPLETED_ANSWER_UNITS && isSafePublicText(text);
}
