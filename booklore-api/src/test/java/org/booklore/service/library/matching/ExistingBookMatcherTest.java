package org.booklore.service.library.matching;

import org.booklore.service.library.matching.BooksInFolder.FolderBook;
import org.booklore.service.library.matching.BooksInFolder.FolderFile;
import org.booklore.service.library.matching.ExistingBookMatcher.Ambiguous;
import org.booklore.service.library.matching.ExistingBookMatcher.Claim;
import org.booklore.service.library.matching.ExistingBookMatcher.ClaimResolution;
import org.booklore.service.library.matching.ExistingBookMatcher.Decision;
import org.booklore.service.library.matching.ExistingBookMatcher.Kind;
import org.booklore.service.library.matching.ExistingBookMatcher.Match;
import org.booklore.service.library.matching.ExistingBookMatcher.NoMatch;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which existing book a new file belongs to. Most cases come from the production rescan that attached files to the
 * wrong books, and from the diagnosis after it; the rest are the legitimate matches the strict rules must keep.
 */
class ExistingBookMatcherTest {

    private static final long PATH = 1L;

    private static boolean audio(String name) {
        return name.matches("(?i).*\\.(m4b|m4a|mp3|opus)$");
    }

    private static FileEvidence file(String folder, String name) {
        return FileEvidence.of(PATH, folder, name, audio(name));
    }

    private static ShellCandidate shell(long id, String title, String... authors) {
        return ShellCandidate.of(id, PATH, false, true, title, null, null, List.of(authors));
    }

    private static ShellCandidate shellNumber(long id, String title, float seriesNumber, String... authors) {
        return ShellCandidate.of(id, PATH, false, true, title, null, seriesNumber, List.of(authors));
    }

    private static Decision shellMatch(FileEvidence file, ShellCandidate... shells) {
        return ExistingBookMatcher.matchShell(file, FilelessShellIndex.of(List.of(shells)));
    }

    private static FolderBook book(long id, List<String> authors, String... names) {
        return new FolderBook(id, Arrays.stream(names).map(n -> new FolderFile(n, audio(n))).toList(), authors);
    }

    private static FolderBook book(long id, String... names) {
        return book(id, List.of(), names);
    }

    private static Decision folderMatch(FileEvidence file, FolderBook... books) {
        return ExistingBookMatcher.matchInFolder(file, BooksInFolder.of(file.folder(), List.of(books)));
    }

    private static long matchedId(Decision decision) {
        assertThat(decision).isInstanceOf(Match.class);
        return ((Match) decision).bookId();
    }

    @Nested
    class FilelessBooksThatMustNotMatch {

        @ParameterizedTest(name = "{0}/{1}")
        @CsvSource(delimiter = '|', textBlock = """
                A/Aarons, Edward S             | Assignment - Karachi.epub
                A/Aarons, Edward S             | Assignment - Stella Marni.epub
                A/Aarons, Edward S             | Assignment - The Cairo Dancers.epub
                A/Aarons, Edward S/Sam Durell  | Assignment - Maltese Maiden.epub
                A/Aarons, Edward S/Sam Durell  | Assignment - Treason.epub
                """)
        void assignmentSubtitlesAreNotTheAssignmentOfAnotherAuthor(String folder, String name) {
            // Production: all five went to book 167862 "Assignment" by I. Christie
            assertThat(shellMatch(file(folder, name), shell(167862, "Assignment", "I. Christie"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aSubtitleIsNotAnAuthorEvenOfTheRightAuthorsBook() {
            assertThat(shellMatch(file("A/Aarons, Edward S", "Assignment - Karachi.epub"),
                    shell(1, "Assignment", "Edward S. Aarons"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("C/Christie, Agatha", "Assignment.epub"),
                    shell(2, "Assignment", "I. Christie"))).as("I. is not Agatha").isInstanceOf(NoMatch.class);
        }

        @ParameterizedTest(name = "{0}/{1} vs {2}")
        @CsvSource(delimiter = '|', textBlock = """
                A/A. C. Arthur/The Shadow Shifters | Primal Heat.epub     | Primal Heat     | Lora Leigh;Michelle Rowan;Ava Gray;Jory Strong
                A/Alex Archer/Rogue Angel          | The Chosen.epub      | The Chosen      | Verda H. Foster
                A/A.M. Arthur/Restoration          | Taking a Chance.epub | Taking A Chance | Planet-Solin
                X/Doe, Jane                        | Survival.epub        | Survival        | Somebody Else
                X/Author One                       | Fallout.epub         | Fallout         | Someone Else
                Y/Author Two                       | Bloodlines.epub      | Bloodlines      | Someone Else
                Z/Author Three                     | Play.epub            | Play            | Someone Else
                Z/Author Three/Series              | Dominion.epub        | Dominion        | Someone Else
                Y/Author Two                       | Bossman.epub         | Bossman         | Someone Else
                X/Doe, Jane                        | Hunter.epub          | Hunter          | Jack Hunter
                """)
        void theSameTitleByAnotherAuthor(String folder, String name, String title, String authors) {
            assertThat(shellMatch(file(folder, name), shell(10, title, authors.split(";")))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aSurnameAloneIsNoEvidence() {
            assertThat(shellMatch(file("Smith", "Play.epub"), shell(27, "Play", "Zadie Smith"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("Smith", "On Beauty.epub"), shell(29, "On Beauty", "Zadie Smith"))).isInstanceOf(NoMatch.class);
            // Production: "King" by any King is not proof of anything
            assertThat(shellMatch(file("K/King", "The Stand.epub"), shell(30, "The Stand", "Stephen King"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aOneWordTitleNeedsTheGivenNameAsTheBookHasIt() {
            assertThat(shellMatch(file("S/Smith, Z.", "Play.epub"), shell(28, "Play", "Zadie Smith"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("S/Smith, Zadie", "Play.epub"), shell(28, "Play", "Zadie Smith"))).isInstanceOf(Match.class);
            assertThat(shellMatch(file("S/Smith, Z.", "On Beauty.epub"), shell(29, "On Beauty", "Zadie Smith"))).isInstanceOf(Match.class);
        }

        @Test
        void aBookWithoutAuthorsOnlyMatchesWhenPhysical() {
            ShellCandidate lostFiles = ShellCandidate.of(15, null, false, true, "Dune", null, null, List.of());
            ShellCandidate physical = ShellCandidate.of(16, null, true, false, "Dune", null, null, List.of());
            assertThat(shellMatch(file("H/Herbert, Frank", "Dune.epub"), lostFiles)).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("H/Herbert, Frank", "Dune.epub"), physical)).isInstanceOf(Match.class);
            // An author part nothing confirms cannot be checked against a book without authors
            assertThat(shellMatch(file("", "Dune - Frank Herbert.epub"), physical)).isInstanceOf(NoMatch.class);
        }

        @Test
        void differentVolumeNumbersNeverMatch() {
            // Production F8: same author, different volume
            assertThat(shellMatch(file("S/Stuart Slade", "A Dangerous Universe 3.epub"),
                    shell(40, "A Dangerous Universe 5", "Stuart Slade"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("A/Anda, Sadanatsu", "Kokoro Connect Volume 4 - Michi Random.epub"),
                    shell(41, "Kokoro Connect Volume 1: Hito Random", "Sadanatsu Anda"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("H/Herbert, Frank", "Dune Messiah.epub"), shell(42, "Dune", "Frank Herbert")))
                    .isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("S/Smith, Ann", "Survival 3 - Ann Smith.epub"), shellNumber(43, "Survival", 2, "Ann Smith")))
                    .isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("S/Smith, Ann", "Survival (2).epub"), shell(44, "Survival", "Ann Smith")))
                    .isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("S/Smith, Ann", "Primal Heat 2.epub"), shell(45, "Primal Heat", "Ann Smith")))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void aComicsIssueIsNotTheVolumeCollectingIt() {
            ShellCandidate volume = shell(80, "Saga, Volume 1", "Brian K. Vaughan");
            assertThat(shellMatch(file("Brian K. Vaughan/Saga", "Saga 001.cbz"), volume)).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("Brian K. Vaughan/Saga", "Saga #1.cbz"), volume)).isInstanceOf(NoMatch.class);
            assertThat(matchedId(shellMatch(file("Brian K. Vaughan/Saga", "Saga Vol. 1.cbz"), volume))).isEqualTo(80);
        }

        @Test
        void aSeriesPrefixOnlyCountsForThatSeriesNumber() {
            FileEvidence kaneAndAbel = file("A/Archer, Jeffrey/Kane and Abel", "01 - Kane and Abel.epub");
            assertThat(shellMatch(kaneAndAbel, shellNumber(50, "Kane and Abel", 2, "Jeffrey Archer"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(kaneAndAbel, shell(51, "Kane and Abel", "Jeffrey Archer"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aNovellaNumberedTwoAndAHalfIsNotNumberTwo() {
            // D6: 2.5 sits between two volumes; it is no whole number, so "02 - Title" is not it
            ShellCandidate novella = shellNumber(54, "Edge of Dawn", 2.5f, "Jane Doe");
            assertThat(novella.seriesNumber()).isNull();
            assertThat(shellMatch(file("D/Doe, Jane/Dawn", "02 - Edge of Dawn.epub"), novella)).isInstanceOf(NoMatch.class);
        }

        @Test
        void aYearIsNoSeriesNumber() {
            // A leading number of four digits is a title ("1984") or a year, never a place in a series
            assertThat(file("O/Orwell, George", "1984 - George Orwell.epub").seriesReadings()).isEmpty();
        }

        @Test
        void anotherLibraryPathDoesNotMatch() {
            ShellCandidate elsewhere = ShellCandidate.of(24, 2L, false, true, "The Martian", null, null, List.of("Andy Weir"));
            assertThat(shellMatch(file("W/Weir, Andy", "The Martian.epub"), elsewhere)).isInstanceOf(NoMatch.class);
        }

        @Test
        void twoQualifyingBooksThatDifferAreAmbiguous() {
            // Two alike books are one book's two records; see AlikeFilelessBooks
            assertThat(shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    shell(20, "Survival", "Ann Smith"), shell(21, "Survival", "Ann Smith", "Bob Jones"))).isInstanceOf(Ambiguous.class);
        }

        @Test
        void kingOfTheLasVegasBadBoysIsNotKingInAnAuthorFolder() {
            ShellCandidate king = shell(60, "King", "Mary Lane");
            assertThat(shellMatch(file("L/Lane, Mary", "King.epub"), king)).isInstanceOf(Match.class);
            assertThat(shellMatch(file("L/Lane, Mary", "KING - Las Vegas Bad Boys.epub"), king)).isInstanceOf(NoMatch.class);
        }
    }

    @Nested
    class FilelessBooksThatMustMatch {

        @ParameterizedTest(name = "{0}/{1} vs {2}")
        @CsvSource(delimiter = '|', textBlock = """
                W/Weir, Andy                       | The Martian.epub                                 | The Martian          | Andy Weir
                W/Weir, Andy                       | The Martian.pdf                                  | The Martian          | Andy Weir
                Andy Weir/The Martian              | The Martian.m4b                                  | The Martian          | Andy Weir
                L/Lora Leigh                       | Primal Heat.epub                                 | Primal Heat          | Lora Leigh;Michelle Rowan;Ava Gray;Jory Strong
                A/Aarons, Edward S                 | Assignment - Karachi.epub                        | Assignment - Karachi | Edward S. Aarons
                A/Aarons, Edward S                 | Assignment - Karachi.epub                        | Assignment: Karachi  | Aarons, Edward S.
                A/A.M. Arthur/Restoration          | Taking a Chance.epub                             | Taking A Chance      | A. M. Arthur
                A/Anda, Sadanatsu                  | Kokoro Connect Volume 4 - Michi Random.epub      | Kokoro Connect Volume 4: Michi Random | Sadanatsu Anda
                T/Tolkien, J.R.R.                  | The Hobbit - J.R.R. Tolkien.epub                 | The Hobbit           | J. R. R. Tolkien
                T/Tolkien, J.R.R.                  | Hobbit, The.epub                                 | The Hobbit           | J. R. R. Tolkien
                B/Brontë, Charlotte                | Jane Eyre.epub                                   | Jane Eyre            | Charlotte Bronte
                H/Hugo, Victor                     | Les Misérables.epub                              | Les Miserables       | Victor Hugo
                G/García Márquez, Gabriel          | Cien años de soledad.epub                        | Cien Años de Soledad | Gabriel Garcia Marquez
                Neil Gaiman/American Gods (123)    | American Gods - Neil Gaiman.epub                 | American Gods        | Neil Gaiman
                ''                                 | The Martian - Andy Weir.epub                     | The Martian          | Andy Weir
                ''                                 | Mass Casualties - Michael Anthony (2009).epub    | Mass Casualties      | Michael Anthony
                B/Dale Brown                       | Battle Born by Dale Brown.epub                   | Battle Born          | Dale Brown
                Smith                              | Survival - Ann Smith.epub                        | Survival             | Ann Smith
                L/Lane, Mary/Las Vegas Bad Boys    | KING - Las Vegas Bad Boys.epub                   | King                 | Mary Lane
                """)
        void theTitleAndAnAuthorAgree(String folder, String name, String title, String authors) {
            assertThat(shellMatch(file(folder, name), shell(30, title, authors.split(";")))).isInstanceOf(Match.class);
        }

        @Test
        void theFileNamesWhichOfTwoSameTitledBooksItIs() {
            Decision decision = shellMatch(file("Smith", "Survival - Ann Smith.epub"),
                    shell(22, "Survival", "Ann Smith"), shell(23, "Survival", "Bob Smith"));
            assertThat(matchedId(decision)).isEqualTo(22);
        }

        @Test
        void aSeriesPrefixMatchesTheBookWithThatSeriesNumber() {
            // Critic P0-3 a): series folders name their files "NN - Title"
            assertThat(shellMatch(file("A/Archer, Jeffrey/Kane and Abel", "01 - Kane and Abel.epub"),
                    shellNumber(50, "Kane and Abel", 1, "Jeffrey Archer"))).isInstanceOf(Match.class);
            assertThat(shellMatch(file("A/Archer, Jeffrey", "1. Kane and Abel.epub"),
                    shellNumber(50, "Kane and Abel", 1, "Jeffrey Archer"))).isInstanceOf(Match.class);
            assertThat(shellMatch(file("T/Tolkien, J.R.R.", "Book One - The Fellowship of the Ring.epub"),
                    shellNumber(51, "The Fellowship of the Ring", 1, "J.R.R. Tolkien"))).isInstanceOf(Match.class);
            assertThat(shellMatch(file("P/Pratchett, Terry", "[Discworld 04] Mort.epub"),
                    shellNumber(52, "Mort", 4, "Terry Pratchett"))).isInstanceOf(Match.class);
            assertThat(shellMatch(file("P/Pratchett, Terry", "[Discworld 04] Mort.epub"),
                    shellNumber(52, "Mort", 3, "Terry Pratchett"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aProvidersSeriesSuffixOnTheTitleIsNotPartOfIt() {
            // Critic P0-3 b): titles as some metadata providers write them
            ShellCandidate stormFront = shell(53, "Storm Front (The Dresden Files, #1)", "Jim Butcher");
            assertThat(stormFront.seriesNumber()).isEqualTo(1);
            assertThat(shellMatch(file("B/Butcher, Jim/Dresden Files", "Storm Front.epub"), stormFront)).isInstanceOf(Match.class);
            assertThat(shellMatch(file("B/Butcher, Jim/Dresden Files", "01 - Storm Front.epub"), stormFront)).isInstanceOf(Match.class);
            assertThat(shellMatch(file("B/Butcher, Jim/Dresden Files", "02 - Storm Front.epub"), stormFront)).isInstanceOf(NoMatch.class);
        }

        @Test
        void aSubtitleIsPartOfOneOfTheTitlesABookIsFoundUnder() {
            ShellCandidate karachi = ShellCandidate.of(54, PATH, false, true, "Assignment", "Karachi", null, List.of("Edward S. Aarons"));
            assertThat(shellMatch(file("A/Aarons, Edward S", "Assignment - Karachi.epub"), karachi)).isInstanceOf(Match.class);
            assertThat(shellMatch(file("A/Aarons, Edward S", "Assignment - Treason.epub"), karachi)).isInstanceOf(NoMatch.class);
        }

        @Test
        void aPhysicalBookWithoutAuthorsMatchesItsTitle() {
            ShellCandidate physical = ShellCandidate.of(16, null, true, false, "Dune", null, null, List.of());
            assertThat(shellMatch(file("H/Herbert, Frank", "Dune - Frank Herbert.epub"), physical)).isInstanceOf(Match.class);
        }
    }

    @Nested
    class AlikeFilelessBooks {

        private ShellCandidate physical(long id, String title, String... authors) {
            return ShellCandidate.of(id, PATH, true, false, title, null, null, List.of(authors));
        }

        @Test
        void theFileGoesToTheLowestIdOfTwoAlikeBooks() {
            Decision decision = shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    shell(40, "Survival", "Ann Smith"), shell(12, "Survival", "Ann Smith"));

            assertThat(decision).isEqualTo(new Match(12, Kind.FILELESS,
                    "title and author agree with book#12 \"Survival\", one of 2 alike fileless books (book#12, book#40)"));
        }

        @Test
        void authorsAreAlikeAsNamesWhateverTheirOrderOrSpelling() {
            assertThat(matchedId(shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    shell(40, "Survival", "Ann Smith", "Bob Jones"), shell(12, "Survival", "Jones, Bob", "Smith, Ann"))))
                    .isEqualTo(12);
        }

        @Test
        void aBookThatIsNotPhysicalGoesFirst() {
            // The paper copy's record keeps its place; the ebook belongs with the record whose file was lost
            assertThat(matchedId(shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    physical(12, "Survival", "Ann Smith"), shell(40, "Survival", "Ann Smith")))).isEqualTo(40);
            assertThat(matchedId(shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    physical(40, "Survival", "Ann Smith"), physical(12, "Survival", "Ann Smith")))).isEqualTo(12);
        }

        @Test
        void anotherSeriesNumberIsAnotherBook() {
            FileEvidence survival = file("S/Smith, Ann", "Survival.epub");
            assertThat(shellMatch(survival, shellNumber(12, "Survival", 1, "Ann Smith"), shellNumber(40, "Survival", 2, "Ann Smith")))
                    .isInstanceOf(Ambiguous.class);
            assertThat(shellMatch(survival, shellNumber(12, "Survival", 1, "Ann Smith"), shell(40, "Survival", "Ann Smith")))
                    .isInstanceOf(Ambiguous.class);
            assertThat(matchedId(shellMatch(survival, shellNumber(12, "Survival", 2, "Ann Smith"),
                    shellNumber(40, "Survival", 2, "Ann Smith")))).isEqualTo(12);
        }

        @Test
        void aSeriesNumberThatIsNoWholeNumberIsAnotherPlaceInTheSeries() {
            // Neither 1.5 nor 2.5 is a whole series number, and still they are two books; so are a prequel's 0.5 and
            // a book in no series
            FileEvidence survival = file("S/Smith, Ann", "Survival.epub");
            assertThat(shellMatch(survival, shellNumber(5, "Survival", 1.5f, "Ann Smith"), shellNumber(9, "Survival", 2.5f, "Ann Smith")))
                    .isInstanceOf(Ambiguous.class);
            assertThat(shellMatch(survival, shellNumber(5, "Survival", 0.5f, "Ann Smith"), shell(9, "Survival", "Ann Smith")))
                    .isInstanceOf(Ambiguous.class);
            assertThat(shellMatch(survival, shellNumber(5, "Survival", -1f, "Ann Smith"), shell(9, "Survival", "Ann Smith")))
                    .isInstanceOf(Ambiguous.class);
            assertThat(matchedId(shellMatch(survival, shellNumber(9, "Survival", 2.5f, "Ann Smith"),
                    shellNumber(5, "Survival", 2.5f, "Ann Smith")))).isEqualTo(5);
        }

        @Test
        void anotherYearInBracketsIsAnotherEdition() {
            // No identity holds a year in brackets, so the identities alone would call two yearly editions alike
            ShellCandidate older = shell(12, "Rick Steves Italy (2020)", "Rick Steves");
            ShellCandidate newer = shell(40, "Rick Steves Italy (2023)", "Rick Steves");
            assertThat(older.identities()).isEqualTo(newer.identities());
            assertThat(older.years()).containsExactly(2020);

            Decision decision = shellMatch(file("S/Steves, Rick", "Rick Steves Italy (2023).epub"), older, newer);

            assertThat(decision).isInstanceOf(Ambiguous.class);
            assertThat(((Ambiguous) decision).bookIds()).containsExactlyInAnyOrder(12L, 40L);
            assertThat(shellMatch(file("S/Steves, Rick", "Rick Steves Italy.epub"), older, newer)).isInstanceOf(Ambiguous.class);
            // A year and none may be two editions as well
            assertThat(shellMatch(file("S/Steves, Rick", "Rick Steves Italy.epub"), older, shell(41, "Rick Steves Italy", "Rick Steves")))
                    .isInstanceOf(Ambiguous.class);
            // The same year twice is one edition entered twice
            assertThat(matchedId(shellMatch(file("S/Steves, Rick", "Rick Steves Italy (2020).epub"),
                    shell(40, "Rick Steves Italy (2020)", "Rick Steves"), older))).isEqualTo(12);
        }

        @Test
        void otherAuthorsAreAnotherBook() {
            // A co-author more, or another given name the path agrees with as well
            assertThat(shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    shell(12, "Survival", "Ann Smith"), shell(40, "Survival", "Ann Smith", "Bob Jones"))).isInstanceOf(Ambiguous.class);
            assertThat(shellMatch(file("S/Smith, A.", "The Last Survivors.epub"),
                    shell(12, "The Last Survivors", "Ann Smith"), shell(40, "The Last Survivors", "Anne Smith"))).isInstanceOf(Ambiguous.class);
            assertThat(shellMatch(file("S/Smith, A.", "The Last Survivors.epub"),
                    shell(12, "The Last Survivors", "Ann Smith"), shell(40, "The Last Survivors", "A. Smith"))).isInstanceOf(Ambiguous.class);
        }

        @Test
        void aSubtitleIsAnotherBook() {
            ShellCandidate plain = ShellCandidate.of(12, PATH, false, true, "Survival", null, null, List.of("Ann Smith"));
            ShellCandidate subtitled = ShellCandidate.of(40, PATH, false, true, "Survival", "A Novel", null, List.of("Ann Smith"));

            Decision decision = shellMatch(file("S/Smith, Ann", "Survival.epub"), plain, subtitled);

            assertThat(decision).isInstanceOf(Ambiguous.class);
            assertThat(decision.reason()).isEqualTo("2 fileless books qualify: book#12 \"Survival\", book#40 \"Survival\"");
        }

        @Test
        void booksWithoutAuthorsAreNeverAlike() {
            ShellCandidate first = ShellCandidate.of(15, null, true, false, "Dune", null, null, List.of());
            ShellCandidate second = ShellCandidate.of(16, null, true, false, "Dune", null, null, List.of());
            assertThat(shellMatch(file("H/Herbert, Frank", "Dune.epub"), first, second)).isInstanceOf(Ambiguous.class);
        }

        @Test
        void twoAlikeOfThreeAreNotEnough() {
            Decision decision = shellMatch(file("S/Smith, Ann", "Survival.epub"), shell(12, "Survival", "Ann Smith"),
                    shell(40, "Survival", "Ann Smith"), shell(41, "Survival", "Ann Smith", "Bob Jones"));

            assertThat(decision).isInstanceOf(Ambiguous.class);
            assertThat(((Ambiguous) decision).bookIds()).containsExactlyInAnyOrder(12L, 40L, 41L);
        }

        @Test
        void onlyTheBooksThatQualifyNeedBeAlike() {
            // Another library path turns book#41 down; the two left are alike
            ShellCandidate elsewhere = ShellCandidate.of(41, 2L, false, true, "Survival", "A Novel", null, List.of("Ann Smith"));
            assertThat(matchedId(shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    shell(40, "Survival", "Ann Smith"), elsewhere, shell(12, "Survival", "Ann Smith")))).isEqualTo(12);
        }

        @Test
        void onceTheChosenBookLeavesTheIndexAnAlikeFileGoesToTheNext() {
            // As the watcher has it: the book a file was attached to is no longer fileless
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(12, "Survival", "Ann Smith"), shell(40, "Survival", "Ann Smith")));
            assertThat(matchedId(ExistingBookMatcher.matchShell(file("S/Smith, Ann", "Survival.epub"), index))).isEqualTo(12);

            index.remove(12);

            assertThat(ExistingBookMatcher.matchShell(file("S/Smith, Ann/Other", "Survival.epub"), index))
                    .isEqualTo(new Match(40, Kind.FILELESS, "title and author agree with book#40 \"Survival\""));
        }
    }

    @Nested
    class ALoneCopyMarkerOfOne {

        @Test
        void aSecondDownloadIsTheBookOfItsTitle() {
            Decision decision = shellMatch(file("A/Kate Atkinson", "Started Early, Took My Dog (1).epub"),
                    shell(7, "Started Early, Took My Dog", "Kate Atkinson"));

            assertThat(decision).isEqualTo(new Match(7, Kind.FILELESS,
                    "title and author agree with book#7 \"Started Early, Took My Dog\" (\" (1)\" read as a copy)"));
        }

        @Test
        void notTheBookOfAnotherNumberOfItsSeries() {
            FileEvidence saga = file("V/Brian K. Vaughan", "Saga (1).epub");

            Decision second = shellMatch(saga, shellNumber(8, "Saga", 2, "Brian K. Vaughan"));

            assertThat(second).isInstanceOf(NoMatch.class);
            assertThat(second.reason()).isEqualTo("book#8 \"Saga\" is number 2 of its series, and \" (1)\" may mean volume 1");
            // It may be the copy of that book all the same: no evidence against it
            assertThat(((NoMatch) second).refused()).isEmpty();
            assertThat(matchedId(shellMatch(saga, shellNumber(8, "Saga", 1, "Brian K. Vaughan")))).isEqualTo(8);
            assertThat(matchedId(shellMatch(saga, shell(8, "Saga", "Brian K. Vaughan")))).isEqualTo(8);
        }

        @Test
        void aBookTurnedDownForAnotherNumberStillMakesTheFileAmbiguous() {
            // Read as a copy, "Saga" number 2 qualifies as much as "Saga" number 1: the name does not say which it is
            FileEvidence saga = file("V/Brian K. Vaughan", "Saga (1).epub");
            ShellCandidate second = shellNumber(2, "Saga", 2, "Brian K. Vaughan");

            Decision decision = shellMatch(saga, shellNumber(1, "Saga", 1, "Brian K. Vaughan"), second);

            assertThat(decision).isEqualTo(new Ambiguous(List.of(1L, 2L),
                    "2 fileless books qualify: book#1 \"Saga\", and book#2 \"Saga\" if \" (1)\" is a copy"));
            // So it is next to a "Saga" in no series, and next to the first volume found by its number
            assertThat(shellMatch(saga, shell(1, "Saga", "Brian K. Vaughan"), second)).isInstanceOf(Ambiguous.class);
            ShellCandidate volumeOne = ShellCandidate.of(1, PATH, false, true, "Saga", "Volume 1", null, List.of("Brian K. Vaughan"));
            assertThat(((Ambiguous) shellMatch(saga, volumeOne, second)).bookIds()).containsExactly(1L, 2L);
            // The original next to it is ambiguous as it always was
            assertThat(shellMatch(file("V/Brian K. Vaughan", "Saga.epub"), shellNumber(1, "Saga", 1, "Brian K. Vaughan"), second))
                    .isInstanceOf(Ambiguous.class);
            // A book turned down for want of evidence, or with evidence against it, is no candidate
            ShellCandidate elsewhere = ShellCandidate.of(3, 2L, false, true, "Saga", null, 2f, List.of("Brian K. Vaughan"));
            ShellCandidate otherAuthor = shellNumber(4, "Saga", 2, "Ann Smith");
            assertThat(shellMatch(saga, shellNumber(1, "Saga", 1, "Brian K. Vaughan"), elsewhere, otherAuthor))
                    .isEqualTo(new Match(1, Kind.FILELESS, "title and author agree with book#1 \"Saga\" (\" (1)\" read as a copy)"));
        }

        @Test
        void notTheBookWhoseTitleNamesAnotherVolume() {
            // No series number, but its subtitle says it is volume 2: "Saga (1).epub" may be volume 1
            ShellCandidate saga = ShellCandidate.of(8, PATH, false, true, "Saga", "Volume Two", null, List.of("Brian K. Vaughan"));

            Decision first = shellMatch(file("V/Vaughan, Brian K.", "Saga (1).epub"), saga);

            assertThat(first).isEqualTo(new NoMatch(
                    "book#8 \"Saga\" names volume 2 in its title or subtitle, and \" (1)\" may mean volume 1"));
            assertThat(((NoMatch) first).refused()).isEmpty();
            assertThat(matchedId(shellMatch(file("V/Vaughan, Brian K.", "Saga (2).epub"), saga))).isEqualTo(8);
            ShellCandidate walkingDead = ShellCandidate.of(9, PATH, false, true, "The Walking Dead", "Vol. 2: Miles Behind Us",
                    null, List.of("Robert Kirkman"));
            assertThat(shellMatch(file("K/Kirkman, Robert", "The Walking Dead (1).epub"), walkingDead)).isInstanceOf(NoMatch.class);
            // A provider's suffix names the number too
            ShellCandidate second = ShellCandidate.of(8, PATH, false, true, "Saga (Saga, #2)", null, null, List.of("Brian K. Vaughan"));
            assertThat(shellMatch(file("V/Vaughan, Brian K.", "Saga (1).epub"), second)).isInstanceOf(NoMatch.class);
            ShellCandidate firstBySuffix = ShellCandidate.of(8, PATH, false, true, "Saga (Saga, #1)", null, null, List.of("Brian K. Vaughan"));
            assertThat(matchedId(shellMatch(file("V/Vaughan, Brian K.", "Saga (1).epub"), firstBySuffix))).isEqualTo(8);
        }

        @Test
        void aBareNumberInASubtitleNamesNoVolume() {
            // "World War II" and "1936" are what the books are about; neither has a series number
            ShellCandidate unbroken = ShellCandidate.of(1, PATH, false, true, "Unbroken",
                    "A World War II Story of Survival, Resilience, and Redemption", null, List.of("Laura Hillenbrand"));
            ShellCandidate boys = ShellCandidate.of(2, PATH, false, true, "The Boys in the Boat",
                    "Nine Americans and Their Epic Quest for Gold at the 1936 Berlin Olympics", null, List.of("Daniel James Brown"));

            assertThat(shellMatch(file("H/Hillenbrand, Laura", "Unbroken (1).epub"), unbroken)).isEqualTo(new Match(1, Kind.FILELESS,
                    "title and author agree with book#1 \"Unbroken\" (\" (1)\" read as a copy)"));
            assertThat(shellMatch(file("B/Brown, Daniel James", "The Boys in the Boat (1).epub"), boys)).isEqualTo(new Match(2,
                    Kind.FILELESS, "title and author agree with book#2 \"The Boys in the Boat\" (\" (1)\" read as a copy)"));
            // Its series number still says which volume it is
            ShellCandidate second = ShellCandidate.of(1, PATH, false, true, "Unbroken",
                    "A World War II Story of Survival, Resilience, and Redemption", 2f, List.of("Laura Hillenbrand"));
            assertThat(shellMatch(file("H/Hillenbrand, Laura", "Unbroken (1).epub"), second)).isInstanceOf(NoMatch.class);
        }

        @Test
        void notTheBookOfAPlaceInItsSeriesThatIsNoWholeNumber() {
            FileEvidence saga = file("V/Brian K. Vaughan", "Saga (1).epub");
            assertThat(shellMatch(saga, shellNumber(8, "Saga", 2.5f, "Brian K. Vaughan")))
                    .isEqualTo(new NoMatch("book#8 \"Saga\" is number 2.5 of its series, and \" (1)\" may mean volume 1"));
            assertThat(shellMatch(saga, shellNumber(8, "Saga", 0.5f, "Brian K. Vaughan"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void onlyOneIsACopy() {
            // " (2)" stays the second volume
            assertThat(shellMatch(file("V/Brian K. Vaughan", "Saga (2).epub"), shell(8, "Saga", "Brian K. Vaughan")))
                    .isEqualTo(new NoMatch("no fileless book has this title"));
            assertThat(shellMatch(file("A/Kate Atkinson", "Started Early, Took My Dog (2).epub"),
                    shell(7, "Started Early, Took My Dog", "Kate Atkinson"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aComicsOneIsAnIssue() {
            ShellCandidate batman = shell(9, "Batman", "Bob Kane");
            assertThat(matchedId(shellMatch(file("K/Kane, Bob", "Batman.cbz"), batman))).isEqualTo(9);
            assertThat(shellMatch(file("K/Kane, Bob", "Batman (1).cbz"), batman)).isInstanceOf(NoMatch.class);
        }

        @Test
        void withALeadingSeriesNumberThatNumberDecides() {
            FileEvidence third = file("D/Doe, Jane", "03. The Long Night (1).epub");
            assertThat(shellMatch(third, shellNumber(9, "The Long Night", 3, "Jane Doe")))
                    .isEqualTo(new Match(9, Kind.FILELESS, "title and author agree with book#9 \"The Long Night\" (\" (1)\" read as a copy)"));
            Decision first = shellMatch(third, shellNumber(9, "The Long Night", 1, "Jane Doe"));
            assertThat(first).isInstanceOf(NoMatch.class);
            assertThat(((NoMatch) first).refused()).containsExactly(9L);
            assertThat(shellMatch(third, shell(9, "The Long Night", "Jane Doe"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void withAnotherNumberInTheNameTheOneIsACopy() {
            assertThat(matchedId(shellMatch(file("S/Smith, Ann", "Survival 3 (1).epub"), shell(11, "Survival 3", "Ann Smith"))))
                    .isEqualTo(11);
            // The name says which volume it is, so the book's own number, not 1, does not turn it down
            assertThat(shellMatch(file("V/Brian K. Vaughan", "Saga Vol. 3 (1).epub"), shellNumber(8, "Saga Vol. 3", 3, "Brian K. Vaughan")))
                    .isEqualTo(new Match(8, Kind.FILELESS, "title and author agree with book#8 \"Saga Vol. 3\" (\" (1)\" read as a copy)"));
            assertThat(matchedId(shellMatch(file("S/Smith, Ann", "Survival 3 (1).epub"), shellNumber(11, "Survival 3", 5, "Ann Smith"))))
                    .isEqualTo(11);
            // The number is part of the name, not the series number: "Survival 3" is not "Survival"
            assertThat(shellMatch(file("S/Smith, Ann", "Survival 3 (1).epub"), shellNumber(11, "Survival", 3, "Ann Smith")))
                    .isEqualTo(new NoMatch("no fileless book has this title"));
        }

        @Test
        void theAuthorRulesStillHold() {
            assertThat(shellMatch(file("S/Smith, Zadie", "Started Early, Took My Dog (1).epub"),
                    shell(7, "Started Early, Took My Dog", "Kate Atkinson"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(file("", "Started Early, Took My Dog (1).epub"),
                    shell(7, "Started Early, Took My Dog", "Kate Atkinson"))).isInstanceOf(NoMatch.class);
            // A one-word title needs the author's given name as the book has it
            ShellCandidate dawn = shell(10, "Dawn", "Octavia E. Butler");
            assertThat(shellMatch(file("B/Butler, O.", "Dawn (1).epub"), dawn)).isInstanceOf(NoMatch.class);
            assertThat(matchedId(shellMatch(file("B/Butler, Octavia", "Dawn (1).epub"), dawn))).isEqualTo(10);
            // Nor does it reach a book in another library path
            ShellCandidate elsewhere = ShellCandidate.of(7, 2L, false, true, "Started Early, Took My Dog", null, null,
                    List.of("Kate Atkinson"));
            assertThat(shellMatch(file("A/Kate Atkinson", "Started Early, Took My Dog (1).epub"), elsewhere)).isInstanceOf(NoMatch.class);
        }

        @Test
        void theFirstVolumeAndTheBookOfTheTitleBothQualifying() {
            Decision decision = shellMatch(file("A/Kate Atkinson", "Case Histories (1).epub"),
                    shell(10, "Case Histories 1", "Kate Atkinson"), shell(11, "Case Histories", "Kate Atkinson"));

            assertThat(decision).isInstanceOf(Ambiguous.class);
            assertThat(((Ambiguous) decision).bookIds()).containsExactly(10L, 11L);
        }

        @Test
        void theFirstVolumeIsStillFoundAsBefore() {
            assertThat(shellMatch(file("A/Kate Atkinson", "Case Histories (1).epub"), shell(10, "Case Histories 1", "Kate Atkinson")))
                    .isEqualTo(new Match(10, Kind.FILELESS, "title and author agree with book#10 \"Case Histories 1\""));
        }

        @Test
        void aBookFoundAsTheFirstVolumeIsNotTurnedDownForItsSeriesNumber() {
            // Only a book found through the name without its " (1)" may be another volume than 1: these are found as
            // volume 1, as before
            assertThat(shellMatch(file("A/Kate Atkinson", "Case Histories (1).epub"), shellNumber(10, "Case Histories 1", 2, "Kate Atkinson")))
                    .isEqualTo(new Match(10, Kind.FILELESS, "title and author agree with book#10 \"Case Histories 1\""));
            ShellCandidate saga = ShellCandidate.of(8, PATH, false, true, "Saga", "Volume 1", 3f, List.of("Brian K. Vaughan"));
            assertThat(shellMatch(file("V/Brian K. Vaughan", "Saga (1).epub"), saga))
                    .isEqualTo(new Match(8, Kind.FILELESS, "title and author agree with book#8 \"Saga\""));
        }

        @Test
        void alikeBooksOfTheTitle() {
            assertThat(shellMatch(file("A/Kate Atkinson", "Started Early, Took My Dog (1).epub"),
                    shell(7, "Started Early, Took My Dog", "Kate Atkinson"), shell(3, "Started Early, Took My Dog", "Kate Atkinson")))
                    .isEqualTo(new Match(3, Kind.FILELESS, "title and author agree with book#3 \"Started Early, Took My Dog\""
                            + " (\" (1)\" read as a copy), one of 2 alike fileless books (book#3, book#7)"));
        }
    }

    @Nested
    class OneFilelessBookOneFolder {

        private List<Claim<FileEvidence>> claims(FilelessShellIndex index, FileEvidence... files) {
            List<Claim<FileEvidence>> claims = new ArrayList<>();
            for (FileEvidence file : files) {
                if (ExistingBookMatcher.matchShell(file, index) instanceof Match match) {
                    String extension = file.fileName().substring(file.fileName().lastIndexOf('.') + 1);
                    claims.add(new Claim<>(file, file.fileName(), match.bookId(), file.folderKey(), extension, file.fileNameKey()));
                }
            }
            return claims;
        }

        @Test
        void survivalFromThreeFoldersAttachesToNone() {
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(40, "Survival", "Ann Smith")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("S/Smith, Ann", "Survival.epub"),
                    file("S/Smith, Ann/Series", "Survival.epub"),
                    file("S/Smith, Ann/Other", "Survival.epub"));
            assertThat(claims).hasSize(3);

            ClaimResolution<FileEvidence> resolution = ExistingBookMatcher.resolveShellClaims(claims);

            assertThat(resolution.accepted()).isEmpty();
            assertThat(resolution.rejected()).containsOnlyKeys(40L);
            assertThat(resolution.rejected().get(40L)).contains("3 folders");
        }

        @Test
        void theFormatsOfOneFolderAllAttach() {
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(41, "The Martian", "Andy Weir")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("W/Weir, Andy", "The Martian.epub"),
                    file("W/Weir, Andy", "The Martian.pdf"));

            ClaimResolution<FileEvidence> resolution = ExistingBookMatcher.resolveShellClaims(claims);

            assertThat(resolution.accepted()).containsOnlyKeys(41L);
            assertThat(resolution.accepted().get(41L)).extracting(FileEvidence::fileName)
                    .containsExactly("The Martian.epub", "The Martian.pdf");
        }

        @Test
        void kingAndKingOfTheLasVegasBadBoysDoNotBothGoToKing() {
            // Production: both new files went to one fileless "King". In the series folder both read as "King"
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(60, "King", "Mary Lane")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("L/Lane, Mary/Las Vegas Bad Boys", "King.epub"),
                    file("L/Lane, Mary/Las Vegas Bad Boys", "KING - Las Vegas Bad Boys.epub"));
            assertThat(claims).hasSize(2);

            ClaimResolution<FileEvidence> resolution = ExistingBookMatcher.resolveShellClaims(claims);

            assertThat(resolution.accepted()).isEmpty();
            assertThat(resolution.rejected().get(60L)).contains("different files of one format");
        }

        @Test
        void aSubtitledFileAndAnUnsubtitledOneAreNotBothTheSubtitledBook() {
            ShellCandidate quiver = ShellCandidate.of(61, PATH, false, true, "A Quiver Full of Arrows", "Stories", null,
                    List.of("Jeffrey Archer"));
            FilelessShellIndex index = FilelessShellIndex.of(List.of(quiver));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("A/Archer, Jeffrey", "A Quiver Full of Arrows.epub"),
                    file("A/Archer, Jeffrey", "A Quiver Full of Arrows - Stories.epub"));

            assertThat(ExistingBookMatcher.resolveShellClaims(claims).accepted()).isEmpty();
        }

        @Test
        void aCopyIsTheSameFile() {
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(62, "The Martian", "Andy Weir")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("W/Weir, Andy", "The Martian.epub"),
                    file("W/Weir, Andy", "The Martian - Copy.epub"));

            assertThat(ExistingBookMatcher.resolveShellClaims(claims).accepted().get(62L)).hasSize(2);
        }

        @Test
        void aSecondDownloadIsTheSameFileToo() {
            // "The Martian (1).epub" matches the book of its title on its own, and is one name with "The Martian.epub"
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(62, "The Martian", "Andy Weir")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("W/Weir, Andy", "The Martian.epub"),
                    file("W/Weir, Andy", "The Martian (1).epub"));
            assertThat(claims).hasSize(2);

            assertThat(ExistingBookMatcher.resolveShellClaims(claims).accepted().get(62L)).hasSize(2);
        }

        @Test
        void alikeBooksClaimedFromTwoFoldersGetNone() {
            // Every file that sees the same alike books claims the one chosen, so the claim rules hold for them as for
            // one book
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(40, "Survival", "Ann Smith"), shell(12, "Survival", "Ann Smith")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("S/Smith, Ann", "Survival.epub"),
                    file("S/Smith, Ann/Series", "Survival.epub"));
            assertThat(claims).extracting(Claim::bookId).containsExactly(12L, 12L);

            ClaimResolution<FileEvidence> resolution = ExistingBookMatcher.resolveShellClaims(claims);

            assertThat(resolution.accepted()).isEmpty();
            assertThat(resolution.rejected()).containsOnlyKeys(12L);
        }

        @Test
        void filesOfTwoLibraryPathsMaySeeDifferentAlikeBooksAndGetOneEach() {
            // A file only sees the books in its own library path or in none: the file of path 1 sees both books and
            // gets book#12, the file of path 2 sees book#40 only. Each book is claimed from one folder, so both attach
            ShellCandidate inPathOne = ShellCandidate.of(12, PATH, false, true, "Survival", null, null, List.of("Ann Smith"));
            ShellCandidate inNoPath = ShellCandidate.of(40, null, false, true, "Survival", null, null, List.of("Ann Smith"));
            FileEvidence ofPathOne = FileEvidence.of(PATH, "S/Smith, Ann", "Survival.epub", false);
            FileEvidence ofPathTwo = FileEvidence.of(2L, "S/Smith, Ann", "Survival.epub", false);
            List<Claim<FileEvidence>> claims = claims(FilelessShellIndex.of(List.of(inPathOne, inNoPath)), ofPathOne, ofPathTwo);
            assertThat(claims).extracting(Claim::bookId).containsExactly(12L, 40L);

            ClaimResolution<FileEvidence> resolution = ExistingBookMatcher.resolveShellClaims(claims);

            assertThat(resolution.accepted()).containsOnlyKeys(12L, 40L);
            // Where a single book in no path, claimed from both, gets neither
            ClaimResolution<FileEvidence> single = ExistingBookMatcher.resolveShellClaims(
                    claims(FilelessShellIndex.of(List.of(inNoPath)), ofPathOne, ofPathTwo));
            assertThat(single.accepted()).isEmpty();
            assertThat(single.rejected()).containsOnlyKeys(40L);
        }

        @Test
        void theFormatsOfOneFolderAllGoToTheOneAlikeBookChosen() {
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(40, "Survival", "Ann Smith"), shell(12, "Survival", "Ann Smith")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("S/Smith, Ann", "Survival.epub"),
                    file("S/Smith, Ann", "Survival.pdf"));

            ClaimResolution<FileEvidence> resolution = ExistingBookMatcher.resolveShellClaims(claims);

            assertThat(resolution.accepted()).containsOnlyKeys(12L);
            assertThat(resolution.accepted().get(12L)).hasSize(2);
        }

        @Test
        void thePartsOfOneAudiobookAllAttach() {
            FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(63, "The Lost Hero", "Rick Riordan")));
            List<Claim<FileEvidence>> claims = claims(index,
                    file("R/Riordan, Rick", "The Lost Hero (Part 1).m4b"),
                    file("R/Riordan, Rick", "The Lost Hero - Part 2.m4b"));
            assertThat(claims).hasSize(2);

            assertThat(ExistingBookMatcher.resolveShellClaims(claims).accepted().get(63L)).hasSize(2);
        }
    }

    @Nested
    class SameFolderBooksThatMustNotMatch {

        @Test
        void differentVolumesInOneFolder() {
            // Production: "A Dangerous Universe 3" went to the book holding "A Dangerous Universe 5.epub"
            assertThat(folderMatch(file("S/Stuart Slade", "A Dangerous Universe 3.epub"),
                    book(1, "A Dangerous Universe 5.epub"))).isInstanceOf(NoMatch.class);
            // Production: "Volume 4" went to "Volume -"
            assertThat(folderMatch(file("K/Kokoro", "Kokoro Connect Volume 4 - Michi Random.epub"),
                    book(2, "Kokoro Connect Volume - Nise Random.epub"))).isInstanceOf(NoMatch.class);
            assertThat(folderMatch(file("R/Rowling, J.K", "Harry Potter 2.epub"), book(3, "Harry Potter 1.epub")))
                    .isInstanceOf(NoMatch.class);
            assertThat(folderMatch(file("Comics/Saga", "Saga Vol. 3.cbz"), book(4, "Saga Vol. 1.cbz"), book(5, "Saga Vol. 2.cbz")))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void aSubtitleOrSeriesPartIsNotTheSameBook() {
            assertThat(folderMatch(file("L/Lane, Mary", "KING - Las Vegas Bad Boys.epub"), book(4, "King.epub")))
                    .isInstanceOf(NoMatch.class);
            assertThat(folderMatch(file("H/Herbert, Frank", "Dune - Messiah.epub"), book(5, "Dune.epub")))
                    .isInstanceOf(NoMatch.class);
            assertThat(folderMatch(file("A/Asimov, Isaac", "Foundation and Empire.epub"), book(6, "Foundation.epub")))
                    .isInstanceOf(NoMatch.class);
            // D4: same format, different name; a separate book, to merge by hand if it is one
            assertThat(folderMatch(file("A/Archer, Jeffrey", "A Quiver Full of Arrows - Stories.epub"),
                    book(7, "A Quiver Full of Arrows.epub"))).isInstanceOf(NoMatch.class);
            assertThat(folderMatch(file("A/Aarons, Edward S", "Assignment - Karachi.epub"),
                    book(8, List.of("Edward S. Aarons"), "Assignment.epub"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aNameThatIsOnlyItsOwnFolderAndANumberIsNotAnotherBookOfThatNumber() {
            // Without its folder's name "01 - Kane and Abel" is only "1", as "Book 1.epub" is
            assertThat(folderMatch(file("A/Archer, Jeffrey/Kane and Abel", "01 - Kane and Abel.epub"), book(11, "Book 1.epub")))
                    .isNotInstanceOf(Match.class);
        }

        @Test
        void twoBooksWithTheTitleAreAmbiguous() {
            assertThat(folderMatch(file("S/Smith, Ann", "Survival.pdf"), book(8, "Survival.epub"), book(9, "Survival.mobi")))
                    .isInstanceOf(Ambiguous.class);
        }

        @Test
        void anAuthorTheFolderVouchesForThatIsNotTheBooksAuthor() {
            assertThat(folderMatch(file("", "The Hobbit - J.R.R. Tolkien.pdf"), book(10, List.of("Bilbo Baggins"), "The Hobbit.epub")))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void aFileNamingAnotherPersonIsNotTheBookOfAFileNamingSomeoneElse() {
            // The book has no authors; its own file names one with initials, and the new file another
            assertThat(folderMatch(file("Poetry", "Collected Poems - W. B. Yeats.epub"), book(1, "Collected Poems - T. S. Eliot.pdf")))
                    .isInstanceOf(NoMatch.class);
            assertThat(folderMatch(file("", "Dune - B. Herbert.epub"), book(300, "Dune - F. Herbert.epub")))
                    .isInstanceOf(NoMatch.class);
            assertThat(matchedId(folderMatch(file("", "Dune - Frank P. Herbert.pdf"), book(301, "Dune - F. Herbert.epub"))))
                    .isEqualTo(301);
            assertThat(matchedId(folderMatch(file("Poetry", "Collected Poems.pdf"), book(2, "Collected Poems - T. S. Eliot.epub"))))
                    .isEqualTo(2);
        }

        @Test
        void twoNamesThatReadAsNothingAreNotTheSameBook() {
            // The grouping keeps them apart too; only a shared base name makes them one book
            assertThat(folderMatch(file("Author/Title", "(2019) [Retail].epub"), book(600, "(Unabridged).m4b")))
                    .isInstanceOf(NoMatch.class);
            assertThat(folderMatch(file("Comics/Saga", "(Digital).epub"), book(601, "(Digital) (1).cbz")))
                    .isInstanceOf(NoMatch.class);
            assertThat(matchedId(folderMatch(file("Comics/Saga", "(Digital).cbr"), book(602, "(Digital).cbz")))).isEqualTo(602);
        }

        @Test
        void saysWhichBooksTheFileSaysItIsNot() {
            // A file of the same new book follows a match only to a book none of them says it is not
            assertThat(folderMatch(file("Poetry", "Collected Poems - W. B. Yeats.epub"),
                    book(100, List.of("T. S. Eliot"), "Collected Poems - T. S. Eliot.epub")))
                    .isEqualTo(new NoMatch("the books in the folder with this title [100] are another author's or year's", java.util.Set.of(100L)));
            assertThat(((NoMatch) folderMatch(file("H/Herbert, Frank", "Dune (2021).epub"), book(11, "Dune (1965).pdf"))).refused())
                    .containsExactly(11L);
            assertThat(((NoMatch) shellMatch(file("Poetry", "Collected Poems - W. B. Yeats.epub"),
                    shell(200, "Collected Poems", "T. S. Eliot"))).refused()).containsExactly(200L);
            // Turned down only for want of evidence: no author in the path, or an initial for a one-word title
            assertThat(((NoMatch) shellMatch(file("Poetry", "Collected Poems.epub"),
                    shell(201, "Collected Poems", "T. S. Eliot"))).refused()).isEmpty();
            assertThat(((NoMatch) shellMatch(file("S/Smith, J", "Survival.epub"), shell(202, "Survival", "Jane Smith"))).refused())
                    .isEmpty();
            // An unconfirmed reading that names someone else is no reading at all
            assertThat(((NoMatch) shellMatch(file("", "Primal Heat - Lora Leigh.epub"), shell(203, "Primal Heat", "Jane Doe"))).refused())
                    .isEmpty();
        }

        @Test
        void differentYears() {
            assertThat(folderMatch(file("H/Herbert, Frank", "Dune (2021).epub"), book(11, "Dune (1965).pdf")))
                    .isInstanceOf(NoMatch.class);
        }
    }

    @Nested
    class SameFolderBooksThatMustMatch {

        @Test
        void anotherFormatOfTheSameTitle() {
            assertThat(matchedId(folderMatch(file("T/Tolkien, J.R.R.", "The Hobbit.pdf"),
                    book(10, "The Hobbit.epub"), book(11, "The Silmarillion.epub")))).isEqualTo(10);
            assertThat(matchedId(folderMatch(file("T/Tolkien, J.R.R.", "The Hobbit - J.R.R. Tolkien.pdf"),
                    book(12, "The Hobbit.epub")))).isEqualTo(12);
            assertThat(matchedId(folderMatch(file("", "The Hobbit - J.R.R. Tolkien.pdf"), book(13, "Hobbit, The.epub"))))
                    .isEqualTo(13);
            assertThat(matchedId(folderMatch(file("Rowling/HP", "Harry Potter 2.pdf"),
                    book(16, "Harry Potter 1.epub"), book(17, "Harry Potter 2.epub")))).isEqualTo(17);
            assertThat(matchedId(folderMatch(file("Comics/Saga", "Saga Vol. 2.cbr"), book(4, "Saga Vol. 1.cbz"), book(5, "Saga Vol. 2.cbz"))))
                    .isEqualTo(5);
            assertThat(matchedId(folderMatch(file("H/Herbert, Frank", "Dune (1965).epub"), book(18, "Dune.pdf")))).isEqualTo(18);
        }

        @Test
        void editionsPartsAndAudiobooksOfTheSameTitle() {
            assertThat(folderMatch(file("American Gods", "American Gods The Tenth Anniversary Edition (A Full Cast Production).m4b"),
                    book(14, "American Gods.epub"))).isInstanceOf(Match.class);
            assertThat(folderMatch(file("The Hobbit", "The Hobbit Unabridged.m4b"), book(15, "The Hobbit.epub")))
                    .isInstanceOf(Match.class);
            assertThat(folderMatch(file("Heroes of Olympus", "1. The Lost Hero (part 2).m4b"), book(18, "1. The Lost Hero (part 1).m4b")))
                    .isInstanceOf(Match.class);
            // A folder audiobook's name is its folder's
            assertThat(folderMatch(file("Author", "Title.epub"), book(19, "Title"))).isInstanceOf(Match.class);
        }

        @Test
        void anAuthorPartThatIsTheBooksAuthor() {
            assertThat(folderMatch(file("", "American Gods - Neil Gaiman.mobi"), book(20, List.of("Neil Gaiman"), "American Gods.epub")))
                    .isInstanceOf(Match.class);
            assertThat(folderMatch(file("", "American Gods.mobi"), book(21, List.of("Neil Gaiman"), "American Gods - Neil Gaiman.epub")))
                    .isInstanceOf(Match.class);
            assertThat(folderMatch(file("", "American Gods - Neil Gaiman.mobi"), book(22, "American Gods.epub")))
                    .as("nothing confirms the author part").isInstanceOf(NoMatch.class);
        }

        @Test
        void aDuplicatesCopyGoesToTheBookOfItsOriginal() {
            // Production example: kept attached, as it should be
            assertThat(folderMatch(file("A/Anthony, Michael", "Mass Casualties - Michael Anthony (2009) (1).epub"),
                    book(19, "Mass Casualties - Michael Anthony (2009).epub"))).isInstanceOf(Match.class);
            assertThat(folderMatch(file("X", "Some Book - Copy.epub"), book(20, "Some Book.epub"))).isInstanceOf(Match.class);
            assertThat(folderMatch(file("X", "Some Book.pdf"), book(21, "Some Book (1).epub"))).isInstanceOf(Match.class);
            assertThat(folderMatch(file("X", "Some Book (2).epub"), book(22, "Some Other Book.epub"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void aFileSharingABaseNameSharesTheBook() {
            // Whatever the names read as: the two share sidecar files
            assertThat(matchedId(folderMatch(file("Author", "Book (Part 1).m4b"), book(23, "Book (Part 1).epub")))).isEqualTo(23);
            assertThat(matchedId(folderMatch(file("Author", "BOOK (part 1).pdf"), book(23, "Book (Part 1).epub")))).isEqualTo(23);
        }

        @Test
        void theKindIsSameFolder() {
            assertThat(folderMatch(file("T/Tolkien, J.R.R.", "The Hobbit.pdf"), book(10, "The Hobbit.epub")))
                    .isEqualTo(new Match(10, Kind.SAME_FOLDER, "same title in the same folder"));
        }
    }

    @Test
    void twentyThousandFilesAgainstTwentyThousandFilelessBooksTakeWellUnderTenSeconds() {
        List<ShellCandidate> shells = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            shells.add(shell(100_000 + i, "Title Number " + i + " Of Many", "Author" + (char) ('a' + i % 26) + " Person"));
        }
        shells.add(shell(99, "The Martian", "Andy Weir"));
        long start = System.nanoTime();
        FilelessShellIndex index = FilelessShellIndex.of(shells);
        int matched = 0;
        for (int i = 0; i < 20_000; i++) {
            if (ExistingBookMatcher.matchShell(file("W/Weir, Andy/Folder " + i, "The Martian " + i + ".epub"), index) instanceof Match) {
                matched++;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(matched).isZero();
        assertThat(millis).isLessThan(10_000);
        assertThat(ExistingBookMatcher.matchShell(file("W/Weir, Andy", "The Martian.epub"), index)).isInstanceOf(Match.class);
    }

    @Test
    void aClaimedBookLeavesTheIndex() {
        FilelessShellIndex index = FilelessShellIndex.of(List.of(shell(70, "The Martian", "Andy Weir")));
        index.remove(70);
        assertThat(index.size()).isZero();
        assertThat(ExistingBookMatcher.matchShell(file("W/Weir, Andy", "The Martian.epub"), index)).isInstanceOf(NoMatch.class);
    }
}
