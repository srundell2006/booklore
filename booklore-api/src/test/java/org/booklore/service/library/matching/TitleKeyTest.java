package org.booklore.service.library.matching;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a file name or a title reads as a book. Rows are "folder | name | identity": the folder is the file's sub path
 * (the only evidence of an author a name gets here), and "" is the library path's root. Most rows come from the
 * production rescan that merged different books and from the diagnosis that followed it.
 */
class TitleKeyTest {

    private static TitleKey key(String folder, String name) {
        boolean audio = name.matches("(?i).*\\.(m4b|m4a|mp3|opus)$");
        return TitleKey.ofFileName(name, audio, NameContext.forFolder(folder));
    }

    @Nested
    class ClosedVocabularies {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                The Hobbit.pdf                               | hobbit
                The Hobbit.epub                              | hobbit
                THE HOBBIT.pdf                               | hobbit
                "  The   Hobbit  .pdf"                       | hobbit
                The Hobbit (PDF).pdf                         | hobbit
                The Hobbit [EPUB].epub                       | hobbit
                Book (PDF) (EPUB).pdf                        | book
                The_Hobbit.pdf                               | hobbit
                Lord_of_the_Rings.epub                       | lord of the rings
                Hobbit, The.pdf                              | hobbit
                Hobbit,_The.pdf                              | hobbit
                Tale of Two Cities, A.epub                   | tale of two cities
                Unexpected Journey, An.pdf                   | unexpected journey
                noextension                                  | noextension
                Les Misérables.epub                          | les miserables
                Pride & Prejudice.epub                       | pride and prejudice
                Harry Potter and the Sorcerer's Stone.epub   | harry potter and the sorcerers stone
                Sapiens: A Brief History of Humankind.epub   | sapiens a brief history of humankind
                Sapiens - A Brief History of Humankind.pdf   | sapiens a brief history of humankind
                Book (Extended Edition).pdf                  | book
                The Hobbit (Illustrated Edition).pdf         | hobbit
                The Stand (Complete and Uncut Edition).epub  | stand
                Neuromancer 20th Anniversary Edition.epub    | neuromancer
                The Hobbit 50th Anniversary Edition.pdf      | hobbit
                Dune Twenty-Fifth Anniversary Edition.epub   | dune
                Title 2nd Ed.pdf                             | title
                The Hobbit - Unabridged.epub                 | hobbit
                The Hobbit Audio Book.epub                   | hobbit
                The Hobbit e-book.epub                       | hobbit
                Audiobook The Hobbit.epub                    | hobbit
                The Martian (v5.0).epub                      | martian
                Saga 001 (2012) (Digital).cbz                | saga #i1
                The Hobbit (m4b).m4b                         | hobbit
                The Hobbit [audiobook].m4b                   | hobbit
                The Hobbit (audio).m4b                       | hobbit
                The Hobbit (Kindle Edition).epub             | hobbit
                The Hobbit [Kindle].azw3                     | hobbit
                The Hobbit Kindle Edition.azw3               | hobbit
                The Hobbit (opus).opus                       | hobbit
                """)
        void dropsOnlyWhatAClosedVocabularyNames(String name, String identity) {
            assertThat(key("", name).identity()).isEqualTo(identity);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', textBlock = """
                1. American Gods - Neil Gaiman (2011) | american gods neil gaiman #1
                Vol. 1 - Adventures (2020)            | adventures #1
                Dr. Seuss Collection                  | dr seuss collection
                U.S.A. History                        | u s a history
                """)
        void readsADotThatIsNoBookExtensionAsPartOfTheName(String folderName, String identity) {
            // A folder audiobook's name has no extension, and the dot in "1." is no extension either
            assertThat(TitleKey.ofFileName(folderName, true, NameContext.NONE).identity()).isEqualTo(identity);
        }

        @Test
        void readsNoNameAsAnEmptyKey() {
            assertThat(TitleKey.ofFileName(null, false, NameContext.NONE).identity()).isEmpty();
            assertThat(TitleKey.ofFileName("", false, null).identity()).isEmpty();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                The Complete Sherlock Holmes.epub        | complete sherlock holmes
                Special Forces.epub                      | special forces
                The Illustrated Man.epub                 | illustrated man
                Digital Fortress.epub                    | digital fortress
                Going Digital.epub                       | going digital
                Mister Ed.epub                           | mister ed
                Dune (Graphic Novel).cbz                 | dune graphic novel
                A Quiver Full of Arrows - Stories.epub   | quiver full of arrows stories
                The Road - A Novel.epub                  | road a novel
                Carbon Copy.epub                         | carbon copy
                Carbon-Copy.epub                         | carbon copy
                Stand by Me.epub                         | stand by me
                The Best Part.epub                       | best part
                Dr. Seuss Collection.epub                | dr seuss collection
                U.S.A. History.epub                      | u s a history
                Kindle the Flame.epub                    | kindle the flame
                Magnum Opus.epub                         | magnum opus
                Opus Dei.epub                            | opus dei
                """)
        void keepsEveryWordOutsideThoseVocabularies(String name, String identity) {
            assertThat(key("", name).identity()).isEqualTo(identity);
        }
    }

    @Nested
    class Numbers {

        @ParameterizedTest(name = "{0} -> [{1}]")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                A Dangerous Universe 3.epub                         | 3
                Kokoro Connect Volume 4 - Michi Random.epub         | 4
                Kokoro Connect Volume - Nise Random.epub            | ""
                Saga 001 (2012) (Digital).cbz                       | i1
                Saga #12.cbz                                        | i12
                Saga v02.cbz                                        | 2
                Saga Vol. 2.cbz                                     | 2
                Saga Volume 2.cbz                                   | 2
                The Expanse - Book Two.epub                         | 2
                The Expanse Book 2.epub                             | 2
                Rocky III.epub                                      | 3
                Book XIV.epub                                       | 14
                2nd Chance.epub                                     | 2
                Henry IV Part 2.epub                                | 4,2
                Star Wars Episode V - The Empire Strikes Back.epub  | 5
                Part I - The Beginning.epub                         | 1
                Neuromancer 20th Anniversary Edition.epub           | ""
                1984.epub                                           | 1984
                Catch-22.epub                                       | 22
                Fahrenheit 451.epub                                 | 451
                101 Dalmatians.epub                                 | 101
                01 - The Lightning Thief.m4b                        | 1
                1. The Lightning Thief.epub                         | 1
                100. War and Peace.epub                             | 100
                [Discworld 03] Equal Rites.epub                     | 3
                Harry Potter Part 2.epub                            | 2
                The Lost Hero (part 2).epub                         | 2
                I, Robot.epub                                       | ""
                X-Men.cbz                                           | ""
                Henry V.epub                                        | ""
                The Book of Dust.epub                               | ""
                No Country for Old Men.epub                         | ""
                The Stand Part 02.m4b                               | ""
                The Martian (v5.0).epub                             | ""
                Mass Casualties (2009).epub                         | ""
                """)
        void readsEveryVolumeOrSeriesNumber(String name, String numbers) {
            List<String> expected = numbers.isEmpty() ? List.of() : Arrays.asList(numbers.split(","));

            assertThat(key("", name).numbers()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> [{1}]")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                Saga: Volume Two                                                      | 2
                The Walking Dead: Vol. 2: Miles Behind Us                             | 2
                The Expanse Book 2                                                    | 2
                Saga #3                                                               | 3
                Henry IV Part 2                                                       | 2
                Unbroken: A World War II Story of Survival, Resilience, and Redemption | ""
                The Boys in the Boat: Nine Americans at the 1936 Berlin Olympics      | ""
                A Dangerous Universe 3                                                | ""
                """)
        void tellsTheNumbersASeriesKeywordIntroduces(String title, String numbers) {
            List<String> expected = numbers.isEmpty() ? List.of() : Arrays.asList(numbers.split(","));

            assertThat(TitleKey.ofTitle(title, NameContext.NONE).keywordNumbers()).isEqualTo(expected);
        }

        @Test
        void keepsBracketedYearsApartFromTheNumbers() {
            TitleKey saga = key("", "Saga 001 (2012) (Digital).cbz");

            assertThat(saga.numbers()).containsExactly("i1");
            assertThat(saga.years()).containsExactly(2012);
            assertThat(key("", "Dune (1965).epub").identity()).isEqualTo(key("", "Dune.epub").identity());
        }

        @ParameterizedTest(name = "{0} / {1}")
        @CsvSource(delimiter = '|', textBlock = """
                A Dangerous Universe 3.epub              | A Dangerous Universe 5.epub
                Kokoro Connect Volume 4 - Michi Random.epub | Kokoro Connect Volume - Michi Random.epub
                Harry Potter Book 1.epub                 | Harry Potter Book 2.epub
                Harry Potter.epub                        | Harry Potter 1.epub
                Saga #12.cbz                             | Saga #13.cbz
                Saga v01.cbz                             | Saga v02.cbz
                Rocky II.epub                            | Rocky III.epub
                Rocky.epub                               | Rocky II.epub
                Henry IV Part 1.epub                     | Henry IV Part 2.epub
                Star Wars Episode IV - A New Hope.epub   | Star Wars Episode V - A New Hope.epub
                2001 A Space Odyssey.epub                | 2010 A Space Odyssey.epub
                """)
        void differentNumbersAreDifferentBooks(String a, String b) {
            assertThat(key("", a).identity()).isNotEqualTo(key("", b).identity());
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', textBlock = """
                Saga Vol. 1.cbz                        | saga #1
                Saga Volume 1.cbr                      | saga #1
                Saga v01.cbz                           | saga #1
                Saga Book One.cbz                      | saga #1
                Saga 001.cbz                           | saga #i1
                Saga #1.cbz                            | saga #i1
                Saga Issue 1.cbz                       | saga #i1
                Saga No. 1.cbz                         | saga #i1
                Saga v01 #3.cbz                        | saga #1/i3
                Saga Volume 1 (Deluxe Edition).cbz     | saga deluxe edition #1
                Saga Volume 1 Deluxe Edition.cbr       | saga deluxe edition #1
                Saga (Digital).cbz                     | saga
                The Hobbit (Deluxe Edition).epub       | hobbit
                Harry Potter 1.epub                    | harry potter #1
                """)
        void readsAComicsIssueApartFromItsVolume(String name, String identity) {
            // "Saga 001" is the first issue, "Saga Vol. 1" the trade collecting issues 1 to 6, and its deluxe edition
            // issues 1 to 18: three books. An ebook's bare number is its volume, as before.
            assertThat(key("", name).identity()).isEqualTo(identity);
        }

        @Test
        void readsAComicsIssueAsNoMetadataTitle() {
            // A title does not say whether it is a comic's: its numbers are volumes, which an issue never matches
            assertThat(TitleKey.ofTitle("Saga, Volume 1", NameContext.NONE).identity()).isEqualTo(key("", "Saga Vol. 1.cbz").identity());
            assertThat(TitleKey.ofTitle("Saga #1", NameContext.NONE).identity()).isNotEqualTo(key("", "Saga 001.cbz").identity());
        }

        @ParameterizedTest(name = "{0} / {1}")
        @CsvSource(delimiter = '|', textBlock = """
                Harry Potter Book 1.epub        | Harry Potter 1.mobi
                The Expanse - Book One.epub     | The Expanse Book 1.pdf
                Saga Vol. 1.cbz                 | Saga Volume 1.cbr
                Saga v01.cbz                    | Saga Vol. 1.cbr
                Saga 001 (2012) (Digital).cbz   | Saga 1 (2012).cbr
                Rocky II.epub                   | Rocky 2.epub
                Part I - The Beginning.epub     | Part 1 - The Beginning.epub
                """)
        void oneNumberWrittenTwoWaysIsOneBook(String a, String b) {
            assertThat(key("", a).identity()).isEqualTo(key("", b).identity());
        }
    }

    @Nested
    class AuthorParts {

        @ParameterizedTest(name = "[{0}] {1} -> {2}")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                # folder                     | name                                          | identity                     | dropped
                B/Dale Brown                 | Battle Born by Dale Brown.epub                | battle born                  | Dale Brown
                B/Brown, Dale                | Battle Born by Dale Brown.epub                | battle born                  | Dale Brown
                B/Dale Brown                 | Battle Born by Dale Brown - Unabridged.m4b    | battle born                  | Dale Brown
                James S. A. Corey            | The Expanse - Leviathan Wakes by James S. A. Corey.epub | expanse leviathan wakes | James S. A. Corey
                Sarah Graves                 | Death by Chocolate by Sarah Graves.epub       | death by chocolate           | Sarah Graves
                Austen, Jane                 | Pride and Prejudice - Jane Austen.pdf         | pride and prejudice          | Jane Austen
                George Orwell                | 1984 - George Orwell.pdf                      | #1984                        | George Orwell
                Neil Gaiman/American Gods    | American Gods - Neil Gaiman.mobi              | american gods                | Neil Gaiman
                Neil Gaiman/American Gods    | Neil Gaiman - American Gods.mobi              | american gods                | Neil Gaiman
                J.R.R. Tolkien               | Tolkien, J.R.R. - The Hobbit.epub             | hobbit                       | Tolkien, J.R.R.
                Michael Anthony              | Mass Casualties - Michael Anthony (2009).epub | mass casualties              | Michael Anthony
                Le Guin, Ursula K            | A Wizard of Earthsea - Ursula K. Le Guin.epub | wizard of earthsea           | Ursula K. Le Guin
                Misc                         | The Hobbit - J.R.R. Tolkien.epub              | hobbit                       | J.R.R. Tolkien
                ""                           | Hobbit, The - J.R.R. Tolkien.pdf              | hobbit                       | J.R.R. Tolkien
                ""                           | The_Hobbit - J.R.R. Tolkien.pdf               | hobbit                       | J.R.R. Tolkien
                A/Aarons, Edward S           | Assignment - Edward S. Aarons.epub            | assignment                   | Edward S. Aarons
                Rogue Angel/Destiny          | Rogue Angel - Destiny.epub                    | destiny                      | Rogue Angel
                Las Vegas Bad Boys           | KING - Las Vegas Bad Boys.epub                | king                         | Las Vegas Bad Boys
                """)
        void dropsAPartTheFoldersOrItsInitialsVouchFor(String folder, String name, String identity, String dropped) {
            TitleKey key = key(folder, name);

            assertThat(key.identity()).isEqualTo(identity);
            assertThat(key.droppedName()).isEqualTo(dropped);
        }

        @ParameterizedTest(name = "[{0}] {1} -> {2}")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                # folder                     | name                                     | identity
                A/Aarons, Edward S           | Assignment - Karachi.epub                | assignment karachi
                A/Aarons, Edward S           | Assignment - Stella Marni.epub           | assignment stella marni
                A/Aarons, Edward S/Sam Durell| Assignment - The Cairo Dancers.epub      | assignment the cairo dancers
                Some Author                  | KING - Las Vegas Bad Boys.epub           | king las vegas bad boys
                Stephen King                 | KING - Las Vegas Bad Boys.epub           | king las vegas bad boys
                Kokoro Connect               | Kokoro Connect Volume 4 - Michi Random.epub | kokoro connect michi random #4
                ""                           | Battle Born by Dale Brown.epub           | battle born by dale brown
                ""                           | Dale Brown - Battle Born.epub            | dale brown battle born
                Dale Brown                   | Dale Brown - Battle Born.epub            | dale brown battle born
                American Gods                | American Gods - Neil Gaiman.mobi         | american gods neil gaiman
                Rogue Angel                  | Rogue Angel - Destiny.epub               | rogue angel destiny
                A/Alex Archer/Rogue Angel    | Rogue Angel - Solomon's Jar.epub         | rogue angel solomons jar
                The Expanse                  | The Expanse - Book One.epub              | expanse #1
                The Dark Tower               | The Dark Tower II - The Drawing of the Three.epub | dark tower the drawing of the three #2
                Star Trek                    | Star Trek - Prime Directive.epub         | star trek prime directive
                Terry Pratchett              | Mort - Pratchett.epub                    | mort pratchett
                Ursula K. Le Guin            | A Wizard of Earthsea - Le Guin.epub      | wizard of earthsea le guin
                ""                           | 1984 - George Orwell.epub                | george orwell #1984
                ""                           | Pride and Prejudice - Jane Austen.pdf    | pride and prejudice jane austen
                ""                           | J.R.R. Tolkien - The Hobbit.epub         | j r r tolkien the hobbit
                ""                           | 01 - The Lightning Thief.m4b             | lightning thief #1
                ""                           | Stand by Me.epub                         | stand by me
                """)
        void keepsAPartNothingVouchesFor(String folder, String name, String identity) {
            TitleKey key = key(folder, name);

            assertThat(key.identity()).isEqualTo(identity);
            assertThat(key.droppedName()).isNull();
        }

        @Test
        void takesTheAuthorsOfTheBookComparedWithAsEvidence() {
            NameContext root = NameContext.NONE;

            assertThat(TitleKey.ofFileName("Primal Heat - Lora Leigh.epub", false, root).identity())
                    .isEqualTo("primal heat lora leigh");
            assertThat(TitleKey.ofFileName("Primal Heat - Lora Leigh.epub", false,
                    root.withAuthors(List.of("Lora Leigh", "Michelle Rowan"))).identity())
                    .isEqualTo("primal heat");
            assertThat(TitleKey.ofFileName("Primal Heat - Lora Leigh.epub", false,
                    root.withAuthors(List.of("Leigh Bardugo"))).identity())
                    .isEqualTo("primal heat lora leigh");
        }
    }

    @Nested
    class CopiesAndParts {

        @ParameterizedTest(name = "{0} -> {1} (copy of {2})")
        @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
                Mort (1).epub                                     | mort #1          | mort
                Mort(2).epub                                      | mort #2          | mort
                Mort - Copy.epub                                  | mort             | mort
                Mort - Copy (2).epub                              | mort             | mort
                Mort (copy).epub                                  | mort             | mort
                Mass Casualties - Michael Anthony (2009) (1).epub | mass casualties michael anthony #1 | mass casualties michael anthony
                Saga (12).cbz                                     | saga #i12        | ""
                Mort-Copy.epub                                    | mort copy        | ""
                """)
        void readsADuplicatesCopyMarker(String name, String identity, String original) {
            TitleKey key = key("", name);

            assertThat(key.identity()).isEqualTo(identity);
            if (original.isEmpty()) {
                assertThat(key.copy()).isNull();
            } else {
                assertThat(key.copy()).isNotNull();
                assertThat(key.identityWithoutCopy()).isEqualTo(original);
            }
        }

        @ParameterizedTest(name = "{0} -> part {1}")
        @CsvSource(delimiter = '|', textBlock = """
                The Lost Hero (part 1).m4b       | 1
                The Lost Hero (pt 2).m4b         | 2
                The Lost Hero (disc 1).m4b       | 1
                The Lost Hero [cd 3].m4b         | 3
                The Lost Hero - part 1.m4b       | 1
                The Lost Hero Part 02.m4b        | 2
                The Lost Hero - Part 2 of 3.m4b  | 2
                The Lost Hero disk 2.mp3         | 2
                The Lost Hero pt 1.m4b           | 1
                The Lost Hero (PART 1).m4b       | 1
                The Lost Hero (mp3) (part 1).mp3 | 1
                The Lost Hero - Part One.m4b     | 1
                The Lost Hero Part Two.mp3       | 2
                The Lost Hero (Part II).m4b      | 2
                The Lost Hero - Part IV of VI.m4b | 4
                The Lost Hero (disc one).m4b     | 1
                The Lost Hero [1-2].m4b          | 1
                The Lost Hero (2 of 3).m4b       | 2
                The Lost Hero (2/3).mp3          | 2
                The Lost Hero CD1.mp3            | 1
                """)
        void readsAnAudiobooksPartAsOneBookSplitOverFiles(String name, int part) {
            TitleKey key = key("", name);

            assertThat(key.identity()).isEqualTo("lost hero");
            assertThat(key.part()).isEqualTo(part);
        }

        @Test
        void keepsAPartMarkerOfAnEbookAsAVolume() {
            assertThat(key("", "The Lost Hero (part 1).epub").identity()).isEqualTo("lost hero #1");
            assertThat(key("", "The Lost Hero - Part 2.pdf").identity()).isEqualTo("lost hero #2");
            assertThat(key("", "The Lost Hero (part 1).epub").part()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                The Hobbit (Part 9780261102217).m4b | hobbit #9780261102217
                The Hobbit - Disc 3000000000.mp3    | hobbit disc #3000000000
                The Hobbit pt 2147483648.mp3        | hobbit #2147483648
                Interview cd 20231015120000.mp3     | interview cd #20231015120000
                """)
        void readsANumberTooLongForAPartAsPartOfTheName(String name, String identity) {
            // Parsed as a part, these threw and stopped the whole scan
            TitleKey key = key("", name);

            assertThat(key.part()).isNull();
            assertThat(key.identity()).isEqualTo(identity);
            assertThat(FileEvidence.of(1L, "", name, true).readings()).isNotEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
                CD1         | 1
                CD 2        | 2
                Disc 1      | 1
                Disk 02     | 2
                Part 3      | 3
                Disc One    | 1
                Disc 1 of 3 | 1
                """)
        void readsAFolderThatIsOnlyADiscAsADiscOfTheBookItsFolderNames(String name, int part) {
            // "Tolkien/The Hobbit/CD1/*.mp3" is the folder audiobook "CD1" in "Tolkien/The Hobbit"
            TitleKey key = TitleKey.ofFileName(name, true, NameContext.forFolder("Tolkien/The Hobbit"));

            assertThat(key.identity()).isEqualTo("hobbit");
            assertThat(key.part()).isEqualTo(part);
            assertThat(TitleKey.ofFileName(name + ".mp3", true, NameContext.forFolder("Tolkien/The Hobbit (1937)")).identity())
                    .isEqualTo("hobbit");
            assertThat(FileEvidence.of(1L, "Tolkien/The Hobbit", name, true).fileNameKey()).isEmpty();
        }

        @Test
        void readsADiscWithoutAFolderOrOfAnEbookAsItIs() {
            assertThat(TitleKey.ofFileName("CD1", true, NameContext.NONE).identity()).isEmpty();
            assertThat(TitleKey.ofFileName("Part 1.epub", false, NameContext.forFolder("Dune")).identity()).isEqualTo("#1");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', textBlock = """
                The Lost Hero Part Ivy.m4b               | lost hero part ivy
                The Lost Hero Part IIII.m4b              | lost hero part iiii
                Collected Lectures (1999-2003).m4b       | collected lectures #1999/2003
                Collected Lectures (3-2).m4b             | collected lectures #3/2
                The Lost Hero (part 1).epub              | lost hero #1
                """)
        void readsNoPartWhereNoNumeralFollowsOrTheFileIsAnEbook(String name, String identity) {
            TitleKey key = key("", name);

            assertThat(key.part()).isNull();
            assertThat(key.identity()).isEqualTo(identity);
        }

        @Test
        void keepsAPartOrDiscWordWithoutANumber() {
            assertThat(key("", "Disc Jockey.m4b").identity()).isEqualTo("disc jockey");
            assertThat(key("", "The Best Part.m4b").identity()).isEqualTo("best part");
        }
    }

    @Nested
    class Readings {

        @Test
        void listsTheContextsReadingFirstThenEveryNameLikePartDropped() {
            List<TitleKey> readings = TitleKey.readings("Primal Heat - Lora Leigh.epub", false, NameContext.NONE);

            assertThat(readings).extracting(TitleKey::identity, TitleKey::droppedName).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("primal heat lora leigh", null),
                    org.assertj.core.groups.Tuple.tuple("lora leigh", "Primal Heat"),
                    org.assertj.core.groups.Tuple.tuple("primal heat", "Lora Leigh"));
        }

        @Test
        void neverDropsAPartWithANumberOrLeavesOnlyASeriesLabel() {
            assertThat(TitleKey.readings("The Expanse - Book One.epub", false, NameContext.NONE))
                    .extracting(TitleKey::identity).containsExactly("expanse #1");
            assertThat(TitleKey.readings("Mistborn 2 - The Well of Ascension.epub", false, NameContext.NONE))
                    .extracting(TitleKey::identity).containsExactly("mistborn the well of ascension #2");
        }

        @Test
        void readsAByPartToo() {
            assertThat(TitleKey.readings("Battle Born by Dale Brown.epub", false, NameContext.NONE))
                    .extracting(TitleKey::identity).containsExactly("battle born by dale brown", "battle born");
        }
    }

    @Nested
    class Titles {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', textBlock = """
                The Hobbit                             | hobbit
                Kokoro Connect Volume 4: Michi Random  | kokoro connect michi random #4
                Assignment - Karachi                   | assignment karachi
                A Dangerous Universe 5                 | dangerous universe #5
                Mort (1)                               | mort #1
                The Stand Part 2                       | stand #2
                """)
        void readsAMetadataTitleWithoutCopyMarkersOrAudioParts(String title, String identity) {
            assertThat(TitleKey.ofTitle(title, NameContext.NONE).identity()).isEqualTo(identity);
        }

        @Test
        void readsAFileAndTheTitleItWasNamedAfterAlike() {
            NameContext anda = NameContext.forFolder("A/Anda, Sadanatsu");

            assertThat(TitleKey.ofFileName("Kokoro Connect Volume 4 - Michi Random.epub", false, anda).identity())
                    .isEqualTo(TitleKey.ofTitle("Kokoro Connect Volume 4: Michi Random", anda).identity());
            assertThat(TitleKey.ofFileName("Assignment - Treason.epub", false, NameContext.forFolder("A/Aarons, Edward S")).identity())
                    .isNotEqualTo(TitleKey.ofTitle("Assignment", NameContext.NONE).identity());
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
                The Hunger Games Trilogy  | hunger games
                Saga Series 2             | saga #2
                The Saga Saga             | saga
                Saga Series Omnibus       | -
                Trilogy                   | -
                The Trilogy               | -
                """)
        void readsASeriesNameWithoutALastWordSuchAsTrilogy(String series, String identity) {
            assertThat(TitleKey.ofTitle(series, NameContext.NONE).identityWithoutLastWord(TitleWords.SERIES_NOUNS))
                    .isEqualTo(identity);
        }
    }
}
