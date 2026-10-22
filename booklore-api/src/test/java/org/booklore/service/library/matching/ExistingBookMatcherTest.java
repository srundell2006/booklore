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
        void aSeriesPrefixOnlyCountsForThatSeriesNumber() {
            FileEvidence kaneAndAbel = file("A/Archer, Jeffrey/Kane and Abel", "01 - Kane and Abel.epub");
            assertThat(shellMatch(kaneAndAbel, shellNumber(50, "Kane and Abel", 2, "Jeffrey Archer"))).isInstanceOf(NoMatch.class);
            assertThat(shellMatch(kaneAndAbel, shell(51, "Kane and Abel", "Jeffrey Archer"))).isInstanceOf(NoMatch.class);
        }

        @Test
        void anotherLibraryPathDoesNotMatch() {
            ShellCandidate elsewhere = ShellCandidate.of(24, 2L, false, true, "The Martian", null, null, List.of("Andy Weir"));
            assertThat(shellMatch(file("W/Weir, Andy", "The Martian.epub"), elsewhere)).isInstanceOf(NoMatch.class);
        }

        @Test
        void twoQualifyingBooksAreAmbiguous() {
            assertThat(shellMatch(file("S/Smith, Ann", "Survival.epub"),
                    shell(20, "Survival", "Ann Smith"), shell(21, "Survival", "Ann Smith"))).isInstanceOf(Ambiguous.class);
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
