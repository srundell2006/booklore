const MODIFIER_PLACEHOLDER_REGEX = /\{([^}:]+)(?::([^}]+))?}/g;
const LATIN_ACCENT_REGEX = /(?<=\p{Script=Latin})\p{M}+/gu;
const ASCII_REGEX = /\p{ASCII}/u;
// eslint-disable-next-line no-control-regex
const ASCII_ONLY_REGEX = /^[\x00-\x7F]*$/;
const ASCII_LETTERS_AND_DIGITS_REGEX = /^[A-Za-z0-9]+$/;
const SPACE_SEPARATOR_REGEX = /\p{Z}/u;
const LETTER_REGEX = /\p{L}/u;
const MODIFIER_LETTER_REGEX = /\p{Lm}/u;
const DECIMAL_DIGIT_REGEX = /\p{Nd}/u;
const LETTER_NUMBER_REGEX = /\p{Nl}/u;
const MODIFIER_OR_OTHER_LETTER_REGEX = /[\p{Lm}\p{Lo}]/u;
const OTHER_SYMBOL_REGEX = /\p{So}/u;
const ALPHABETIC_REGEX = /\p{Alphabetic}/u;
const PUNCTUATION_OR_SYMBOL_REGEX = /[\p{P}\p{S}]/u;
const EMOJI_REGEX = /\p{Extended_Pictographic}/u;
const LATIN_REGEX = /\p{Script=Latin}/u;
const COMMON_SCRIPT_REGEX = /\p{Script=Common}/u;
const MARK_REGEX = /\p{M}/u;
const ZERO_WIDTH_NON_JOINER = '\u200C';
const ZERO_WIDTH_JOINER = '\u200D';
// Punctuation that sits inside a word or a number, where a space would split it in two: apostrophes and the marks
// typed in their place ("п’ять", "д’Артаньян", Hebrew ג׳ורג׳), the Armenian emphasis, exclamation, question and
// abbreviation marks written over a vowel (Ինչո՞ւ), the Arabic thousands separator (١٬٠٠٠) and the spacing dakuten
// and handakuten typed after kana (ト゛ラコ゛ン). They are dropped like other punctuation but never become a space,
// as on the server (PathPatternResolver.IN_WORD_PUNCTUATION). The Arabic decimal separator is not one of them:
// dropped, it would make another number of ٢٠٫٥ (٢٠٥).
const IN_WORD_PUNCTUATION = '\u2018\u2019\u201B\u2032\u00B4\u055A\u05F3\u05F4\uFF07'
  + '\u055B\u055C\u055E\u055F\u066C\u309B\u309C';
const INVALID_CHARS_REGEX = /[\\/:*?"<>|]/g;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS_REGEX = /[\x00-\x1F\x7F]/g;
// What the two above remove, for a test of one character
// eslint-disable-next-line no-control-regex
const REMOVED_LATER_REGEX = /[\\/:*?"<>|\x00-\x1F\x7F]/;
// Java's Character.isWhitespace or isSpaceChar, which the server drops from the end of a cut name
// eslint-disable-next-line no-control-regex
const JAVA_WHITESPACE_REGEX = /[\p{Z}\t\n\u000B\f\r\u001C-\u001F]/u;
// Graphemes as Java's BreakIterator finds them; without Intl.Segmenter (Firefox before 125) a cut falls between code points
const GRAPHEMES = typeof Intl.Segmenter === 'function' ? new Intl.Segmenter('und', {granularity: 'grapheme'}) : null;

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
 * A letter that is only another form of ASCII letters is written as them (its NFKC form): ligatures
 * (ﬁ → fi), fullwidth and mathematical letters (Ａ → A, 𝐁𝐨𝐥𝐝 → Bold) and circled and squared letters
 * (Ⓐ 🄰 → A), and so is a number that is only another form of ASCII: a fullwidth or mathematical digit
 * (１ 𝟏 → 1) and a Roman numeral (Ⅷ → VIII, so that ドラゴンクエストⅡ and Ⅲ keep apart). Superscript
 * and subscript letters, the ordinal indicators among them (ª º ᵉ ⁿ), are dropped like the superscript
 * digits (² ³), as they were when every non-ASCII code point was dropped: "Nº 5" and "451º Fahrenheit"
 * keep their names. Other numbers (½ ①), symbols that spell letters (™ №) and the letters that are
 * emoji (ℹ Ⓜ) are dropped too, as they always were.
 *
 * Accents are then stripped from Latin letters (é → e) but letters of every script are kept, with
 * the marks that belong to them (Japanese が, Devanagari vowel signs), the letter numbers of other
 * scripts (the 〇 of 二〇〇八) and the joiners Persian and Indic text need between non-Latin letters.
 * A modifier letter that belongs to no script (ー ـ ʼ ˈ) is kept only where it follows a non-Latin
 * letter or digit, as in コーヒー, and is dropped next to Latin text (Winterʼs → Winters). Unicode
 * spaces become plain spaces; other non-ASCII characters (punctuation, symbols, emoji, control and
 * invisible format characters) are dropped, as are filesystem-illegal characters (\ / : * ? " < > |)
 * and ASCII control characters. Punctuation or a symbol whose nearest kept neighbours on both sides
 * are non-Latin letters or digits becomes a space instead, so that it keeps the words apart
 * (三体·黑暗森林 → 三体 黑暗森林). ASCII neighbours do not count, but a fullwidth digit does, though
 * it is written in ASCII (第１・２巻 → 第1 2巻), and so does a Roman numeral, though it is Latin:
 * dropped, the punctuation would run two numerals into another one (ドラゴンクエストⅠ・Ⅱ →
 * ドラゴンクエストI II, not the III of ドラゴンクエストⅢ; Episode Ⅰ–Ⅲ → Episode I III). The illegal
 * and control characters removed are looked through (なぜ?・どうして → なぜ どうして). Punctuation
 * that sits inside a word or a number is dropped all the same: apostrophes (п’ять → пять), the
 * Armenian question mark over a vowel (Ինչո՞ւ → Ինչու), the Arabic thousands separator (١٬٠٠٠ → ١٠٠٠)
 * and the spacing dakuten (ト゛ → ト). Latin names so come out as they did when every non-ASCII code
 * point was dropped, but for Unicode spaces, Latin letters with no accent to strip (ø ł æ ß) and the
 * ASCII forms above.
 *
 * Like the backend, it decides what to keep on the decomposed text and recomposes only at the end,
 * so a symbol that decomposes to ASCII plus a mark keeps the ASCII part (≠ → =), as it did when
 * every non-ASCII code point was dropped. Sanitizing a sanitized value returns it unchanged.
 *
 * The move preview sanitizes several values of every book it shows, so a value that is ASCII once
 * decomposed, as most are, skips the steps that only change what is not ASCII, and the Unicode
 * properties of each other character are looked up once (classOf) rather than at every step.
 */
export function sanitize(input: string | null | undefined): string {
  if (!input) return '';
  const decomposed = input.normalize('NFD');
  const kept = ASCII_ONLY_REGEX.test(decomposed)
    ? decomposed
    // Strip accents from Latin letters: drop the combining marks that follow them
    : keepNameCharacters(writeCompatibilityFormsInAscii(decomposed).replace(LATIN_ACCENT_REGEX, ''));
  return kept
    .replace(INVALID_CHARS_REGEX, '')
    .replace(CONTROL_CHARS_REGEX, '')
    .normalize('NFC')
    .replace(/\s+/g, ' ')
    .trim();
}

/** What sanitize needs to know of a character, from the Unicode property tests below, which are slow to repeat. */
interface CharacterClass {
  /** The ASCII letters writeCompatibilityFormsInAscii writes it as (asciiFormOf), or null. */
  asciiLetters: string | null;
  /** The ASCII keepNameCharacters writes it as (asciiNumberOf), or null. */
  asciiNumber: string | null;
  keptOnItsOwn: boolean;
  /** Kept on its own and not removed afterwards as an illegal or control character. */
  staysOnItsOwn: boolean;
  space: boolean;
  letter: boolean;
  mark: boolean;
  joiner: boolean;
  commonModifierLetter: boolean;
  nonLatinLetter: boolean;
  nonLatinLetterOrNumber: boolean;
  separatesWords: boolean;
}

// The classes of the characters seen so far. A library's names hold only so many different characters, but should
// unusual names fill it, it is emptied rather than left to grow.
const CHARACTER_CLASSES = new Map<string, CharacterClass>();
const MAX_CHARACTER_CLASSES = 10_000;

function classOf(char: string): CharacterClass {
  let found = CHARACTER_CLASSES.get(char);
  if (found === undefined) {
    if (CHARACTER_CLASSES.size >= MAX_CHARACTER_CLASSES) {
      CHARACTER_CLASSES.clear();
    }
    const keptOnItsOwn = isKeptOnItsOwn(char);
    found = {
      asciiLetters: asciiFormOf(char),
      asciiNumber: asciiNumberOf(char),
      keptOnItsOwn,
      staysOnItsOwn: keptOnItsOwn && !REMOVED_LATER_REGEX.test(char),
      space: SPACE_SEPARATOR_REGEX.test(char),
      letter: LETTER_REGEX.test(char),
      mark: isMark(char),
      joiner: isJoiner(char),
      commonModifierLetter: isCommonModifierLetter(char),
      nonLatinLetter: isNonLatinLetter(char),
      nonLatinLetterOrNumber: isNonLatinLetterOrNumber(char),
      separatesWords: separatesWords(char),
    };
    CHARACTER_CLASSES.set(char, found);
  }
  return found;
}

/**
 * Writes each letter that is only another form of ASCII letters as those, as the backend's
 * writeCompatibilityFormsInAscii does. It runs before accents are stripped, so that a mark on a
 * mathematical letter (𝐀 + ́) is stripped from the A it becomes. Digits and Roman numerals are
 * written in ASCII later, by keepNameCharacters (see asciiNumberOf).
 */
function writeCompatibilityFormsInAscii(decomposed: string): string {
  let written = '';
  for (const char of decomposed) {
    written += classOf(char).asciiLetters ?? char;
  }
  return written;
}

/**
 * The ASCII letters a non-ASCII letter or circled letter stands for, or null when it is more than a
 * form of them, or a superscript or subscript letter.
 */
function asciiFormOf(char: string): string | null {
  if (ASCII_REGEX.test(char) || EMOJI_REGEX.test(char) || isSuperscriptOrSubscriptLetter(char)) {
    return null;
  }
  const letter = LETTER_REGEX.test(char)
    // Circled and squared Latin letters (Ⓐ 🄰) are symbols, but alphabetic ones
    || (OTHER_SYMBOL_REGEX.test(char) && ALPHABETIC_REGEX.test(char));
  return letter ? asciiCompatibilityForm(char) : null;
}

/**
 * The ASCII a non-ASCII number is only another form of, or null: the digit of a fullwidth or
 * mathematical digit (１ 𝟏 → 1) and the letters of a Roman numeral (Ⅷ → VIII, ⅻ → xii), as the
 * backend's asciiNumberOf decides. Unlike letters, these are written in ASCII only once
 * punctuationBetweenNonLatinWords has seen them as the numbers they are, so that 第１・２巻 keeps its
 * numbers apart (第1 2巻) the way 三体·黑暗 keeps its words, and ドラゴンクエストⅠ・Ⅱ does not
 * become the III of ドラゴンクエストⅢ (ドラゴンクエストI II).
 */
function asciiNumberOf(char: string): string | null {
  if (ASCII_REGEX.test(char)) {
    return null;
  }
  if (DECIMAL_DIGIT_REGEX.test(char)) {
    const compatible = asciiCompatibilityForm(char);
    return compatible?.length === 1 ? compatible : null;
  }
  // Only the Roman numerals are letter numbers whose NFKC form is ASCII
  return LETTER_NUMBER_REGEX.test(char) ? asciiCompatibilityForm(char) : null;
}

/** The NFKC form of char when it is ASCII letters and digits only, or null. */
function asciiCompatibilityForm(char: string): string | null {
  const compatible = char.normalize('NFKC');
  return ASCII_LETTERS_AND_DIGITS_REGEX.test(compatible) ? compatible : null;
}

/**
 * ª º ᵉ ⁿ ₐ: the letters that are a superscript or subscript form of ASCII letters, as the backend's
 * isSuperscriptOrSubscriptLetter decides. They are the Latin modifier and other letters (Lm, Lo) whose
 * NFKC form is ASCII. Like the superscript digits they are dropped, as when every non-ASCII code point
 * was, rather than written in ASCII, which would rename books whose names were fine ("Nº 5" to "No 5")
 * and spell "451º Fahrenheit", whose º stands for a degree sign, "451o".
 */
function isSuperscriptOrSubscriptLetter(char: string): boolean {
  return MODIFIER_OR_OTHER_LETTER_REGEX.test(char) && LATIN_REGEX.test(char) && asciiCompatibilityForm(char) !== null;
}

/**
 * Keeps ASCII, letters, decimal digits, the letter numbers of other scripts and Unicode spaces (as
 * plain spaces), as the backend's keepNameCharacters does. A combining mark belongs to the code point
 * before it, so it is kept only along with a letter (or the mark or joiner that follows one), never
 * moved onto whatever came before a dropped emoji or symbol. The digits and Roman numerals that are
 * another form of ASCII ones are written in ASCII here.
 */
function keepNameCharacters(decomposed: string): string {
  const chars = Array.from(decomposed);
  const classes = chars.map(classOf);
  const keptModifiers = modifierLettersOfNonLatinWords(classes);
  const spaced = punctuationBetweenNonLatinWords(classes, keptModifiers);
  let kept = '';
  // What was written last: a character, a space, or the ASCII of a number
  let lastWritten: string | undefined;
  let previousTakesMarks = false;
  for (let i = 0; i < chars.length; i++) {
    const char = chars[i];
    const charClass = classes[i];
    let written: string | null = null;
    if (spaced[i]) {
      written = ' ';
    } else if (charClass.commonModifierLetter) {
      written = keptModifiers[i] ? char : null;
    } else if (charClass.keptOnItsOwn) {
      written = charClass.space ? ' ' : charClass.asciiNumber ?? char;
    } else if (charClass.mark) {
      written = previousTakesMarks ? char : null;
    } else if (charClass.joiner) {
      written = joinsNonLatinLetters(lastWritten, classes, i, keptModifiers, spaced) ? char : null;
    }
    if (written !== null) {
      kept += written;
      lastWritten = written;
    }
    previousTakesMarks = written !== null && (written === char
      ? charClass.letter || charClass.mark || charClass.joiner
      // A space, or the ASCII of a number: letters for a Roman numeral (XII)
      : LETTER_REGEX.test(written));
  }
  return kept;
}

/**
 * Which characters are modifier letters of the Common script (ー ـ ʼ) kept as part of a non-Latin
 * word: those that follow a non-Latin letter or digit with nothing but marks, joiners and other such
 * modifier letters between (コーヒー, ｶﾞｰﾙ, Ukrainian мʼята). Anywhere else, next to Latin letters,
 * ASCII, punctuation or a space, they are dropped as they always were.
 */
function modifierLettersOfNonLatinWords(classes: CharacterClass[]): boolean[] {
  const kept = new Array<boolean>(classes.length).fill(false);
  let inNonLatinWord = false;
  classes.forEach((charClass, i) => {
    if (charClass.commonModifierLetter) {
      kept[i] = inNonLatinWord;
    } else if (!charClass.mark && !charClass.joiner) {
      // A fullwidth digit or Roman numeral is written in ASCII, after which a modifier letter would not be kept the
      // next time
      inNonLatinWord = charClass.nonLatinLetterOrNumber && charClass.asciiNumber === null;
    }
  });
  return kept;
}

/**
 * Which characters are non-ASCII punctuation or symbols that become a space: those whose nearest kept
 * neighbours on both sides are non-Latin letters or digits (or the marks and modifier letters kept
 * with them), so that "三体·黑暗森林" and "東京・大阪" keep their words apart. Marks, joiners, other
 * punctuation, whatever is dropped and the illegal and control characters removed afterwards
 * (なぜ?・どうして) are looked through. An ASCII character that stays (a space, a digit, a Latin
 * letter) or a non-ASCII Latin letter on either side leaves it dropped as it always was, so Latin
 * names do not change. A fullwidth or mathematical digit counts as the non-Latin digit it is, though
 * it is then written in ASCII (第１・２巻 → 第1 2巻), and so does a Roman numeral, though it is Latin
 * (ドラゴンクエストⅠ・Ⅱ → ドラゴンクエストI II, Episode Ⅰ–Ⅲ → Episode I III): written in ASCII, two
 * numerals the punctuation no longer kept apart would run into another one (III, the name of
 * ドラゴンクエストⅢ). Punctuation that sits inside a word or a number (IN_WORD_PUNCTUATION) is always
 * dropped.
 */
function punctuationBetweenNonLatinWords(classes: CharacterClass[], keptModifiers: boolean[]): boolean[] {
  const afterNonLatin = new Array<boolean>(classes.length).fill(false);
  let nonLatin = false;
  for (let i = 0; i < classes.length; i++) {
    afterNonLatin[i] = nonLatin;
    if (keptModifiers[i] || classes[i].staysOnItsOwn) {
      nonLatin = keptModifiers[i] || classes[i].nonLatinLetterOrNumber;
    }
  }
  const spaced = new Array<boolean>(classes.length).fill(false);
  nonLatin = false;
  for (let i = classes.length - 1; i >= 0; i--) {
    spaced[i] = afterNonLatin[i] && nonLatin && classes[i].separatesWords;
    if (keptModifiers[i] || classes[i].staysOnItsOwn) {
      nonLatin = keptModifiers[i] || classes[i].nonLatinLetterOrNumber;
    }
  }
  return spaced;
}

/** Non-ASCII punctuation, symbols and emoji, but for the punctuation that sits inside a word or a number. */
function separatesWords(char: string): boolean {
  if (ASCII_REGEX.test(char) || IN_WORD_PUNCTUATION.includes(char)) {
    return false;
  }
  // ℹ is a letter, but an emoji too
  return PUNCTUATION_OR_SYMBOL_REGEX.test(char) || (LETTER_REGEX.test(char) && EMOJI_REGEX.test(char));
}

/**
 * Whether the zero-width (non-)joiner at index sits between non-Latin letters or marks once
 * dropped characters are gone, previous being what keepNameCharacters wrote before it. Persian and
 * Indic text need it there; anywhere else it is invisible noise that Latin names never had.
 */
function joinsNonLatinLetters(previous: string | undefined, classes: CharacterClass[], index: number, keptModifiers: boolean[], spaced: boolean[]): boolean {
  if (previous === undefined || (!isNonLatinLetter(previous) && !isMark(previous))) {
    return false;
  }
  let skippedDropped = false;
  for (let i = index + 1; i < classes.length; i++) {
    const next = classes[i];
    if (spaced[i]) {
      // Punctuation that becomes a space ends the word
      return false;
    } else if (next.mark) {
      if (!skippedDropped) {
        // The mark sits on the joiner itself (Sinhala touching letters) and is kept with it
        return true;
      }
      // Dropped along with the character it sits on
    } else if (next.commonModifierLetter) {
      if (keptModifiers[i]) {
        return true;
      }
      skippedDropped = true;
    } else if (!next.keptOnItsOwn && !next.joiner) {
      skippedDropped = true;
    } else {
      return next.nonLatinLetter;
    }
  }
  return false;
}

/**
 * ASCII, letters, decimal digits, the letter numbers of scripts other than Latin (〇), the Roman
 * numerals that are ASCII letters (Ⅷ) and space separators: kept whatever surrounds them. Modifier
 * letters of the Common script depend on what they follow; the letters that are emoji (ℹ) are dropped
 * with the other emoji, and the superscript and subscript letters (ª º ᵉ) with the superscript digits.
 */
function isKeptOnItsOwn(char: string): boolean {
  if (ASCII_REGEX.test(char)) {
    return true;
  }
  if (LETTER_REGEX.test(char)) {
    return !isCommonModifierLetter(char) && !EMOJI_REGEX.test(char) && !isSuperscriptOrSubscriptLetter(char);
  }
  return DECIMAL_DIGIT_REGEX.test(char)
    || (LETTER_NUMBER_REGEX.test(char)
      && ((!LATIN_REGEX.test(char) && !COMMON_SCRIPT_REGEX.test(char)) || asciiNumberOf(char) !== null))
    || SPACE_SEPARATOR_REGEX.test(char);
}

function isMark(char: string): boolean {
  return MARK_REGEX.test(char);
}

function isJoiner(char: string): boolean {
  return char === ZERO_WIDTH_NON_JOINER || char === ZERO_WIDTH_JOINER;
}

/** ー ـ ʼ ˈ and the like: modifier letters used by several scripts, or by none. */
function isCommonModifierLetter(char: string): boolean {
  return MODIFIER_LETTER_REGEX.test(char) && COMMON_SCRIPT_REGEX.test(char);
}

function isNonLatinLetter(char: string): boolean {
  return LETTER_REGEX.test(char) && !LATIN_REGEX.test(char);
}

/**
 * A non-ASCII letter, decimal digit or letter number kept on its own that is not Latin: Arabic, CJK,
 * Cyrillic or Devanagari, say, and the fullwidth digits. The Roman numerals count too, though they are
 * Latin, as numbers that written in ASCII would run into the next (see punctuationBetweenNonLatinWords).
 */
function isNonLatinLetterOrNumber(char: string): boolean {
  return !ASCII_REGEX.test(char)
    && isKeptOnItsOwn(char)
    && !SPACE_SEPARATOR_REGEX.test(char)
    && (!LATIN_REGEX.test(char) || asciiNumberOf(char) !== null);
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
 * Cuts component to at most maxBytes of UTF-8, as the server's truncatePathComponent does. The cut
 * falls between two characters as a reader sees them (a letter and its vowel signs, accents or virama
 * stay together), and whitespace or a zero-width (non-)joiner left at the end of the cut is dropped,
 * so a cut name never ends in a space or in a joiner with nothing to join.
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
  const characterEnd = lastGraphemeBoundary(component, end);
  // A single character longer than the limit (a letter under a pile of marks) is cut where the bytes run out
  if (characterEnd > 0) {
    end = characterEnd;
  }
  // Whitespace and the joiners are all in the BMP, one UTF-16 code unit each
  while (end > 0 && (JAVA_WHITESPACE_REGEX.test(component[end - 1]) || isJoiner(component[end - 1]))) {
    end--;
  }
  return component.substring(0, end);
}

/** The last boundary between two graphemes at or before end, as Java's BreakIterator finds it. */
function lastGraphemeBoundary(text: string, end: number): number {
  if (end >= text.length || !GRAPHEMES) {
    return end;
  }
  let boundary = 0;
  for (const {index} of GRAPHEMES.segment(text)) {
    if (index > end) {
      break;
    }
    boundary = index;
  }
  return boundary;
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
