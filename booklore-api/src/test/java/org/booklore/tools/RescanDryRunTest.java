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
                .containsEntry("fileless: is number N of its series, and \" (N)\" may mean volume N", 1);
    }

    @Test
    void writesTheReportFromTheCommandLine() throws IOException {
        Path out = dir.resolve("report.tsv");

        RescanDryRun.main(new String[]{"--dir", dir.toString(), "--out", out.toString()});

        List<String> lines = Files.readAllLines(out);
        assertThat(lines.getFirst()).isEqualTo("# decision\ttarget\tgroup_key\treason\tlibrary_path_id\tfile_sub_path\tfile_name");
        assertThat(lines).filteredOn(line -> !line.startsWith("#")).hasSize(18)
                .allSatisfy(line -> assertThat(line.split("\t", -1)).hasSize(7));
        assertThat(lines).contains("#   attach-shell\t5", "#   new-book\t7");
    }

    @Test
    void readsWhatMariaDbBatchModeWrites() {
        assertThat(RescanDryRun.Exports.unescape("a\\tb\\nc\\\\d")).isEqualTo("a\tb\nc\\d");
        assertThat(RescanDryRun.Exports.unescape("plain")).isEqualTo("plain");
    }
}
