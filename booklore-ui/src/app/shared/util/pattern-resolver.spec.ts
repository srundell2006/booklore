import {describe, expect, it} from 'vitest';
import {sanitize} from './pattern-resolver';

// The move preview must show the names PathPatternResolver.sanitize writes on the server,
// so these cases mirror the backend tests. Invisible characters are written as escapes.
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
    ])('should clean Latin text as before: %j', (input, expected) => {
      expect(sanitize(input)).toBe(expected);
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
    ])('should keep %s text whole', (_, text) => {
      expect(sanitize(text)).toBe(text);
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

    it('should drop joiners that do not sit between letters', () => {
      expect(sanitize('\u200Cمی\u200C')).toBe('می');
      expect(sanitize('می\u200E\u200Cخ')).toBe('می\u200Cخ');
      expect(sanitize('a \u200D b')).toBe('a b');
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
      'Harry Potter and the Philosopher’s Stone',
      'Gabriel García Márquez',
      'ガイドがある'.normalize('NFD'),
      'می\u200Cخواهم',
      'Titre\u00A0:\u00A0sous-titre',
      '\u1100?\u1161',
      'Title \u2764\uFE0F \uD800',
    ])('should give a stable NFC result for %j', input => {
      const once = sanitize(input);
      expect(sanitize(once)).toBe(once);
      expect(once.normalize('NFC')).toBe(once);
    });
  });
});
