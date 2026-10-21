package org.booklore.service.metadata.sidecar;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.dto.settings.SidecarSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SidecarMetadataWriterTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private SidecarMetadataMapper mapper;

    @Mock
    private FileService fileService;

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private SidecarMetadataWriter sidecarMetadataWriter;

    @BeforeEach
    void setUp() {
        lenient().when(appProperties.isLocalStorage()).thenReturn(true);
    }

    @Test
    void writeSidecarMetadata_networkStorage_skipsWrite() {
        when(appProperties.isLocalStorage()).thenReturn(false);

        sidecarMetadataWriter.writeSidecarMetadata(new BookEntity());

        verify(appSettingService, never()).getAppSettings();
    }

    @Test
    void writeSidecarMetadata_localStorage_proceedsNormally() {
        sidecarMetadataWriter.writeSidecarMetadata(null);

        verify(appProperties).isLocalStorage();
    }

    @Test
    void prepare_buildsTheSidecarWithoutTouchingTheFilesystem() {
        SidecarMetadataWriter writer = writerWithRealMapper(true);
        // Neither the book nor its folder exists: prepare must not look
        BookEntity book = book(tempDir.resolve("missing"), "Book One.epub");
        when(fileService.getCoverFile(7L)).thenReturn(tempDir.resolve("images/7/cover.jpg").toString());

        SidecarMetadataWriter.SidecarJob job = writer.prepare(book).orElseThrow();

        assertThat(job.bookId()).isEqualTo(7L);
        assertThat(job.bookPath()).isEqualTo(tempDir.resolve("missing/Book One.epub"));
        assertThat(job.sidecarPath()).isEqualTo(tempDir.resolve("missing/Book One.metadata.json"));
        assertThat(job.coverSource()).isEqualTo(tempDir.resolve("images/7/cover.jpg"));
        assertThat(job.coverTarget()).isEqualTo(tempDir.resolve("missing/Book One.cover.jpg"));
        assertThat(job.json()).contains("\"title\": \"The Title\"").contains("\"path\": \"Book One.cover.jpg\"");
        assertThat(tempDir.resolve("missing")).doesNotExist();
    }

    @Test
    void prepare_withoutCoverFile_leavesTheCoverOut() {
        SidecarMetadataWriter writer = writerWithRealMapper(false);

        SidecarMetadataWriter.SidecarJob job = writer.prepare(book(tempDir, "book.epub")).orElseThrow();

        assertThat(job.coverSource()).isNull();
        assertThat(job.coverTarget()).isNull();
        assertThat(job.json()).doesNotContain("\"cover\"");
        verifyNoInteractions(fileService);
    }

    @Test
    void prepare_writesNothingWhenDisabledOrNotLocalOrWithoutABook() {
        SidecarMetadataWriter writer = writerWithRealMapper(true);
        assertThat(writer.prepare(null)).isEmpty();
        assertThat(writer.prepare(new BookEntity())).isEmpty();

        BookEntity withoutPath = book(tempDir, "book.epub");
        withoutPath.setLibraryPath(null);
        assertThat(writer.prepare(withoutPath)).isEmpty();

        when(appSettingService.getAppSettings()).thenReturn(settings(false, true));
        assertThat(writer.prepare(book(tempDir, "book.epub"))).isEmpty();

        when(appProperties.isLocalStorage()).thenReturn(false);
        assertThat(writer.prepare(book(tempDir, "book.epub"))).isEmpty();
    }

    @Test
    void execute_writesTheSidecarAndCopiesTheCover() throws IOException {
        Path book = Files.writeString(tempDir.resolve("book.epub"), "epub");
        Path cover = Files.write(Files.createDirectories(tempDir.resolve("images/7")).resolve("cover.jpg"), new byte[]{1, 2, 3});
        var job = new SidecarMetadataWriter.SidecarJob(7L, book, tempDir.resolve("book.metadata.json"), "{ }", cover, tempDir.resolve("book.cover.jpg"));

        sidecarMetadataWriter.execute(job);

        assertThat(tempDir.resolve("book.metadata.json")).hasContent("{ }");
        assertThat(tempDir.resolve("book.cover.jpg")).hasBinaryContent(new byte[]{1, 2, 3});
    }

    @Test
    void execute_withoutAStoredCover_writesOnlyTheSidecar() throws IOException {
        Path book = Files.writeString(tempDir.resolve("book.epub"), "epub");
        var job = new SidecarMetadataWriter.SidecarJob(7L, book, tempDir.resolve("book.metadata.json"), "{ }",
                tempDir.resolve("images/7/cover.jpg"), tempDir.resolve("book.cover.jpg"));

        sidecarMetadataWriter.execute(job);

        assertThat(tempDir.resolve("book.metadata.json")).hasContent("{ }");
        assertThat(tempDir.resolve("book.cover.jpg")).doesNotExist();
    }

    @Test
    void execute_bookFileGone_writesNothing() {
        var job = new SidecarMetadataWriter.SidecarJob(7L, tempDir.resolve("gone.epub"), tempDir.resolve("gone.metadata.json"), "{ }", null, null);

        sidecarMetadataWriter.execute(job);

        assertThat(tempDir.resolve("gone.metadata.json")).doesNotExist();
    }

    @Test
    void writeSidecarMetadata_writesWhatPrepareBuilds() throws IOException {
        SidecarMetadataWriter writer = writerWithRealMapper(false);
        Files.writeString(tempDir.resolve("book.epub"), "epub");
        BookEntity book = book(tempDir, "book.epub");

        writer.writeSidecarMetadata(book);

        String written = Files.readString(tempDir.resolve("book.metadata.json"));
        String prepared = writer.prepare(book).orElseThrow().json();
        // Equal but for the time each was generated at
        assertThat(written.replaceAll("\"generatedAt\": \"[^\"]*\"", ""))
                .isEqualTo(prepared.replaceAll("\"generatedAt\": \"[^\"]*\"", ""));
    }

    @TempDir
    Path tempDir;

    private SidecarMetadataWriter writerWithRealMapper(boolean includeCoverFile) {
        lenient().when(appSettingService.getAppSettings()).thenReturn(settings(true, includeCoverFile));
        return new SidecarMetadataWriter(appProperties, new SidecarMetadataMapper(), fileService, appSettingService);
    }

    private static AppSettings settings(boolean enabled, boolean includeCoverFile) {
        return AppSettings.builder()
                .metadataPersistenceSettings(MetadataPersistenceSettings.builder()
                        .sidecarSettings(SidecarSettings.builder().enabled(enabled).writeOnScan(true).includeCoverFile(includeCoverFile).build())
                        .build())
                .build();
    }

    private static BookEntity book(Path folder, String fileName) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(folder.toString());
        BookEntity book = new BookEntity();
        book.setId(7L);
        book.setLibraryPath(libraryPath);
        BookFileEntity file = new BookFileEntity();
        file.setFileName(fileName);
        file.setFileSubPath("");
        file.setBookFormat(true);
        book.getBookFiles().add(file);
        book.setMetadata(BookMetadataEntity.builder().title("The Title").build());
        return book;
    }
}
