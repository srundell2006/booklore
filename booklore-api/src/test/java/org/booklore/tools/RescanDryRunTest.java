package org.booklore.tools;

import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.tools.RescanDryRun.Line;
import org.booklore.tools.RescanDryRun.Report;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * The dry run over exports holding the production incident's cases: it must decide as the rescan's grouping does
 * (BookGroupingServiceTest has the same cases against the service), from the TSV files alone.
 */
class RescanDryRunTest {

    @TempDir
    Path dir;

    @BeforeEach
    void exports() throws IOException {
        Files.writeString(dir.resolve("shells.tsv"), """
                id\tlibrary_path_id\tis_physical\thas_cover\ttitle\tsubtitle\tseries_number\tauthors
                167862\t1\t0\t1\tAssignment\tNULL\tNULL\tI. Christie
                500\t1\t0\t0\tSurvival\tNULL\tNULL\tAnn Smith
                700\tNULL\t0\t1\tKing\tNULL\tNULL\tMary Lane
                901\t1\t0\t0\tThe Martian\tA Novel\t1\tAndy Weir; Someone Else
                950\tNULL\t1\t0\tDune\tNULL\tNULL\tNULL
                960\t1\t0\t0\tEmma\tNULL\tNULL\t
                1200\tNULL\t1\t0\tStarted Early, Took My Dog\tNULL\tNULL\tKate Atkinson
                1201\t1\t0\t1\tStarted Early, Took My Dog\tNULL\tNULL\tKate Atkinson
                1300\t1\t0\t1\tSaga\tNULL\t2\tBrian K. Vaughan
                """);
        Files.writeString(dir.resolve("existing.tsv"), """
                600\t1\tS/Stuart Slade\tA Dangerous Universe 5.epub\tEPUB
                800\t1\tA/Archer, Jeffrey\tA Quiver Full of Arrows.epub\tEPUB\tJeffrey Archer
                900\t1\tA/Anthony, Michael\tMass Casualties - Michael Anthony (2009).epub\tEPUB\tMichael Anthony
                1100\t1\tX/Twin\tTwin.epub\tEPUB\tNULL
                1101\t1\tX/Twin\tTwin.mobi\tMOBI\tNULL
                """);
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tA/Aarons, Edward S\tAssignment - Karachi.epub
                1\tS/Smith, Ann\tSurvival.epub
                1\tS/Smith, Ann/Series\tSurvival.epub
                1\tS/Smith, Ann/Other\tSurvival.epub
                1\tL/Lane, Mary\tKing.epub
                1\tL/Lane, Mary\tKING - Las Vegas Bad Boys.epub
                1\tW/Weir, Andy\tThe Martian.epub
                1\tW/Weir, Andy\tThe Martian (1).epub
                1\tS/Stuart Slade\tA Dangerous Universe 3.epub
                1\tS/Stuart Slade\tA Dangerous Universe 5.pdf
                1\tA/Archer, Jeffrey\tA Quiver Full of Arrows - Stories.epub
                1\tA/Anthony, Michael\tMass Casualties - Michael Anthony (2009) (1).epub
                1\tH/Herbert, Frank\tDune.epub
                1\tA/Austen, Jane\tEmma.epub
                1\tX/Twin\tTwin.pdf
                1\tAudio\tFolder Book
                1\tA/Kate Atkinson\tStarted Early, Took My Dog (1).epub
                1\tV/Brian K. Vaughan\tSaga (1).epub
                """);
    }

    private Report run() throws IOException {
        return RescanDryRun.run(RescanDryRun.Exports.read(dir.resolve("shells.tsv"), dir.resolve("existing.tsv"),
                dir.resolve("newfiles.tsv")), LibraryOrganizationMode.AUTO_DETECT);
    }

    private static Map<String, String> decisions(Report report) {
        return report.lines().stream().collect(Collectors.toMap(
                line -> line.file().fileSubPath() + "/" + line.file().fileName(),
                line -> line.decision() + " " + line.target(),
                (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    void decidesEachFileAsTheRescanWould() throws IOException {
        Report report = run();

        assertThat(decisions(report)).containsExactly(
                entry("A/Aarons, Edward S/Assignment - Karachi.epub", "new-book -"),
                entry("S/Smith, Ann/Survival.epub", "ambiguous 500"),
                entry("S/Smith, Ann/Series/Survival.epub", "ambiguous 500"),
                entry("S/Smith, Ann/Other/Survival.epub", "ambiguous 500"),
                entry("L/Lane, Mary/King.epub", "attach-shell 700"),
                entry("L/Lane, Mary/KING - Las Vegas Bad Boys.epub", "new-book -"),
                entry("W/Weir, Andy/The Martian.epub", "attach-shell 901"),
                entry("W/Weir, Andy/The Martian (1).epub", "attach-shell 901"),
                entry("S/Stuart Slade/A Dangerous Universe 3.epub", "new-book -"),
                entry("S/Stuart Slade/A Dangerous Universe 5.pdf", "attach-same-folder 600"),
                entry("A/Archer, Jeffrey/A Quiver Full of Arrows - Stories.epub", "new-book -"),
                entry("A/Anthony, Michael/Mass Casualties - Michael Anthony (2009) (1).epub", "attach-same-folder 900"),
                entry("H/Herbert, Frank/Dune.epub", "attach-shell 950"),
                entry("A/Austen, Jane/Emma.epub", "new-book -"),
                entry("X/Twin/Twin.pdf", "ambiguous 1100,1101"),
                entry("Audio/Folder Book", "new-book -"),
                entry("A/Kate Atkinson/Started Early, Took My Dog (1).epub", "attach-shell 1201"),
                entry("V/Brian K. Vaughan/Saga (1).epub", "new-book -"));
        assertThat(report.decisions()).containsExactly(
                entry("ambiguous", 4), entry("attach-same-folder", 2), entry("attach-shell", 5), entry("new-book", 7));
    }

    @Test
    void saysWhyAndWhichNewBookEachFileWouldBe() throws IOException {
        Map<String, Line> lines = run().lines().stream()
                .collect(Collectors.toMap(line -> line.file().fileSubPath() + "/" + line.file().fileName(), line -> line));

        assertThat(lines.get("L/Lane, Mary/King.epub").reason()).startsWith("title and author agree with book#700");
        // A lone " (1)" also reads as the copy it is: the file matches the fileless book on its own
        assertThat(lines.get("W/Weir, Andy/The Martian (1).epub").reason())
                .isEqualTo("title and author agree with book#901 \"The Martian\" (\" (1)\" read as a copy)");
        // Of alike fileless books, one that is not a paper copy's record
        assertThat(lines.get("A/Kate Atkinson/Started Early, Took My Dog (1).epub").reason())
                .isEqualTo("title and author agree with book#1201 \"Started Early, Took My Dog\" (\" (1)\" read as a copy),"
                        + " one of 2 alike fileless books (book#1200, book#1201)");
        assertThat(lines.get("V/Brian K. Vaughan/Saga (1).epub").reason()).isEqualTo("folder: no book has files in the folder"
                + " | fileless: book#1300 \"Saga\" is number 2 of its series, and \" (1)\" may mean volume 1");
        assertThat(lines.get("S/Smith, Ann/Survival.epub").reason()).startsWith("fileless book#500 is claimed by files of 3 folders");
        assertThat(lines.get("A/Austen, Jane/Emma.epub").reason())
                .isEqualTo("folder: no book has files in the folder | fileless: book#960 \"Emma\" has no author to check the file against");
        assertThat(lines.get("X/Twin/Twin.pdf").reason()).isEqualTo("folder: 2 books in the folder have this title");
        // Group keys keep the rescan's format, and a copy is in its original's group
        assertThat(lines.get("W/Weir, Andy/The Martian (1).epub").groupKey())
                .isEqualTo(lines.get("W/Weir, Andy/The Martian.epub").groupKey())
                .startsWith("1:W/Weir, Andy:");
        assertThat(lines.get("S/Stuart Slade/A Dangerous Universe 3.epub").groupKey())
                .isNotEqualTo(lines.get("S/Stuart Slade/A Dangerous Universe 5.pdf").groupKey());
    }

    @Test
    void countsTheReasonsBooksWereTurnedDownWithoutTheirParticulars() throws IOException {
        Report report = run();

        assertThat(report.rejections())
                .containsEntry("claim: claimed by files of N folders [...]", 3)
                .containsEntry("fileless: has no author to check the file against", 1)
                .containsEntry("folder: N books in the folder have this title", 1)
                .containsEntry("fileless: is number N of its series, and \" (1)\" may mean volume 1", 1);
    }

    @Test
    void countsOneRuleOnceWhateverItsNumbers() throws IOException {
        // A place in a series of 2, 2.5 or 3, and one volume or two named, are particulars; the " (1)" and the volume
        // 1 it may mean are the reason's own words
        Files.writeString(dir.resolve("shells.tsv"), """
                1\t1\t0\t1\tSaga\tNULL\t2\tBrian K. Vaughan
                2\t1\t0\t1\tFables\tNULL\t2.5\tBill Willingham
                3\t1\t0\t1\tY The Last Man\tNULL\t3\tBrian K. Vaughan
                4\t1\t0\t1\tMonstress\tVolume Two\tNULL\tMarjorie Liu
                5\t1\t0\t1\tPaper Girls\tVolume 2, Part 3\tNULL\tBrian K. Vaughan
                """);
        Files.writeString(dir.resolve("existing.tsv"), "");
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tV/Brian K. Vaughan\tSaga (1).epub
                1\tW/Bill Willingham\tFables (1).epub
                1\tV/Brian K. Vaughan\tY The Last Man (1).epub
                1\tL/Marjorie Liu\tMonstress (1).epub
                1\tV/Brian K. Vaughan\tPaper Girls (1).epub
                """);

        Report report = run();

        assertThat(report.decisions()).containsExactly(entry("new-book", 5));
        assertThat(report.rejections()).containsExactly(
                entry("fileless: is number N of its series, and \" (1)\" may mean volume 1", 3),
                entry("fileless: names volume N in its title or subtitle, and \" (1)\" may mean volume 1", 2));
    }

    @Test
    void aSecondDownloadOfATitleTwoVolumesShareGoesToNeither() throws IOException {
        // Read as a copy, "Saga (1).epub" is the second volume as much as the first, and so is "Saga.epub" next to it
        Files.writeString(dir.resolve("shells.tsv"), """
                1\t1\t0\t1\tSaga\tNULL\t1\tBrian K. Vaughan
                2\t1\t0\t1\tSaga\tNULL\t2\tBrian K. Vaughan
                """);
        Files.writeString(dir.resolve("existing.tsv"), "");
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tV/Brian K. Vaughan\tSaga.epub
                1\tV/Brian K. Vaughan\tSaga (1).epub
                """);

        Report report = run();

        assertThat(decisions(report)).containsExactly(
                entry("V/Brian K. Vaughan/Saga.epub", "ambiguous 1,2"),
                entry("V/Brian K. Vaughan/Saga (1).epub", "ambiguous 1,2"));
        assertThat(report.lines().get(1).reason())
                .isEqualTo("fileless: 2 fileless books qualify: book#1 \"Saga\", and book#2 \"Saga\" if \" (1)\" is a copy");
        assertThat(report.rejections()).containsExactly(entry("fileless: several fileless books qualify", 2));
    }

    @Test
    void readsTheSeriesNameOfAFilelessBookWhenTheExportHasOne() throws IOException {
        // The " (1)" rule weighs a place in a series only when the series may be what the name calls it
        Files.writeString(dir.resolve("shells.tsv"), """
                id\tlibrary_path_id\tis_physical\thas_cover\ttitle\tsubtitle\tseries_number\tauthors\tseries_name
                1\t1\t0\t1\tThe Testaments\tNULL\t2\tMargaret Atwood\tThe Handmaid's Tale
                2\t1\t0\t1\tSaga\tNULL\t2\tBrian K. Vaughan\tSaga
                3\t1\t0\t1\tY The Last Man\tNULL\t3\tBrian K. Vaughan\tNULL
                """);
        Files.writeString(dir.resolve("existing.tsv"), "");
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tA/Atwood, Margaret\tThe Testaments (1).epub
                1\tV/Brian K. Vaughan\tSaga (1).epub
                1\tV/Brian K. Vaughan\tY The Last Man (1).epub
                """);

        Report report = run();

        assertThat(RescanDryRun.Exports.read(dir.resolve("shells.tsv"), dir.resolve("existing.tsv"), dir.resolve("newfiles.tsv"))
                .shells()).extracting(RescanDryRun.Shell::seriesName).containsExactly("The Handmaid's Tale", "Saga", null);
        assertThat(decisions(report)).containsExactly(
                entry("A/Atwood, Margaret/The Testaments (1).epub", "attach-shell 1"),
                entry("V/Brian K. Vaughan/Saga (1).epub", "new-book -"),
                entry("V/Brian K. Vaughan/Y The Last Man (1).epub", "new-book -"));
        assertThat(report.lines().get(1).reason()).isEqualTo("folder: no book has files in the folder | fileless: book#2"
                + " \"Saga\" is number 2 of a series of the same name, and \" (1)\" may mean volume 1");
        assertThat(report.rejections()).containsExactly(
                entry("fileless: is number N of a series of the same name, and \" (1)\" may mean volume 1", 1),
                entry("fileless: is number N of its series, and \" (1)\" may mean volume 1", 1));
    }

    @Test
    void readsAnExportWithoutSeriesNamesAsSeriesWithoutAName() throws IOException {
        // Eight columns, as exports made before series_name was: only a "(Series, #N)" suffix names a series, and
        // the " (1)" rule turns down a "The Testaments" numbered 2 that has none
        Files.writeString(dir.resolve("existing.tsv"), "");
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tA/Atwood, Margaret\tThe Testaments (1).epub
                """);
        Files.writeString(dir.resolve("shells.tsv"), """
                id\tlibrary_path_id\tis_physical\thas_cover\ttitle\tsubtitle\tseries_number\tauthors
                1\t1\t0\t1\tThe Testaments\tNULL\t2\tMargaret Atwood
                """);
        assertThat(decisions(run())).containsExactly(entry("A/Atwood, Margaret/The Testaments (1).epub", "new-book -"));

        Files.writeString(dir.resolve("shells.tsv"), """
                2\t1\t0\t1\tThe Testaments (The Handmaid's Tale, #2)\tNULL\tNULL\tMargaret Atwood
                """);
        assertThat(decisions(run())).containsExactly(entry("A/Atwood, Margaret/The Testaments (1).epub", "attach-shell 2"));

        Files.writeString(dir.resolve("shells.tsv"), """
                1\t1\t0\t1\tThe Testaments\tNULL\t2\tMargaret Atwood\tThe Handmaid's Tale\textra
                """);
        assertThatThrownBy(this::run).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not 8 to 9");
    }

    @Test
    void saysWhatCameOfWeakMatches() throws IOException {
        // The dry run of a real library: the newer rules took these files from the books they had before
        Files.writeString(dir.resolve("shells.tsv"), """
                id\tlibrary_path_id\tis_physical\thas_cover\ttitle\tsubtitle\tseries_number\tauthors\tseries_name
                10\t1\t0\t1\tKilling Floor\tNULL\tNULL\tLee Child\tNULL
                11\t1\t0\t1\tKilling Floor\tNULL\tNULL\tLee Child\tNULL
                70\t1\t0\t1\tMEG\tNULL\tNULL\tSteve Alten\tNULL
                71\t1\t0\t1\tThe Subtle Knife\tNULL\t2\tPhilip Pullman\tHis Dark Materials
                1\t1\t0\t1\tNico (Book 1)\tNULL\tNULL\tNaomi West\tNULL
                2\t1\t0\t1\tNico\tNULL\tNULL\tNaomi West\tNULL
                40\t1\t0\t1\tSurvival\tNULL\tNULL\tAnn Smith\tNULL
                41\t1\t0\t1\tSurvival\tNULL\tNULL\tAnn Smith\tNULL
                """);
        Files.writeString(dir.resolve("existing.tsv"), """
                280632\t1\tC/Lee Child/Jack Reacher\t01. Killing Floor - Lee Child (2006).mobi\tMOBI\tLee Child
                """);
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tC/Lee Child/Jack Reacher\tThe Killing Floor (1).epub
                1\tC/Lee Child/Jack Reacher\tKilling Floor.epub
                1\tA/Steve Alten/MEG\tThe MEG.epub
                1\tA/Steve Alten\tMEG (1).epub
                1\tP/Philip Pullman/His Dark Materials\tThe Subtle Knife.epub
                1\tP/Philip Pullman/His Dark Materials\tThe Subtle Knife (1).epub
                1\tP/Philip Pullman/His Dark Materials\t02. The Subtle Knife - Philip Pullman (2001) (1).epub
                1\tW/Naomi West\tNico (1).epub
                1\tS/Smith, Ann\tSurvival.epub
                """);

        Report report = run();

        assertThat(decisions(report)).containsExactly(
                entry("C/Lee Child/Jack Reacher/The Killing Floor (1).epub", "attach-same-folder 280632"),
                entry("C/Lee Child/Jack Reacher/Killing Floor.epub", "attach-same-folder 280632"),
                entry("A/Steve Alten/MEG/The MEG.epub", "attach-shell 70"),
                entry("A/Steve Alten/MEG (1).epub", "ambiguous 70"),
                entry("P/Philip Pullman/His Dark Materials/The Subtle Knife.epub", "attach-shell 71"),
                entry("P/Philip Pullman/His Dark Materials/The Subtle Knife (1).epub", "attach-shell 71"),
                entry("P/Philip Pullman/His Dark Materials/02. The Subtle Knife - Philip Pullman (2001) (1).epub", "ambiguous 71"),
                entry("W/Naomi West/Nico (1).epub", "attach-shell 1"),
                entry("S/Smith, Ann/Survival.epub", "attach-shell 40"));
        Map<String, String> reasons = report.lines().stream().collect(Collectors.toMap(
                line -> line.file().fileName(), Line::reason));
        assertThat(reasons.get("Killing Floor.epub")).isEqualTo("goes with the files it would be one book with; its own"
                + " weak match yields to theirs: title and author agree with book#10 \"Killing Floor\", one of 2 alike"
                + " fileless books (book#10, book#11)");
        assertThat(reasons.get("MEG (1).epub")).isEqualTo("its weak claim on fileless book#70 yields to the strong claims"
                + " of other files, which get it: claimed by files of 2 folders [1:A/Steve Alten, 1:A/Steve Alten/MEG]:"
                + " The MEG.epub, MEG (1).epub; the weak claims of MEG (1).epub yield to the others");
        assertThat(reasons.get("02. The Subtle Knife - Philip Pullman (2001) (1).epub"))
                .startsWith("its weak claim on fileless book#71 yields to the strong claims of other files, which get it:"
                        + " claimed by different files of one format: ");
        assertThat(reasons.get("Nico (1).epub")).isEqualTo("title and author agree with book#1 \"Nico (Book 1)\"; book#2"
                + " \"Nico\" qualifies only if \" (1)\" is a copy, and yields");
        assertThat(report.decisions()).containsExactly(
                entry("ambiguous", 2), entry("attach-same-folder", 2), entry("attach-shell", 5));
        assertThat(report.rejections()).containsExactly(
                entry("claim: a weak claim yields to the strong claims of other files", 2));
        assertThat(report.weakMatches()).containsExactly(
                entry("a match by its name as it reads goes before books found with \" (1)\" read as a copy", 1),
                entry("attached by its own weak match", 1),
                entry("its weak match agrees with the strong match of the files it would be one book with", 1),
                entry("its weak match yields to the strong claims of other files on its book", 2),
                entry("its weak match yields to the strong match of the files it would be one book with", 1));
    }

    @Test
    void countsTheFilesAWeakMatchGaveABookAndThoseItCameToNothingFor() throws IOException {
        // A follower of a weak match is a new attach too; weak matches that do not agree split their group
        Files.writeString(dir.resolve("shells.tsv"), """
                10\t1\t0\t1\tSurvival\tNULL\tNULL\tAnn Smith
                11\t1\t0\t1\tSurvival\tNULL\tNULL\tAnn Smith
                20\t1\t0\t1\tThe Martian\tNULL\tNULL\tAnn Smith
                """);
        Files.writeString(dir.resolve("existing.tsv"), "");
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tS/Smith, Ann/Survival\tSurvival.epub
                1\tS/Smith, Ann/Survival\tNotes.pdf
                1\tS/Smith, Ann/Mixed\tSurvival.epub
                1\tS/Smith, Ann/Mixed\tThe Martian (1).epub
                """);

        Report report = RescanDryRun.run(RescanDryRun.Exports.read(dir.resolve("shells.tsv"), dir.resolve("existing.tsv"),
                dir.resolve("newfiles.tsv")), LibraryOrganizationMode.BOOK_PER_FOLDER);

        assertThat(decisions(report)).containsExactly(
                entry("S/Smith, Ann/Survival/Survival.epub", "attach-shell 10"),
                entry("S/Smith, Ann/Survival/Notes.pdf", "attach-shell 10"),
                entry("S/Smith, Ann/Mixed/Survival.epub", "ambiguous 10,20"),
                entry("S/Smith, Ann/Mixed/The Martian (1).epub", "ambiguous 10,20"));
        assertThat(report.weakMatches()).containsExactly(
                entry("attached by a weak match of the files it would be one book with", 1),
                entry("attached by its own weak match", 1),
                entry("its weak match comes to nothing: its group is split or turned down, or its book's claims rejected", 2));
    }

    @Test
    void keepsTheAttachesOfBeforeWhatACopyReadingSaysAgainstABookAside() throws IOException {
        // Read as a copy, the pdf is not Neil Gaiman's: the rules of before never looked, and the pdf goes with the
        // epub as it did. That only keeps a group from a book that weak matches alone give it
        Files.writeString(dir.resolve("shells.tsv"), """
                10\t1\t0\t1\tGood Omens\tNULL\tNULL\tNeil Gaiman
                """);
        Files.writeString(dir.resolve("existing.tsv"), "");
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tG/Neil Gaiman\tGood Omens.epub
                1\tG/Neil Gaiman\tGood Omens - T. Pratchett (1).pdf
                1\tG/Neil Gaiman/Copies\tGood Omens (1).epub
                1\tG/Neil Gaiman/Copies\tGood Omens - T. Pratchett (1).pdf
                """);

        Report report = run();

        assertThat(decisions(report)).containsExactly(
                entry("G/Neil Gaiman/Good Omens.epub", "attach-shell 10"),
                entry("G/Neil Gaiman/Good Omens - T. Pratchett (1).pdf", "attach-shell 10"),
                entry("G/Neil Gaiman/Copies/Good Omens (1).epub", "ambiguous 10"),
                entry("G/Neil Gaiman/Copies/Good Omens - T. Pratchett (1).pdf", "ambiguous 10"));
        assertThat(report.lines().get(1).reason()).isEqualTo("goes with the files it would be one book with");
        assertThat(report.rejections()).containsExactly(
                entry("fileless: is not by '...' (\" (1)\" read as a copy)", 1),
                entry("group: the book a file it would be one book with matched turned another of them down", 2));
        assertThat(report.weakMatches()).containsExactly(
                entry("its weak match comes to nothing: its group is split or turned down, or its book's claims rejected", 1));
    }

    @Test
    void anAudiobookCopyInTheSubfolderOfItsEbookGoesWithIt() throws IOException {
        // BOOK_PER_FOLDER groups the audiobook with the ebook of the folder above; its weak match is the epub's book,
        // which it claims from the epub's folder, as it did with no match before
        Files.writeString(dir.resolve("shells.tsv"), """
                20\t1\t0\t1\tSurvival\tNULL\tNULL\tKate Atkinson
                """);
        Files.writeString(dir.resolve("existing.tsv"), "");
        Files.writeString(dir.resolve("newfiles.tsv"), """
                1\tA/Kate Atkinson/Survival\tSurvival.epub
                1\tA/Kate Atkinson/Survival/Audio\tSurvival (1).m4b
                """);

        Report report = RescanDryRun.run(RescanDryRun.Exports.read(dir.resolve("shells.tsv"), dir.resolve("existing.tsv"),
                dir.resolve("newfiles.tsv")), LibraryOrganizationMode.BOOK_PER_FOLDER);

        assertThat(decisions(report)).containsExactly(
                entry("A/Kate Atkinson/Survival/Survival.epub", "attach-shell 20"),
                entry("A/Kate Atkinson/Survival/Audio/Survival (1).m4b", "attach-shell 20"));
        assertThat(report.weakMatches()).containsExactly(
                entry("its weak match agrees with the strong match of the files it would be one book with", 1));
    }

    @Test
    void writesTheReportFromTheCommandLine() throws IOException {
        Path out = dir.resolve("report.tsv");

        RescanDryRun.main(new String[]{"--dir", dir.toString(), "--out", out.toString()});

        List<String> lines = Files.readAllLines(out);
        assertThat(lines.getFirst()).isEqualTo("# decision\ttarget\tgroup_key\treason\tlibrary_path_id\tfile_sub_path\tfile_name");
        assertThat(lines).filteredOn(line -> !line.startsWith("#")).hasSize(18)
                .allSatisfy(line -> assertThat(line.split("\t", -1)).hasSize(7));
        assertThat(lines).contains("#   attach-shell\t5", "#   new-book\t7",
                "# weak matches (one of alike fileless books, or \" (1)\" read as a copy), by outcome:");
    }

    @Test
    void readsWhatMariaDbBatchModeWrites() {
        assertThat(RescanDryRun.Exports.unescape("a\\tb\\nc\\\\d")).isEqualTo("a\tb\nc\\d");
        assertThat(RescanDryRun.Exports.unescape("plain")).isEqualTo("plain");
    }
}
