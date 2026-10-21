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

// Byte limits PathPatternResolver applies on the server
export const MAX_COMPONENT_BYTES = 200;
const MAX_FILESYSTEM_COMPONENT_BYTES = 245; // Left 10 bytes buffer
const MAX_AUTHOR_BYTES = 180;
const MAX_EXTENSION_BYTES = 50;
const TRUNCATION_SUFFIX = ' et al.';

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

function resolveModifierPlaceholders(block: string, values: Record<string, string>, keepUnknown = false): string {
  return block.replace(MODIFIER_PLACEHOLDER_REGEX, (placeholder: string, fieldName: string, modifier?: string) => {
    if (keepUnknown && !Object.hasOwn(values, fieldName)) {
      return placeholder;
    }
    const val = values[fieldName] ?? '';
    return modifier ? applyModifier(val, modifier, fieldName) : val;
  });
}

function checkAllPlaceholdersPresent(block: string, values: Record<string, string>): boolean {
  const matches = [...block.matchAll(MODIFIER_PLACEHOLDER_REGEX)];
  return matches.every(m => values[m[1]]?.trim());
}

export function replacePlaceholders(pattern: string, values: Record<string, string>): string {
  // Resolve optional blocks, then the remaining top-level placeholders (with optional modifiers)
  return resolveModifierPlaceholders(resolveOptionalBlocks(pattern, values), values).trim();
}

function resolveOptionalBlocks(pattern: string, values: Record<string, string>): string {
  // Handle optional blocks with else clause: <primary|fallback>
  return pattern.replace(/<([^<>]+)>/g, (_, blockContent: string) => {
    const pipeIndex = blockContent.indexOf('|');
    const primary = pipeIndex >= 0 ? blockContent.substring(0, pipeIndex) : blockContent;
    const fallback = pipeIndex >= 0 ? blockContent.substring(pipeIndex + 1) : null;

    if (checkAllPlaceholdersPresent(primary, values)) {
      return resolveModifierPlaceholders(primary, values);
    }
    return fallback != null ? resolveModifierPlaceholders(fallback, values) : '';
  });
}

/**
 * Resolves a file naming pattern to the relative path the server moves a book file to, mirroring
 * PathPatternResolver.resolvePattern once the values are sanitized and capped.
 *
 * A book file keeps the extension of its current name. A folder audiobook gets none, since a dot
 * in a folder name ("01. The Assassin's Blade") does not start an extension. Every path component
 * is then cut to the bytes the filesystem allows, keeping a file's extension.
 */
export function resolveFilePath(pattern: string, values: Record<string, string>, currentFilename: string, folderBased: boolean): string {
  if (!pattern?.trim()) {
    return currentFilename;
  }

  const lastDot = currentFilename.lastIndexOf('.');
  const extension = !folderBased && lastDot >= 0 && lastDot < currentFilename.length - 1
    ? sanitize(currentFilename.substring(lastDot + 1))
    : '';
  const allValues = {...values, extension};

  // Unlike replacePlaceholders, keep unknown placeholders and do not trim, as the server does:
  // a title cut to its byte limit can end with a space
  let result = resolveModifierPlaceholders(resolveOptionalBlocks(pattern, allValues), allValues, true);
  const usedFallbackFilename = !result.trim();
  if (usedFallbackFilename) {
    result = currentFilename;
  }

  const patternIncludesFilename = pattern.includes('{extension}') || pattern.includes('{currentFilename}');
  if (!folderBased && !usedFallbackFilename && !patternIncludesFilename && extension.trim()) {
    result += `.${extension}`;
  }

  return validateFinalPath(result, folderBased);
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

/**
 * Cuts an author list that is too long for a path at the last whole author that fits and adds
 * " et al.", as the server does before sanitizing it. A first author that alone is too long is cut
 * within the name.
 */
export function truncateAuthorsForFilesystem(authors: string): string {
  if (!authors || utf8Length(authors) <= MAX_AUTHOR_BYTES) {
    return authors;
  }

  const authorArray = splitDroppingTrailingEmpty(authors, ', ');
  let result = '';
  let currentBytes = 0;
  for (let i = 0; i < authorArray.length; i++) {
    const author = authorArray[i];
    const separatorBytes = i > 0 ? 2 : 0;
    const authorBytes = utf8Length(author);

    if (currentBytes + separatorBytes + authorBytes > MAX_AUTHOR_BYTES) {
      if (!result) {
        return truncatePathComponent(author, MAX_AUTHOR_BYTES - utf8Length(TRUNCATION_SUFFIX)) + TRUNCATION_SUFFIX;
      }
      return result + TRUNCATION_SUFFIX;
    }

    if (i > 0) {
      result += ', ';
      currentBytes += 2;
    }
    result += author;
    currentBytes += authorBytes;
  }
  return result;
}

/**
 * Cuts component to at most maxBytes of UTF-8, at a code point boundary, as the server's
 * truncatePathComponent does.
 */
export function truncatePathComponent(component: string, maxBytes: number): string {
  if (!component || utf8Length(component) <= maxBytes) {
    return component;
  }

  let bytes = 0;
  let end = 0;
  for (const char of component) {
    // Java's UTF-8 encoder stops at a lone surrogate, which it cannot encode
    if (isLoneSurrogate(char)) {
      break;
    }
    bytes += codePointBytes(char);
    if (bytes > maxBytes) {
      break;
    }
    end += char.length;
  }
  return component.substring(0, end);
}

/**
 * Cuts every component of the resolved path to the filesystem's byte limit, keeping the extension
 * of the file name, and drops trailing dots from the components of a book file's path, as the
 * server's validateFinalPath does.
 */
function validateFinalPath(path: string, folderBased: boolean): string {
  const components = splitDroppingTrailingEmpty(path, '/');
  const result: string[] = [];
  components.forEach((component, i) => {
    if (!component) {
      return;
    }

    const isLastComponent = i === components.length - 1;
    // For folder-based items, don't treat dots as extension separators
    if (isLastComponent && component.includes('.') && !folderBased) {
      component = truncateFilenameWithExtension(component);
    } else {
      component = truncatePathComponent(component, MAX_FILESYSTEM_COMPONENT_BYTES);
      if (!folderBased) {
        component = component.replace(/\.+$/, '');
      }
    }
    result.push(component);
  });
  return result.join('/');
}

function truncateFilenameWithExtension(filename: string): string {
  const lastDotIndex = filename.lastIndexOf('.');
  const extension = filename.substring(lastDotIndex);
  if (lastDotIndex <= 0 || utf8Length(extension) > MAX_EXTENSION_BYTES) {
    // No extension, a hidden file, or an extension too long to keep whole
    return truncatePathComponent(filename, MAX_FILESYSTEM_COMPONENT_BYTES);
  }

  const name = filename.substring(0, lastDotIndex);
  const maxNameBytes = MAX_FILESYSTEM_COMPONENT_BYTES - utf8Length(extension);
  return utf8Length(name) > maxNameBytes ? truncatePathComponent(name, maxNameBytes) + extension : filename;
}

/** Splits value as Java's String.split does, which drops the empty parts at the end. */
function splitDroppingTrailingEmpty(value: string, separator: string): string[] {
  const parts = value.split(separator);
  while (parts.length > 0 && parts[parts.length - 1] === '') {
    parts.pop();
  }
  return parts;
}

/** UTF-8 length of value as Java's getBytes counts it, which writes a lone surrogate as '?'. */
function utf8Length(value: string): number {
  let bytes = 0;
  for (const char of value) {
    bytes += isLoneSurrogate(char) ? 1 : codePointBytes(char);
  }
  return bytes;
}

function codePointBytes(char: string): number {
  const codePoint = char.codePointAt(0)!;
  if (codePoint < 0x80) return 1;
  if (codePoint < 0x800) return 2;
  return codePoint < 0x10000 ? 3 : 4;
}

function isLoneSurrogate(char: string): boolean {
  const codePoint = char.codePointAt(0)!;
  return codePoint >= 0xD800 && codePoint <= 0xDFFF;
}
