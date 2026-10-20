const MODIFIER_PLACEHOLDER_REGEX = /\{([^}:]+)(?::([^}]+))?}/g;
const LATIN_ACCENT_REGEX = /(?<=\p{Script=Latin})\p{M}+/gu;
const KEPT_ON_ITS_OWN_REGEX = /[\p{ASCII}\p{L}\p{Nd}\p{Z}]/u;
const SPACE_SEPARATOR_REGEX = /\p{Z}/u;
const LETTER_REGEX = /\p{L}/u;
const LATIN_REGEX = /\p{Script=Latin}/u;
const MARK_REGEX = /\p{M}/u;
const ZERO_WIDTH_NON_JOINER = '\u200C';
const ZERO_WIDTH_JOINER = '\u200D';
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
 * text need between non-Latin letters. Unicode spaces become plain spaces; other non-ASCII
 * characters (punctuation, symbols, emoji, control and invisible format characters) are dropped,
 * as are filesystem-illegal characters (\ / : * ? " < > |) and ASCII control characters.
 *
 * Like the backend, it decides what to keep on the decomposed text and recomposes only at the end,
 * so a symbol that decomposes to ASCII plus a mark keeps the ASCII part (≠ → =), as it did when
 * every non-ASCII code point was dropped.
 */
export function sanitize(input: string | null | undefined): string {
  if (!input) return '';
  // Strip accents from Latin letters: decompose to NFD and drop their combining marks
  const unaccented = input.normalize('NFD').replace(LATIN_ACCENT_REGEX, '');
  return keepNameCharacters(unaccented)
    .replace(INVALID_CHARS_REGEX, '')
    .replace(CONTROL_CHARS_REGEX, '')
    .normalize('NFC')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Keeps ASCII, letters, decimal digits and Unicode spaces (as plain spaces), as the backend's
 * keepNameCharacters does. A combining mark belongs to the code point before it, so it is kept only
 * along with a letter (or the mark or joiner that follows one), never moved onto whatever came
 * before a dropped emoji or symbol.
 */
function keepNameCharacters(decomposed: string): string {
  const chars = Array.from(decomposed);
  const kept: string[] = [];
  let previousTakesMarks = false;
  for (let i = 0; i < chars.length; i++) {
    let char = chars[i];
    let keep = false;
    if (KEPT_ON_ITS_OWN_REGEX.test(char)) {
      char = SPACE_SEPARATOR_REGEX.test(char) ? ' ' : char;
      keep = true;
    } else if (isMark(char)) {
      keep = previousTakesMarks;
    } else if (isJoiner(char)) {
      keep = joinsNonLatinLetters(kept, chars, i);
    }
    if (keep) {
      kept.push(char);
    }
    previousTakesMarks = keep && (LETTER_REGEX.test(char) || isMark(char) || isJoiner(char));
  }
  return kept.join('');
}

/**
 * Whether the zero-width (non-)joiner at index sits between non-Latin letters or marks once
 * dropped characters are gone. Persian and Indic text need it there; anywhere else it is invisible
 * noise that Latin names never had.
 */
function joinsNonLatinLetters(kept: string[], chars: string[], index: number): boolean {
  const previous = kept[kept.length - 1];
  if (previous === undefined || (!isNonLatinLetter(previous) && !isMark(previous))) {
    return false;
  }
  let skippedDropped = false;
  for (let i = index + 1; i < chars.length; i++) {
    const next = chars[i];
    if (isMark(next)) {
      if (!skippedDropped) {
        // The mark sits on the joiner itself (Sinhala touching letters) and is kept with it
        return true;
      }
      // Dropped along with the character it sits on
    } else if (!KEPT_ON_ITS_OWN_REGEX.test(next) && !isJoiner(next)) {
      skippedDropped = true;
    } else {
      return isNonLatinLetter(next);
    }
  }
  return false;
}

function isMark(char: string): boolean {
  return MARK_REGEX.test(char);
}

function isJoiner(char: string): boolean {
  return char === ZERO_WIDTH_NON_JOINER || char === ZERO_WIDTH_JOINER;
}

function isNonLatinLetter(char: string): boolean {
  return LETTER_REGEX.test(char) && !LATIN_REGEX.test(char);
}
