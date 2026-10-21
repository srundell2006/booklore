package org.booklore.service.metadata.sidecar;

import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.sidecar.SidecarMetadata;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.metadata.BookMetadataUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SidecarServiceTest {

    @Mock private BookRepository bookRepository;
    @Mock private LibraryRepository libraryRepository;
    @Mock private SidecarMetadataReader sidecarReader;
    @Mock private SidecarMetadataWriter sidecarWriter;
    @Mock private SidecarMetadataMapper sidecarMapper;
    @Mock private BookMetadataUpdater bookMetadataUpdater;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private SidecarService sidecarService;

    private LibraryEntity library;

    @BeforeEach
    void setUp() {
        library = new LibraryEntity();
        library.setId(1L);
        library.setName("Books");
        when(libraryRepository.findById(1L)).thenReturn(Optional.of(library));
    }

    @Test
    void bulkImport_commitsEachBookBeforeImportingTheNext() {
        BookEntity book1 = book(1L, "one.epub");
        BookEntity book2 = book(2L, "two.epub");
        when(bookRepository.findBookIdsByLibraryId(1L)).thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book1));
        when(bookRepository.findByIdWithBookFiles(2L)).thenReturn(Optional.of(book2));
        SidecarMetadata sidecar = SidecarMetadata.builder().build();
        when(sidecarReader.readSidecarMetadata(any(Path.class))).thenReturn(Optional.of(sidecar));
        when(sidecarMapper.toBookMetadata(sidecar)).thenReturn(new BookMetadata());
        TransactionStatus status1 = mock(TransactionStatus.class);
        TransactionStatus status2 = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status1, status2);

        int imported = sidecarService.bulkImport(1L);

        assertThat(imported).isEqualTo(2);
        // Saving a book's metadata may move its files, and their new paths are committed before the next book
        InOrder inOrder = inOrder(transactionManager, bookMetadataUpdater);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(bookMetadataUpdater).setBookMetadata(argThat((MetadataUpdateContext context) -> context.getBookEntity() == book1));
        inOrder.verify(transactionManager).commit(status1);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(bookMetadataUpdater).setBookMetadata(argThat((MetadataUpdateContext context) -> context.getBookEntity() == book2));
        inOrder.verify(transactionManager).commit(status2);
    }

    @Test
    void bulkImport_rollsBackOnlyTheBookThatFailed() {
        BookEntity book1 = book(1L, "one.epub");
        BookEntity book2 = book(2L, "two.epub");
        when(bookRepository.findBookIdsByLibraryId(1L)).thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book1));
        when(bookRepository.findByIdWithBookFiles(2L)).thenReturn(Optional.of(book2));
        SidecarMetadata sidecar = SidecarMetadata.builder().build();
        when(sidecarReader.readSidecarMetadata(any(Path.class))).thenReturn(Optional.of(sidecar));
        when(sidecarMapper.toBookMetadata(sidecar)).thenReturn(new BookMetadata());
        TransactionStatus status1 = mock(TransactionStatus.class);
        TransactionStatus status2 = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status1, status2);
        doThrow(new RuntimeException("Update failed")).doNothing()
                .when(bookMetadataUpdater).setBookMetadata(any(MetadataUpdateContext.class));

        int imported = sidecarService.bulkImport(1L);

        assertThat(imported).isEqualTo(1);
        verify(transactionManager).rollback(status1);
        verify(transactionManager, never()).commit(status1);
        verify(transactionManager).commit(status2);
    }

    @Test
    void bulkImport_skipsBooksWithoutSidecar() {
        BookEntity book1 = book(1L, "one.epub");
        when(bookRepository.findBookIdsByLibraryId(1L)).thenReturn(new LinkedHashSet<>(List.of(1L)));
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book1));
        when(sidecarReader.readSidecarMetadata(any(Path.class))).thenReturn(Optional.empty());

        int imported = sidecarService.bulkImport(1L);

        assertThat(imported).isZero();
        verifyNoInteractions(bookMetadataUpdater);
    }

    private BookEntity book(Long id, String fileName) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath("/library");
        BookEntity book = new BookEntity();
        book.setId(id);
        book.setLibrary(library);
        book.setLibraryPath(libraryPath);
        BookFileEntity file = new BookFileEntity();
        file.setBook(book);
        file.setFileName(fileName);
        file.setFileSubPath("");
        file.setBookType(BookFileType.EPUB);
        file.setBookFormat(true);
        book.setBookFiles(new ArrayList<>(List.of(file)));
        return book;
    }
}
