package org.booklore.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Flyway migration that indexes book_file by path. Tests run on H2 with a schema generated from the entities and
 * Flyway turned off, so this checks the script itself; it was tried on MariaDB 11.4, where the scan's path lookups use
 * the index instead of reading the whole table.
 */
class BookFileIndexMigrationTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void indexesBookFileBySubPathAndANamePrefixThatFitsInnoDbsKeyLimit() throws IOException {
        String sql = statements(migration(147));

        assertThat(sql).containsIgnoringCase(
                "CREATE INDEX IF NOT EXISTS idx_book_file_sub_path_name ON book_file (file_sub_path, file_name(255))");
    }

    @Test
    void leavesTheAlgorithmAndLockToMariaDb() throws IOException {
        // An explicit ALGORITHM or LOCK makes MariaDB fail the migration, and so the application's start, where it
        // would otherwise fall back to a slower way of building the index
        String sql = statements(migration(147));

        assertThat(sql).doesNotContainIgnoringCase("ALGORITHM").doesNotContainIgnoringCase("LOCK");
    }

    @Test
    void everyMigrationVersionIsUsedOnce() throws IOException {
        Map<Integer, List<String>> byVersion;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            byVersion = files.map(file -> file.getFileName().toString())
                    .filter(name -> VERSION.matcher(name).matches())
                    .collect(Collectors.groupingBy(BookFileIndexMigrationTest::version));
        }

        assertThat(byVersion).allSatisfy((version, names) -> assertThat(names).as("V%d", version).hasSize(1));
    }

    private static Path migration(int version) throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(file -> file.getFileName().toString().startsWith("V" + version + "__"))
                    .findFirst().orElseThrow();
        }
    }

    /** The script without its comments, on one line. */
    private static String statements(Path script) throws IOException {
        return Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ");
    }

    private static int version(String fileName) {
        Matcher matcher = VERSION.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(fileName);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
