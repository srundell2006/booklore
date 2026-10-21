package org.booklore.service.fileprocessor.prepare;

import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.util.cover.CoverKind;
import org.booklore.util.cover.CropPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PrepareRequestTest {

    private static final CropPolicy CROP = new CropPolicy(true, false, 2.0, true);

    @Test
    void of_takesTheFileAsPlainValues() {
        LibraryFile libraryFile = libraryFile(LibraryOrganizationMode.BOOK_PER_FOLDER, false);

        PrepareRequest request = PrepareRequest.of(libraryFile, CROP);

        assertThat(request).isEqualTo(new PrepareRequest(Path.of("/library/Author/Book/book.epub"), "book.epub",
                BookFileType.EPUB, false, Path.of("/library/Author/Book"), CROP));
    }

    @Test
    void folderAudiobook_fallsBackToItsOwnFolder() {
        PrepareRequest request = PrepareRequest.of(libraryFile(LibraryOrganizationMode.BOOK_PER_FILE, true), CROP);

        assertThat(request.coverFallbackFolder()).isEqualTo(request.path());
    }

    @Test
    void bookPerFileOrNoLibrary_hasNoFallbackFolder() {
        LibraryFile withoutLibrary = libraryFile(LibraryOrganizationMode.BOOK_PER_FOLDER, false);
        withoutLibrary.setLibraryEntity(null);

        assertThat(PrepareRequest.of(libraryFile(LibraryOrganizationMode.BOOK_PER_FILE, false), CROP).coverFallbackFolder()).isNull();
        assertThat(PrepareRequest.coverFallbackFolder(withoutLibrary)).isNull();
    }

    @Test
    void attachment_takesTheFileAndTheCoverToMakeFromIt() {
        AttachmentRequest request = AttachmentRequest.of(libraryFile(LibraryOrganizationMode.BOOK_PER_FILE, true), CoverKind.AUDIOBOOK, CROP);

        assertThat(request).isEqualTo(new AttachmentRequest(Path.of("/library/Author/Book/book.epub"), "book.epub",
                BookFileType.EPUB, true, CoverKind.AUDIOBOOK, CROP));
    }

    @Test
    void unboundedContext_neverLimitsOrStops() throws Exception {
        PrepareContext ctx = PrepareContext.unbounded();

        ctx.checkpoint();
        ctx.registerProcess(null);
        ctx.reserveHeap(Long.MAX_VALUE).close();
        assertThat(ctx.<String>render(Long.MAX_VALUE, () -> "rendered")).isEqualTo("rendered");
        assertThat(PrepareContext.unbounded()).isSameAs(ctx);
    }

    private static LibraryFile libraryFile(LibraryOrganizationMode mode, boolean folderBased) {
        LibraryEntity library = new LibraryEntity();
        library.setOrganizationMode(mode);
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath("/library");
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath("Author/Book")
                .fileName("book.epub")
                .bookFileType(BookFileType.EPUB)
                .folderBased(folderBased)
                .build();
    }
}
