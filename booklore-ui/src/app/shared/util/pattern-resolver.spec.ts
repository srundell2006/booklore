import {describe, expect, it} from 'vitest';
import {resolveFilePath, sanitize, truncateAuthorsForFilesystem, truncatePathComponent} from './pattern-resolver';

// The move preview must show the names PathPatternResolver.sanitize writes on the server,
// so these cases mirror the backend tests. Invisible characters are written as escapes.

/** sanitize() as it was before non-Latin text was kept: decompose, then drop every non-ASCII code point. */
function asciiOnlySanitize(input: string): string {
  return input.normalize('NFD')
    .replace(/[^\p{ASCII}]/gu, '')
    .replace(/[\\/:*?"<>|]/g, '')
    // eslint-disable-next-line no-control-regex
    .replace(/[\x00-\x1F\x7F]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

describe('pattern-resolver', () => {

  describe('sanitize', () => {

    it('should return an empty string for missing input', () => {
      expect(sanitize(undefined)).toBe('');
      expect(sanitize(null)).toBe('');
      expect(sanitize('')).toBe('');
    });

    it.each([
      ['Harry Potter and the Philosopher’s Stone', 'Harry Potter and the Philosophers Stone'],
      ['A – B', 'A B'],
      ['A — B…', 'A B'],
      ['« Le Rouge et le Noir »', 'Le Rouge et le Noir'],
      ['¿Quién? ¡Ya!', 'Quien Ya!'],
      ['Derrière la haine', 'Derriere la haine'],
      ['Gabriel García Márquez', 'Gabriel Garcia Marquez'],
      ['Tiếng Việt', 'Tieng Viet'],
      ['Café crème'.normalize('NFD'), 'Cafe creme'],
      ['Star Wars™ © 2015 ½', 'Star Wars 2015'],
      ['Title: Sub/Part *1*?', 'Title SubPart 1'],
      ['  Spaced \t  Out  ', 'Spaced Out'],
      ['Love ≠ Hate', 'Love = Hate'],
      ['Auf\u200Clage', 'Auflage'],
      ['a\u200D\u0301b', 'ab'],
      ['\uFEFFZero\u200Bwidth\u200C\u200Djoiners', 'Zerowidthjoiners'],
      ['Stray marks on e\u200B\u0301 and 1\u0301', 'Stray marks on e and 1'],
      ['Tab\tand bell\u0007', 'Taband bell'],
      ['C1\u0085 and \u0092 controls', 'C1 and controls'],
    ])('should clean Latin text as before: %j', (input, expected) => {
      expect(sanitize(input)).toBe(expected);
      expect(sanitize(input)).toBe(asciiOnlySanitize(input));
    });

    it('should keep Latin letters that have no accent mark to strip', () => {
      expect(sanitize('Søren Kierkegaard')).toBe('Søren Kierkegaard');
      expect(sanitize('Władysław Reymont')).toBe('Władysław Reymont');
      expect(sanitize('Frygt og Bæven')).toBe('Frygt og Bæven');
      expect(sanitize('æ œ ß đ ð þ ı ħ ŋ')).toBe('æ œ ß đ ð þ ı ħ ŋ');
    });

    it.each([
      ['Arabic', 'نجيب محفوظ'],
      ['Chinese', '中国文化合集'],
      ['Japanese', 'ガイドがある'],
      ['Hindi', 'प्रेमचंद'],
      ['Russian', 'Война и мир'],
      ['Greek', 'Αλέξανδρος Παπαδιαμάντης'],
      ['Korean', '소년이 온다'],
      ['Persian with a zero-width non-joiner', 'می\u200Cخواهم'],
      ['Devanagari with a zero-width joiner', 'क्\u200Dष'],
      ['Bengali with a zero-width joiner before a virama', 'র\u200D্যাব'],
      ['Sinhala touching letters', 'ක\u200D්ව'],
      ['Arabic with a mark after a zero-width non-joiner', 'ب\u200C\u064E'],
      ['Arabic-Indic digits', 'الجزء ٣'],
      ['Thai', 'สวัสดี'],
    ])('should keep %s text whole', (_, text) => {
      expect(sanitize(text)).toBe(text);
    });

    it('should keep Hebrew points, in canonical order', () => {
      const shalom = '\u05E9\u05C1\u05B8\u05DC\u05D5\u05B9\u05DD';
      expect(sanitize(shalom)).toBe(shalom.normalize('NFC'));
    });

    it('should strip Latin accents and keep other scripts in mixed text', () => {
      expect(sanitize('Le Petit Prince (星の王子さま)')).toBe('Le Petit Prince (星の王子さま)');
      expect(sanitize('Saint-Exupéry — 《三体》。')).toBe('Saint-Exupery 三体');
    });

    it('should turn Unicode spaces into plain spaces', () => {
      expect(sanitize('Titre\u00A0:\u00A0sous-titre')).toBe('Titre sous-titre');
      expect(sanitize('A\u3000B\u2009C\u202FD')).toBe('A B C D');
    });

    it('should drop control, bidi, zero-width and soft hyphen characters', () => {
      expect(sanitize('a\u0085b\u009Fc')).toBe('abc');
      expect(sanitize('\u200ERTL\u200F \u202ALTR\u202C \u2066iso\u2069')).toBe('RTL LTR iso');
      expect(sanitize('\uFEFFzero\u200Bwidth')).toBe('zerowidth');
      expect(sanitize('soft\u00ADhyphen')).toBe('softhyphen');
    });

    it('should drop joiners that do not sit between non-Latin letters', () => {
      expect(sanitize('\u200Cمی\u200C')).toBe('می');
      expect(sanitize('می\u200E\u200Cخ')).toBe('می\u200Cخ');
      expect(sanitize('a \u200D b')).toBe('a b');
      expect(sanitize('é\u200Cb')).toBe('eb');
      expect(sanitize('ک\u200C \u0628')).toBe('ک \u0628');
      expect(sanitize('ک\u200C\u{1F600}')).toBe('ک');
    });

    it('should keep one of two joiners in a row', () => {
      expect(sanitize('می\u200C\u200Cخواهم')).toBe('می\u200Cخواهم');
    });

    it('should drop emoji along with their modifiers, selectors and joiners', () => {
      expect(sanitize('Title \u2764\uFE0F')).toBe('Title');
      expect(sanitize('\u{1F44D}\u{1F3FD} Good')).toBe('Good');
      expect(sanitize('\u{1F468}\u200D\u{1F469}\u200D\u{1F467} Family')).toBe('Family');
      expect(sanitize('1\uFE0F\u20E3 One')).toBe('1 One');
    });

    it('should drop lone surrogates and private-use characters', () => {
      expect(sanitize('x\uD800y\uDC00z')).toBe('xyz');
      expect(sanitize('a\uE000b')).toBe('ab');
    });

    it.each([
      '0085', '0092', '009F', '200E', '200F', '202A', '202B', '202C', '202D', '202E', '2066', '2067', '2068',
      '2069', 'FEFF', '200B', '00AD', '2060', '2122', '00A9', '00BD', '2764', '1F600', '1F1EB', '1F3FD',
      'E000', 'F0000', '0378', 'D800', 'DC00',
    ])('should never put U+%s in a name', hex => {
      const char = String.fromCodePoint(parseInt(hex, 16));
      expect(sanitize(`Naguib${char}Mahfouz`)).toBe('NaguibMahfouz');
      expect(sanitize(`قصر${char}الشوق`)).toBe('قصرالشوق');
      expect(sanitize(`中国${char} ${char}文化`)).toBe('中国 文化');
    });

    it.each([
      'Harry Potter and the Philosopher’s Stone',
      'Gabriel García Márquez',
      'ガイドがある'.normalize('NFD'),
      'می\u200Cخواهم',
      'Titre\u00A0:\u00A0sous-titre',
      '\u1100?\u1161',
      'Title \u2764\uFE0F \uD800',
      'e\u200B\u0301',
      '\u0301leading mark',
      'ک\u200C\u200C\u0628',
      'ک\u200C\u{1F600}\u0628',
      'ක\u200D්ව',
      'ක\u2764\u200D්',
      'a\u200D\u0301b',
      'Love ≠ Hate',
      '\u202Bقصر\u200F الشوق\u202C',
      '1\uFE0F\u20E3',
    ])('should give a stable NFC result for %j', input => {
      const once = sanitize(input);
      expect(sanitize(once)).toBe(once);
      expect(once.normalize('NFC')).toBe(once);
    });
  });

  // PathPatternResolver caps values and path components in UTF-8 bytes, which non-Latin names
  // reach long before Latin ones do
  describe('truncatePathComponent', () => {

    it('should leave a component within the limit unchanged', () => {
      expect(truncatePathComponent('The Hobbit', 200)).toBe('The Hobbit');
      expect(truncatePathComponent('ب'.repeat(100), 200)).toBe('ب'.repeat(100));
    });

    it('should cut at the last whole code point that fits', () => {
      expect(truncatePathComponent('ب'.repeat(120), 200)).toBe('ب'.repeat(100));
      expect(truncatePathComponent('某'.repeat(70), 200)).toBe('某'.repeat(66));
      expect(truncatePathComponent('\u{10000}'.repeat(60), 202)).toBe('\u{10000}'.repeat(50));
    });
  });

  describe('truncateAuthorsForFilesystem', () => {

    it('should leave an author list within 180 bytes unchanged', () => {
      expect(truncateAuthorsForFilesystem('John Doe, Jane Smith')).toBe('John Doe, Jane Smith');
      expect(truncateAuthorsForFilesystem('某'.repeat(60))).toBe('某'.repeat(60));
    });

    it('should cut the list after the last author that fits and add et al.', () => {
      const authors = Array.from({length: 10}, () => 'A'.repeat(20));
      expect(truncateAuthorsForFilesystem(authors.join(', '))).toBe(`${authors.slice(0, 8).join(', ')} et al.`);
    });

    it('should cut a first author that alone is too long', () => {
      expect(truncateAuthorsForFilesystem('某'.repeat(100))).toBe(`${'某'.repeat(57)} et al.`);
      expect(truncateAuthorsForFilesystem(`${'某'.repeat(100)}, Jane Smith`)).toBe(`${'某'.repeat(57)} et al.`);
    });
  });

  describe('resolveFilePath', () => {

    const hobbit = {authors: 'J.R.R. Tolkien', title: 'The Hobbit', series: ''};

    it('should give a book file the extension of its current name', () => {
      expect(resolveFilePath('{authors}/{title}', hobbit, 'the_hobbit.epub', false)).toBe('J.R.R. Tolkien/The Hobbit.epub');
    });

    it('should not add the extension again when the pattern places it', () => {
      expect(resolveFilePath('{title}.{extension}', hobbit, 'the_hobbit.epub', false)).toBe('The Hobbit.epub');
      expect(resolveFilePath('{authors}/{currentFilename}', {...hobbit, currentFilename: 'the_hobbit.epub'}, 'the_hobbit.epub', false))
        .toBe('J.R.R. Tolkien/the_hobbit.epub');
    });

    it('should not give a folder audiobook an extension from the dot in its name', () => {
      const values = {seriesIndex: '01', title: 'The Assassin\'s Blade', authors: 'Sarah J. Maas', year: '2015'};
      expect(resolveFilePath('{seriesIndex}. {title} - {authors} ({year})', values, '01. The Assassin\'s Blade', true))
        .toBe('01. The Assassin\'s Blade - Sarah J. Maas (2015)');
    });

    it('should drop trailing dots from the folders of a book file but not of a folder audiobook', () => {
      const values = {authors: 'Tolkien J.R.R.', title: 'The Hobbit'};
      expect(resolveFilePath('{authors}/{title}', values, 'the_hobbit.epub', false)).toBe('Tolkien J.R.R/The Hobbit.epub');
      expect(resolveFilePath('{authors}/{title}', values, 'The Hobbit', true)).toBe('Tolkien J.R.R./The Hobbit');
    });

    it('should fall back to the current name when the pattern resolves to nothing', () => {
      expect(resolveFilePath('{series}', hobbit, 'the_hobbit.epub', false)).toBe('the_hobbit.epub');
      expect(resolveFilePath('  ', hobbit, 'the_hobbit.epub', false)).toBe('the_hobbit.epub');
    });

    it('should keep unknown placeholders, as the server does', () => {
      expect(resolveFilePath('{title} {titel}', hobbit, 'the_hobbit.epub', false)).toBe('The Hobbit {titel}.epub');
    });

    it('should cut a long file name to 245 bytes and keep its extension', () => {
      const values = {title: 'ب'.repeat(100), authors: `${'某'.repeat(57)} et al.`};
      expect(resolveFilePath('{title} - {authors}', values, 'book.epub', false)).toBe(`${'ب'.repeat(100)} - ${'某'.repeat(12)}.epub`);
      expect(resolveFilePath('{title} - {authors}', values, 'Book', true)).toBe(`${'ب'.repeat(100)} - ${'某'.repeat(14)}`);
    });

    it('should cut every folder in the path to 245 bytes', () => {
      const values = {series: 'Ж'.repeat(100), publisher: 'Ж'.repeat(30), title: 'Ж'};
      expect(resolveFilePath('{series} - {publisher}/{title}', values, 'book.epub', false))
        .toBe(`${'Ж'.repeat(100)} - ${'Ж'.repeat(21)}/Ж.epub`);
    });

    it('should keep the space a title cut to its byte limit ends with, as the server does', () => {
      const title = truncatePathComponent(sanitize(`${'ب'.repeat(99)} ب`), 200);
      expect(resolveFilePath('{title}', {title}, 'book.epub', false)).toBe(`${'ب'.repeat(99)} .epub`);
    });
  });
});
