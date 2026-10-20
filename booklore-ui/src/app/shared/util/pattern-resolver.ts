const MODIFIER_PLACEHOLDER_REGEX = /\{([^}:]+)(?::([^}]+))?}/g;
const LATIN_ACCENT_REGEX = /(?<=\p{Script=Latin})\p{M}+/gu;
const SPACE_SEPARATOR_REGEX = /\p{Z}/gu;
const DETACHED_MARK_REGEX = /(?<![\p{L}\p{M}])\p{M}+/gu;
const UNSUPPORTED_NON_ASCII_REGEX = /[^\p{ASCII}\p{L}\p{Nd}\p{M}\u200C\u200D]/gu;
const STRAY_JOINER_REGEX = /(?<![\p{L}\p{M}])[\u200C\u200D]|[\u200C\u200D](?![\p{L}\p{M}])/gu;
const INVALID_CHARS_REGEX = /[\\/:*?"<>|]/g;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS_REGEX = /[\x00-\x1F\x7F]/g;

export function applyModifier(value: string, modifier: string, fieldName: string): string {
  if (!value) return value;

  switch (modifier) {
    case 'first':
      return value.split(', ')[0].trim();
    case 'sort': {
      const first = value.split(', ')[0].trim();
      const lastSpace = first.lastIndexOf(' ');
      return lastSpace > 0 ? `${first.substring(lastSpace + 1)}, ${first.substring(0, lastSpace)}` : first;
    }
    case 'initial': {
      let target = value;
      if (fieldName === 'authors') {
        const firstAuthor = value.split(', ')[0].trim();
        const lastSpace = firstAuthor.lastIndexOf(' ');
        target = lastSpace > 0 ? firstAuthor.substring(lastSpace + 1) : firstAuthor;
      }
      return target.charAt(0).toUpperCase();
    }
    case 'upper':
      return value.toUpperCase();
    case 'lower':
      return value.toLowerCase();
    default:
      return value;
  }
}

function resolveModifierPlaceholders(block: string, values: Record<string, string>): string {
  return block.replace(MODIFIER_PLACEHOLDER_REGEX, (_, fieldName: string, modifier?: string) => {
    const val = values[fieldName] ?? '';
    return modifier ? applyModifier(val, modifier, fieldName) : val;
  });
}

function checkAllPlaceholdersPresent(block: string, values: Record<string, string>): boolean {
  const matches = [...block.matchAll(MODIFIER_PLACEHOLDER_REGEX)];
  return matches.every(m => values[m[1]]?.trim());
}

export function replacePlaceholders(pattern: string, values: Record<string, string>): string {
  // Handle optional blocks with else clause: <primary|fallback>
  pattern = pattern.replace(/<([^<>]+)>/g, (_, blockContent: string) => {
    const pipeIndex = blockContent.indexOf('|');
    const primary = pipeIndex >= 0 ? blockContent.substring(0, pipeIndex) : blockContent;
    const fallback = pipeIndex >= 0 ? blockContent.substring(pipeIndex + 1) : null;

    if (checkAllPlaceholdersPresent(primary, values)) {
      return resolveModifierPlaceholders(primary, values);
    }
    return fallback != null ? resolveModifierPlaceholders(fallback, values) : '';
  });

  // Replace remaining top-level placeholders (with optional modifiers)
  return resolveModifierPlaceholders(pattern, values).trim();
}

/**
 * Cleans a metadata value for use in a path, mirroring PathPatternResolver.sanitize on the backend
 * so the preview shows the name the server will write.
 *
 * Accents are stripped from Latin letters (é → e) but letters of every script are kept, with the
 * marks that belong to them (Japanese が, Devanagari vowel signs) and the joiners Persian and Indic
 * text need between letters. Unicode spaces become plain spaces; other non-ASCII characters
 * (punctuation, symbols, emoji, control and invisible format characters) are dropped, as are
 * filesystem-illegal characters (\ / : * ? " < > |) and ASCII control characters.
 */
export function sanitize(input: string | null | undefined): string {
  if (!input) return '';
  return input.normalize('NFD')
    .replace(LATIN_ACCENT_REGEX, '')
    .normalize('NFC')
    .replace(SPACE_SEPARATOR_REGEX, ' ')
    .replace(DETACHED_MARK_REGEX, '')
    .replace(UNSUPPORTED_NON_ASCII_REGEX, '')
    .replace(STRAY_JOINER_REGEX, '')
    .replace(INVALID_CHARS_REGEX, '')
    .replace(CONTROL_CHARS_REGEX, '')
    .replace(/\s+/g, ' ')
    .trim()
    // Dropping characters can bring together Hangul jamo that compose
    .normalize('NFC');
}
