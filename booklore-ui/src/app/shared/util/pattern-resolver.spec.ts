import {describe, expect, it} from 'vitest';
import {MAX_COMPONENT_BYTES, resolveFilePath, sanitize, truncateAuthorsForFilesystem, truncatePathComponent} from './pattern-resolver';

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
      // Modifier letters of no script (U+02BC apostrophe, okina, stress mark) are dropped next to Latin letters
      ['Winter\u02BCs Tale', 'Winters Tale'],
      ['Hawai\u02BBi and \u02C8stress', 'Hawaii and stress'],
      // Numbers that are not digits, symbols that spell letters and the letters that are emoji are dropped
      ['E=mc\u00B2 \u00BD', 'E=mc'],
      ['Chapter \u2460', 'Chapter'],
      ['Roman \u2180', 'Roman'],
      ['Star Wars\u2122 \u2116 4', 'Star Wars 4'],
      ['Info \u2139\uFE0F and Metro \u24C2\uFE0F', 'Info and Metro'],
      ['Parenthesized \u249C\u249D', 'Parenthesized'],
      // Superscript and ordinal letters are dropped like superscript digits, rather than written in ASCII
      ['N\u00BA 5 and 1\u00AA edici\u00F3n', 'N 5 and 1 edicion'],
      ['451\u00BA Fahrenheit', '451 Fahrenheit'],
      ['XIX\u1D49 si\u00E8cle and M\u02E1\u02E1\u1D49 Dupont', 'XIX siecle and M Dupont'],
      ['2\u207F and x\u2090', '2 and x'],
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

    it.each([
      // These letters used to vanish ("Signicance", "Henry"); they are now written as the ASCII they stand for
      ['The Signi\uFB01cance of \uFB02ow', 'The Significance of flow'],
      ['Henry \u2167 and Louis \u2169\u2163', 'Henry VIII and Louis XIV'],
      ['ドラゴンクエスト\u2162', 'ドラゴンクエストIII'],
      ['\uFF28\uFF41\uFF52\uFF52\uFF59 \uFF11', 'Harry 1'],
      ['\u{1D401}\u{1D428}\u{1D425}\u{1D41D} \u{1D7CF}', 'Bold 1'],
      ['\u24B6\u24D1\u24D2 \u{1F130}BC', 'Abc ABC'],
      ['\u0132sselmeer', 'IJsselmeer'],
      ['Wei\u017Fs and \u01C8ubljana', 'Weiss and Ljubljana'],
      ['\u{1D400}\u0301 and \uFF25\u0301', 'A and E'],
    ])('should write letters that are another form of ASCII ones in ASCII: %j', (input, expected) => {
      expect(sanitize(input)).toBe(expected);
    });

    it('should leave alone compatibility characters that are more than ASCII letters', () => {
      // Ǆ is D + Ž, not plain ASCII, and stays the letter it is; the rest are not letters, or superscripts, or a
      // Roman numeral that is more than ASCII letters (ↀ), and are dropped as before
      expect(sanitize('\u01C4 \u00B2\u00BD\u2460\u2180\u2122\u2116\u249C \u00BA\u1D49\u2139\u24C2 x')).toBe('\u01C4 x');
    });

    it('should keep the volumes of a title apart with their Roman numerals', () => {
      // They used to vanish, so that volumes II and III of a title went to the same path
      expect(sanitize('ドラゴンクエスト\u2161')).not.toBe(sanitize('ドラゴンクエスト\u2162'));
      // Dropped, the middle dot would run I and II into III, the name of the third volume
      expect(sanitize('ドラゴンクエスト\u2160・\u2161 公式ガイドブック')).toBe('ドラゴンクエストI II 公式ガイドブック');
      expect(sanitize('ドラゴンクエスト\u2160・\u2161 公式ガイドブック')).not.toBe(sanitize('ドラゴンクエスト\u2162 公式ガイドブック'));
      expect(sanitize('Star Wars Episode \u2160\u2013\u2162')).toBe('Star Wars Episode I III');
      expect(sanitize('Star Wars Episode \u2160\u2013\u2162')).not.toBe(sanitize('Star Wars Episode \u2163'));
    });

    it.each([
      ['ドラゴンクエスト\u2160・\u2161', 'ドラゴンクエストI II'],
      ['化学\u2160・\u2161の新研究', '化学I IIの新研究'],
      ['\u2160・\u2161・\u2162', 'I II III'],
      ['三体\u2161·黑暗森林', '三体II 黑暗森林'],
      ['第\u2161・１巻', '第II 1巻'],
      ['Star Wars Episode \u2160\u2013\u2162', 'Star Wars Episode I III'],
      ['Episodes \u2163\u2013\u2165', 'Episodes IV VI'],
      ['\u2170·\u2171', 'i ii'],
      // Numerals in a row are one number (ⅩⅢ is 13), and a Latin letter or an ASCII digit beside one leaves the
      // punctuation dropped, as beside a fullwidth digit
      ['\u2169\u2162', 'XIII'],
      ['Part\u2013\u2161', 'PartII'],
      ['\u2161\u2013B', 'IIB'],
      ['\u2161·2', 'II2'],
      ['Henry \u2167\u2019s Wives', 'Henry VIIIs Wives'],
      // A modifier letter after a numeral is dropped, as it is after the ASCII letters the numeral becomes
      ['ドラゴンクエスト\u2161\u30FC', 'ドラゴンクエストII'],
    ])('should turn punctuation between Roman numerals into a space: %j', (input, expected) => {
      // They are Latin, but written in ASCII two of them would run into another numeral, as the digits of 第１・２巻
      // would run into another number
      expect(sanitize(input)).toBe(expected);
    });

    it.each([
      ['a Chinese year with 〇', '二〇〇八年'],
      ['Hangzhou numerals', '\u3021\u3022\u3029'],
    ])('should keep the letter numbers of %s', (_, text) => {
      expect(sanitize(text)).toBe(text);
    });

    it.each([
      ['Katakana with prolonged sound marks', '\u30B3\u30FC\u30D2\u30FC'],
      ['halfwidth katakana', '\uFF76\uFF9E\uFF70\uFF99'],
      ['Arabic with a tatweel', '\u06A9\u0640\u062A\u0627\u0628'],
      ['Ukrainian with a modifier apostrophe', '\u043C\u02BC\u044F\u0442\u0430'],
    ])('should keep the modifier letters of %s', (_, text) => {
      expect(sanitize(text)).toBe(text);
    });

    it.each([
      ['三体·黑暗森林', '三体 黑暗森林'],
      ['東京・大阪', '東京 大阪'],
      ['こんにちは。世界', 'こんにちは 世界'],
      ['ハリー・ポッター', 'ハリー ポッター'],
      ['Война—мир', 'Война мир'],
      ['《三体》《黑暗森林》', '三体 黑暗森林'],
      // A decimal separator too: dropped, it would make another number of 20.5 (205)
      ['٢٠\u066B٥ مليون', '٢٠ ٥ مليون'],
      ['۳\u066B۵', '۳ ۵'],
    ])('should turn punctuation between non-Latin words into a space: %j', (input, expected) => {
      // It used to glue the words together ("三体黑暗森林")
      expect(sanitize(input)).toBe(expected);
    });

    it.each([
      ['第１・２巻', '第1 2巻'],
      ['第１．５巻', '第1 5巻'],
      ['\uD835\uDFCF·\uD835\uDFD0', '1 2'],
      // ASCII digits leave it dropped, as for Latin names
      ['第1・2巻', '第12巻'],
      ['Vol. 1·2', 'Vol. 12'],
      // A modifier letter after a fullwidth digit is dropped, as it is after the ASCII digit it becomes
      ['１ー２', '12'],
    ])('should turn punctuation between fullwidth digits into a space: %j', (input, expected) => {
      // The digits are written in ASCII, but count as the non-Latin digits they are
      expect(sanitize(input)).toBe(expected);
    });

    it.each([
      ['なぜ?・どうして', 'なぜ どうして'],
      ['三体\t·黑暗', '三体 黑暗'],
      ['三体:·黑暗', '三体 黑暗'],
      ['三体·*黑暗*', '三体 黑暗'],
      // Without non-ASCII punctuation, ASCII is handled as before
      ['なぜ?どうして', 'なぜどうして'],
      ['Tab\t·and?·more', 'Tabandmore'],
    ])('should look through the characters it removes when turning punctuation into a space: %j', (input, expected) => {
      // The question mark, tab, colon and asterisks are removed from the name, so they are not the neighbours that decide
      expect(sanitize(input)).toBe(expected);
    });

    it.each([
      ['《三体》', '三体'],
      ['こんにちは。', 'こんにちは'],
      ['الثلاثية، الجزء الأول', 'الثلاثية الجزء الأول'],
      ['三体 · 黑暗森林', '三体 黑暗森林'],
      ['三体·2', '三体2'],
      ['東京·Tokyo', '東京Tokyo'],
      ['Bjørk·東京', 'Bjørk東京'],
      ['п\u2019ять', 'пять'],
      ['Жанна д\u2019Арк', 'Жанна дАрк'],
      ['三体-黑暗', '三体-黑暗'],
      ['三体:黑暗', '三体黑暗'],
      ['١\u066C٠٠٠ ليلة', '١٠٠٠ ليلة'],
      ['۱\u066C۰۰۰', '۱۰۰۰'],
      ['Ինչո\u055Eւ', 'Ինչու'],
      ['Ինչո\u055Bւ', 'Ինչու'],
      ['Ինչո\u055Cւ', 'Ինչու'],
      ['ト\u309Bラコ\u309Bン', 'トラコン'],
      ['ハ\u309Cン', 'ハン'],
    ])('should drop punctuation at the edge of a word, next to ASCII or Latin text, or inside a word: %j', (input, expected) => {
      // ASCII and Latin neighbours keep Latin names as they were; an apostrophe sits inside a word
      expect(sanitize(input)).toBe(expected);
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
      expect(sanitize('ک\u200C\u00B7ب')).toBe('ک ب');
    });

    it('should keep one of two joiners in a row', () => {
      expect(sanitize('می\u200C\u200Cخواهم')).toBe('می\u200Cخواهم');
    });

    it('should drop emoji along with their modifiers, selectors and joiners', () => {
      expect(sanitize('Title \u2764\uFE0F')).toBe('Title');
      expect(sanitize('\u{1F44D}\u{1F3FD} Good')).toBe('Good');
      expect(sanitize('\u{1F468}\u200D\u{1F469}\u200D\u{1F467} Family')).toBe('Family');
      expect(sanitize('1\uFE0F\u20E3 One')).toBe('1 One');
      expect(sanitize('猫\u{1F468}\u200D\u{1F469}\u200D\u{1F467}狗')).toBe('猫 狗');
    });

    it('should drop lone surrogates and private-use characters', () => {
      expect(sanitize('x\uD800y\uDC00z')).toBe('xyz');
      expect(sanitize('a\uE000b')).toBe('ab');
    });

    it.each([
      '0085', '0092', '009F', '200E', '200F', '202A', '202B', '202C', '202D', '202E', '2066', '2067', '2068',
      '2069', 'FEFF', '200B', '00AD', '2060', '00BD', '00B2', '2460', '2180', '00AA', '00BA', '1D49', '207F', '2090',
      'E000', 'F0000', '0378', 'D800', 'DC00',
    ])('should never put U+%s in a name', hex => {
      const char = String.fromCodePoint(parseInt(hex, 16));
      expect(sanitize(`Naguib${char}Mahfouz`)).toBe('NaguibMahfouz');
      expect(sanitize(`قصر${char}الشوق`)).toBe('قصرالشوق');
      expect(sanitize(`中国${char} ${char}文化`)).toBe('中国 文化');
    });

    it.each(['2122', '00A9', '2764', '1F600', '1F1EB', '1F3FD', '2116', '2139', '24C2', '00B7', '2014', '066B'])(
      'should never put U+%s in a name, but keep non-Latin words apart with it', hex => {
        const char = String.fromCodePoint(parseInt(hex, 16));
        expect(sanitize(`Naguib${char}Mahfouz`)).toBe('NaguibMahfouz');
        expect(sanitize(`قصر${char}الشوق`)).toBe('قصر الشوق');
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
      '三体·黑暗森林',
      'ハリー・ポッター',
      'ک\u200C\u00B7\u0628',
      '\u30AB\u00B7\u30FC\u30AD',
      '\u6771\u30FC\u00B7a',
      'ک\u200D\u0640',
      '\uFF76\uFF9E\uFF70',
      'Winter\u02BCs',
      '\u{1D400}\u0301',
      '\u24B6\u2139\u24C2',
      '\u4E2D\u3001\u3001\u200C\u6587',
      '\u30AB\u3099\u00B7\u3099\u30AD',
      '第１・２巻',
      '１ー２',
      '\uFF11\u200C·\uFF12',
      'なぜ?・どうして',
      '٢٠\u066B٥',
      '١\u066C٠٠٠',
      'Ինչո\u055Eւ',
      'ト\u309Bラ',
      '二〇〇八',
      'N\u00BA 5',
      '\uD801\uDF80\u0301',
      '\uD837\uDF00\u0301\u200C\u0628',
      '三体\u2161·黑暗',
      'ドラゴンクエスト\u2160・\u2161',
      '\u2160・\u2161・\u2162',
      'Episode \u2160\u2013\u2162',
      'Part\u2013\u2161',
      '\u2161\u30FC\u00B7\u30B3',
      '\u2161\u200D\u4E2D',
    ])('should give a stable NFC result for %j', input => {
      const once = sanitize(input);
      expect(sanitize(once)).toBe(once);
      expect(once.normalize('NFC')).toBe(once);
    });

    // The cases the move preview must agree on with the server: PathPatternResolverScriptTest in booklore-api asserts
    // this same table (SHARED_SANITIZE_CASES), so a row added or changed here belongs there too. Each row is a
    // description, a title and the name sanitize() makes of it.
    const SHARED_SANITIZE_CASES: [string, string, string][] = [
      // Punctuation and symbols between non-Latin words become a space
      ["middle dot between Chinese words", "三体·黑暗森林", "三体 黑暗森林"],
      ["katakana middle dot", "東京・大阪", "東京 大阪"],
      ["middle dot after a prolonged sound mark", "ハリー・ポッター", "ハリー ポッター"],
      ["fullwidth colon", "三体\uFF1A黑暗森林", "三体 黑暗森林"],
      ["ideographic full stop", "こんにちは。世界", "こんにちは 世界"],
      ["em dash between Cyrillic words", "Война—мир", "Война мир"],
      ["danda between Hindi words", "गोदान।प्रेमचंद", "गोदान प्रेमचंद"],
      ["Arabic comma before an Arabic-Indic digit", "الجزء،٣", "الجزء ٣"],
      ["symbol between Arabic words", "قصر™الشوق", "قصر الشوق"],
      ["emoji between Chinese words", "猫\uD83D\uDE00狗", "猫 狗"],
      ["emoji sequence between Chinese words", "猫\uD83D\uDC68\u200D\uD83D\uDC69狗", "猫 狗"],
      ["two in a row make one space", "三体·—黑暗", "三体 黑暗"],
      ["after a letter with a mark", "カ\u3099·キ\u3099", "ガ ギ"],
      ["joiner before it is dropped", "ک\u200C·ب", "ک ب"],
      // and are dropped as before anywhere else
      ["brackets at the edges", "《三体》", "三体"],
      ["comma before a space", "الثلاثية، الجزء الأول", "الثلاثية الجزء الأول"],
      ["already spaced", "三体 · 黑暗", "三体 黑暗"],
      ["next to an ASCII digit", "三体·2", "三体2"],
      ["next to a Latin word", "東京·Tokyo", "東京Tokyo"],
      ["next to a Latin letter that is not ASCII", "Bjørk·東京", "Bjørk東京"],
      ["Latin apostrophe", "Harry Potter and the Philosopher\u2019s Stone", "Harry Potter and the Philosophers Stone"],
      ["Latin dash", "A\u2013B", "AB"],
      ["Latin quotes and middle dot", "«Les Misérables» · Tome 1", "Les Miserables Tome 1"],
      ["apostrophe inside a Ukrainian word", "п\u2019ять", "пять"],
      ["apostrophe in a Russian name", "Жанна д\u2019Арк", "Жанна дАрк"],
      ["Hebrew geresh", "ג\u05F3ורג\u05F3", "גורג"],
      ["Arabic thousands separator", "١\u066C٠٠٠ ليلة", "١٠٠٠ ليلة"],
      ["Persian thousands separator", "۱\u066C۰۰۰", "۱۰۰۰"],
      ["Armenian question mark over a vowel", "Ինչո\u055Eւ", "Ինչու"],
      ["Armenian emphasis mark", "Ինչո\u055Bւ", "Ինչու"],
      ["Armenian exclamation mark", "Ինչո\u055Cւ", "Ինչու"],
      ["spacing dakuten", "ト\u309Bラコ\u309Bン", "トラコン"],
      ["spacing handakuten", "ハ\u309Cン", "ハン"],
      ["zero-width space, which is not punctuation", "中国\u200B文化", "中国文化"],
      ["fraction, which is not punctuation", "中国\u00BD文化", "中国文化"],
      // A decimal separator becomes a space too: dropped, it would make another number of 20.5 (205)
      ["Arabic decimal separator", "٢٠\u066B٥ مليون", "٢٠ ٥ مليون"],
      ["Persian decimal separator", "۳\u066B۵", "۳ ۵"],
      // Fullwidth and mathematical digits count as the non-Latin digits they are, though written in ASCII
      ["between fullwidth digits", "第１・２巻", "第1 2巻"],
      ["fullwidth full stop between fullwidth digits", "第１．５巻", "第1 5巻"],
      ["between mathematical digits", "\uD835\uDFCF·\uD835\uDFD0", "1 2"],
      ["between ASCII digits, as before", "第1・2巻", "第12巻"],
      ["modifier letter after a fullwidth digit", "１ー２", "12"],
      // Characters removed later are looked through
      ["after a question mark that is removed", "なぜ?・どうして", "なぜ どうして"],
      ["after a tab that is removed", "三体\t·黑暗", "三体 黑暗"],
      ["after a colon that is removed", "三体:·黑暗", "三体 黑暗"],
      ["Latin text around removed characters", "Tab\t·and?·more", "Tabandmore"],
      // ASCII punctuation is handled as before
      ["ASCII hyphen", "三体-黑暗", "三体-黑暗"],
      ["ASCII colon", "三体:黑暗", "三体黑暗"],
      ["ASCII question mark", "なぜ?どうして", "なぜどうして"],
      // Letters and digits that are another form of ASCII ones are written in ASCII
      ["ligatures", "The Signi\uFB01cance of \uFB02ow", "The Significance of flow"],
      ["fullwidth letters and digits", "\uFF28\uFF41\uFF52\uFF52\uFF59 \uFF11", "Harry 1"],
      ["mathematical letters and digits", "\uD835\uDC01\uD835\uDC28\uD835\uDC25\uD835\uDC1D \uD835\uDFCF", "Bold 1"],
      ["circled and squared letters", "\u24B6\u24D1\u24D2 \uD83C\uDD30", "Abc A"],
      ["Dutch ij and long s", "\u0132sselmeer Wei\u017Fs", "IJsselmeer Weiss"],
      ["accent on a mathematical letter", "\uD835\uDC00\u0301", "A"],
      ["inside non-Latin text", "中\uFB01文", "中fi文"],
      ["Roman numerals", "Henry \u2167", "Henry VIII"],
      ["Roman numeral in a Japanese title", "ドラゴンクエスト\u2162", "ドラゴンクエストIII"],
      // Roman numerals count as numbers, though Latin, so that two of them do not run into another one
      ["Roman numeral before Chinese punctuation", "三体\u2161·黑暗森林", "三体II 黑暗森林"],
      ["between Roman numerals in a Japanese title", "ドラゴンクエスト\u2160・\u2161", "ドラゴンクエストI II"],
      ["between Roman numerals in a Latin title", "Episode \u2160\u2013\u2162", "Episode I III"],
      ["between small Roman numerals", "\u2170\u00B7\u2171", "i ii"],
      ["between a Roman numeral and a fullwidth digit", "第\u2161・\uFF11巻", "第II 1巻"],
      ["Roman numerals in a row, which are one number", "\u2169\u2162", "XIII"],
      ["Roman numeral next to a Latin word", "Part\u2013\u2161", "PartII"],
      ["Roman numeral next to an ASCII digit", "\u2161\u00B72", "II2"],
      ["modifier letter after a Roman numeral", "ドラゴンクエスト\u2161\u30FC", "ドラゴンクエストII"],
      // and the rest are left as they were
      ["ligature that is not plain ASCII stays a letter", "\u01C4", "\u01C4"],
      ["parenthesized letters, which are more than letters", "A\u249C\u249DB", "AB"],
      ["superscript digits and fractions", "E=mc\u00B2 \u00BD", "E=mc"],
      ["ordinal indicators", "N\u00BA 5 and 1\u00AA edición", "N 5 and 1 edicion"],
      ["ordinal indicator typed for a degree sign", "451\u00BA Fahrenheit", "451 Fahrenheit"],
      ["superscript letter", "XIX\u1D49 siècle", "XIX siecle"],
      ["superscript and subscript letters", "M\u02E1\u02E1\u1D49 2\u207F x\u2090", "M 2 x"],
      ["Roman numeral with no ASCII form", "\u2180 x", "x"],
      ["circled digits", "Chapter \u2460", "Chapter"],
      ["symbols that spell letters", "Pokémon\u2122 \u2116 5", "Pokemon 5"],
      ["letters that are emoji", "Info \u2139\uFE0F Metro \u24C2\uFE0F", "Info Metro"],
      // Modifier letters of no script are dropped next to Latin text and kept in non-Latin words
      ["modifier apostrophe in a Latin word", "Winter\u02BCs Tale", "Winters Tale"],
      ["okina", "Hawai\u02BBi", "Hawaii"],
      ["stress mark", "\u02C8stress", "stress"],
      ["before a Latin word", "\u30FCstart", "start"],
      ["prolonged sound marks", "コ\u30FCヒ\u30FC", "コ\u30FCヒ\u30FC"],
      ["prolonged sound mark before Latin letters", "ファイナルファンタジ\u30FCVII", "ファイナルファンタジ\u30FCVII"],
      ["halfwidth katakana", "\uFF76\uFF9E\uFF70\uFF99", "\uFF76\uFF9E\uFF70\uFF99"],
      ["Arabic tatweel", "ک\u0640تاب", "ک\u0640تاب"],
      ["modifier apostrophe in a Ukrainian word", "м\u02BCята", "м\u02BCята"],
      ["Greek numeral sign", "\u0391\u0374", "\u0391\u02B9"],
      // Letter numbers of other scripts are kept like their digits
      ["ideographic zero", "二〇〇八年", "二〇〇八年"],
      ["Hangzhou numerals", "\u3021\u3022\u3029", "\u3021\u3022\u3029"],
      // Accents are stripped from Latin letters outside the BMP too
      ["accent on a Latin letter outside the BMP", "\uD801\uDF80\u0301", "\uD801\uDF80"],
      ["accent on a Latin letter outside the BMP before a joiner", "\uD837\uDF00\u0301\u200C\u0628", "\uD837\uDF00\u0628"],
    ];

    it.each(SHARED_SANITIZE_CASES)('should agree with the server on %s', (_, input, expected) => {
      expect(sanitize(input)).toBe(expected);
    });

    // Characters that occur in and around names in Latin script, and those of other scripts
    const LATIN_NAME_CHARACTERS = [
      'a', 'Z', 'e', '1', ' ', '-', '.', '\'', '_', ':', '?', '\t', 0xE9, 0x0301, 0x0308, 0xFE0F, 0x20E3, 0x200C,
      0x200D, 0x200B, 0x200E, 0xFEFF, 0xAD, 0x85, 0x2019, 0x2018, 0x2013, 0x2014, 0x2026, 0xAB, 0xBB, 0xB7, 0x30FB,
      0x3002, 0xFF0C, 0xFF1A, 0x300A, 0x2122, 0xA9, 0xBD, 0xB2, 0x2460, 0x2139, 0x24C2, 0x1F600, 0x1F3FD,
      0x1F1EB, 0x2260, 0x2116, 0x02BC, 0x02C8, 0x02BB, 0x02B9, 0x0374, 0x30FC, 0x0640, 0xFF70, 0xFF9E, 0x3031, 0xE000,
      0x0378, 0x249C, 0x05F3, 0x2032, 0xB4, 0xAA, 0xBA, 0x1D49, 0x207F, 0x2090, 0x02E1, 0x2180, 0x066B, 0x066C, 0x055E,
      0x309B, 0xFF0E, 0x7F, '*', '"',
    ].map(c => typeof c === 'string' ? c : String.fromCodePoint(c));
    const OTHER_NAME_CHARACTERS = [
      0x4E09, 0x4F53, 0x30AC, 0x30AB, 0x3099, 0xFF76, 0x06A9, 0x0628, 0x064E, 0x043F, 0x0414, 0x0915, 0x094D, 0x0D9A,
      0x0DCA, 0x05E9, 0x05C1, 0x0E2A, 0x1100, 0x1161, 0xAC00, 0x0663, 0x00F8, 0x0142, 0x00A0, 0x3000, 0x2028, 0xFB01,
      0xFF21, 0xFF11, 0xFF12, 0x1D401, 0x1D7CF, 0x017F, 0x0133, 0x01C5, 0x01C8, 0xB5, 0x24B6, 0x1F130, 0x0E46,
      0x3005, 0xD800, 0xDC00, 0x1E9B, 0x216B, 0x2161, 0x3007, 0x0531, 0x0565, 0x10780, 0x1DF00,
    ].map(c => String.fromCodePoint(c));

    /** A small seeded generator (mulberry32), so a failure can be reproduced. */
    function random(seed: number): () => number {
      let state = seed;
      return () => {
        state = (state + 0x6D2B79F5) | 0;
        let t = Math.imul(state ^ (state >>> 15), 1 | state);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
      };
    }

    function randomName(next: () => number, alphabet: string[]): string {
      const length = 1 + Math.floor(next() * 16);
      return Array.from({length}, () => alphabet[Math.floor(next() * alphabet.length)]).join('');
    }

    it('should sanitize random names stably and composed', () => {
      const next = random(20261021);
      const alphabet = [...LATIN_NAME_CHARACTERS, ...OTHER_NAME_CHARACTERS];
      for (let i = 0; i < 20000; i++) {
        const name = randomName(next, alphabet);
        const once = sanitize(name);
        if (sanitize(once) !== once || once.normalize('NFC') !== once) {
          expect.fail(`sanitizing ${JSON.stringify(name)} gave ${JSON.stringify(once)}, then ${JSON.stringify(sanitize(once))}`);
        }
      }
    });

    it('should clean random ASCII names as before', () => {
      // They take a shorter way than other names, which must come to the same, as on the server
      // (PathPatternResolverScriptTest.randomAsciiNames_comeOutAsTheyDidWhenEveryNonAsciiCodePointWasDropped)
      const next = random(20261021);
      const ascii = Array.from({length: 0x80}, (_, c) => String.fromCharCode(c));
      for (let i = 0; i < 20000; i++) {
        // Letters around it, so the name is never empty
        const name = `a${randomName(next, ascii)}a`;
        if (sanitize(name) !== asciiOnlySanitize(name)) {
          expect.fail(`sanitizing ${JSON.stringify(name)} gave ${JSON.stringify(sanitize(name))}, not ${JSON.stringify(asciiOnlySanitize(name))}`);
        }
      }
    });

    it('should give the same names once the character classes it keeps have been forgotten', () => {
      const before = SHARED_SANITIZE_CASES.map(([, input]) => sanitize(input));
      // More different characters than it keeps the classes of (CJK ideographs from U+4E00)
      sanitize(Array.from({length: 12000}, (_, i) => String.fromCodePoint(0x4E00 + i)).join(''));
      expect(SHARED_SANITIZE_CASES.map(([, input]) => sanitize(input))).toEqual(before);
    });

    it('should clean random Latin names as before', () => {
      const next = random(20261021);
      for (let i = 0; i < 20000; i++) {
        // Letters around it, so the name is never empty
        const name = `a${randomName(next, LATIN_NAME_CHARACTERS)}a`;
        if (sanitize(name) !== asciiOnlySanitize(name)) {
          expect.fail(`sanitizing ${JSON.stringify(name)} gave ${JSON.stringify(sanitize(name))}, not ${JSON.stringify(asciiOnlySanitize(name))}`);
        }
      }
    });
  });

  // PathPatternResolver caps values and path components in UTF-8 bytes, which non-Latin names
  // reach long before Latin ones do
  describe('truncatePathComponent', () => {

    it('should leave a component within the limit unchanged', () => {
      expect(truncatePathComponent('The Hobbit', 200)).toBe('The Hobbit');
      expect(truncatePathComponent('ب'.repeat(100), 200)).toBe('ب'.repeat(100));
    });

    it('should cut at the last whole character that fits', () => {
      expect(truncatePathComponent('ب'.repeat(120), 200)).toBe('ب'.repeat(100));
      expect(truncatePathComponent('某'.repeat(70), 200)).toBe('某'.repeat(66));
      expect(truncatePathComponent('\u{10000}'.repeat(60), 202)).toBe('\u{10000}'.repeat(50));
    });

    // The cuts the move preview must agree on with the server: PathPatternResolverScriptTest in booklore-api asserts
    // this same table (SHARED_TRUNCATION_CASES), so a row added or changed here belongs there too. Each row is a
    // description, a path component, a limit in bytes and what truncatePathComponent() makes of it.
    const SHARED_TRUNCATION_CASES: [string, string, number, string][] = [
      ["within the limit", "The Hobbit", 200, "The Hobbit"],
      ["Latin cut inside a word", "word ".repeat(39) + "words and more", 200, "word ".repeat(39) + "words"],
      ["Latin cut after a space", "word ".repeat(40) + "end", 200, "word ".repeat(39) + "word"],
      ["cut after a space a middle dot became", "中".repeat(66) + " 文文文", 200, "中".repeat(66)],
      ["cut after an ideographic space", "某\u3000某某", 7, "某"],
      ["cut after a joiner", "کتاب ".repeat(21) + "نومی\u200Cخواهم", 200, "کتاب ".repeat(21) + "نومی"],
      ["cut inside a letter and its vowel signs", "क" + "कहानियाँ ".repeat(7) + "कहानियाँ", 200, "क" + "कहानियाँ ".repeat(7) + "कहानि"],
      ["cut inside halfwidth katakana and its voiced mark", "ア".repeat(65) + "\uFF76\uFF9E", 200, "ア".repeat(65)],
      ["cut inside an emoji sequence", "a\uD83D\uDC68\u200D\uD83D\uDC69", 6, "a"],
      ["a conjunct longer than the limit", "\u0915\u094D\u0937", 7, "\u0915\u094D"],
      ["a letter under a pile of marks", "a" + "\u0301".repeat(10), 5, "a\u0301\u0301"],
      ["a first character over the limit", "某", 2, ""],
      ["whole code points", "\uD800\uDC00".repeat(60), 202, "\uD800\uDC00".repeat(50)],
    ];

    it.each(SHARED_TRUNCATION_CASES)('should agree with the server on a %s', (_, component, maxBytes, expected) => {
      expect(truncatePathComponent(component, maxBytes)).toBe(expected);
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

    it('should drop the space a title cut to its byte limit ends with, as the server does', () => {
      const title = truncatePathComponent(sanitize(`${'ب'.repeat(99)} ب`), MAX_COMPONENT_BYTES);
      expect(resolveFilePath('{title}', {title}, 'book.epub', false)).toBe(`${'ب'.repeat(99)}.epub`);
    });

    it('should drop the space punctuation became where a long Chinese title is cut, as the server does', () => {
      // The middle dot becomes a space, which the first 200 bytes end in
      const title = truncatePathComponent(sanitize(`${'中'.repeat(66)}·文文文`), MAX_COMPONENT_BYTES);
      expect(resolveFilePath('{title}', {title}, 'x.epub', false)).toBe(`${'中'.repeat(66)}.epub`);
    });
  });
});
