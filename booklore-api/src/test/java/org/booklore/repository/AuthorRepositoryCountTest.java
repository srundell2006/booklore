package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.service.task.TaskCronService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The author count queries give the size of the author list queries they stand in for.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
@Import(AuthorRepositoryCountTest.TestConfig.class)
class AuthorRepositoryCountTest {

    @Autowired
    private AuthorRepository authorRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.boot.test.context.TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public org.flywaydb.core.Flyway flyway() {
            return mock(org.flywaydb.core.Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    private LibraryEntity fiction;
    private LibraryEntity science;
    private long authorsBefore;

    @BeforeEach
    void setUp() {
        authorsBefore = authorRepository.count();
        fiction = library("Fiction");
        science = library("Science");

        AuthorEntity king = author("Stephen King");
        AuthorEntity straub = author("Peter Straub");
        AuthorEntity sagan = author("Carl Sagan");
        author("Nobody Yet");

        // King has two books in Fiction, so the scoped count must not count him twice.
        book(fiction, king);
        book(fiction, king, straub);
        book(science, sagan, king);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void count_matchesTheAdminList() {
        assertThat(authorRepository.count())
                .isEqualTo(authorRepository.findAllWithBookCount().size())
                .isEqualTo(authorsBefore + 4);
    }

    @Test
    void countByLibraryIds_matchesTheScopedList() {
        assertScopedCount(Set.of(fiction.getId()), 2);
        assertScopedCount(Set.of(science.getId()), 2);
        assertScopedCount(Set.of(fiction.getId(), science.getId()), 3);
        assertScopedCount(Set.of(-1L), 0);
    }

    private void assertScopedCount(Set<Long> libraryIds, int expected) {
        assertThat(authorRepository.countByLibraryIds(libraryIds))
                .isEqualTo(authorRepository.findAllWithBookCountByLibraryIds(libraryIds).size())
                .isEqualTo(expected);
    }

    private LibraryEntity library(String name) {
        LibraryEntity library = LibraryEntity.builder().name(name).icon("book").watch(false).build();
        entityManager.persist(library);
        LibraryPathEntity path = LibraryPathEntity.builder().library(library).path("/test/" + name).build();
        entityManager.persist(path);
        library.setLibraryPaths(new ArrayList<>(List.of(path)));
        return library;
    }

    private AuthorEntity author(String name) {
        AuthorEntity author = AuthorEntity.builder().name(name).build();
        entityManager.persist(author);
        return author;
    }

    private void book(LibraryEntity library, AuthorEntity... authors) {
        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(library.getLibraryPaths().getFirst())
                .addedOn(Instant.now())
                .deleted(false)
                .build();
        entityManager.persist(book);
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title("Book " + book.getId())
                .authors(new ArrayList<>(List.of(authors)))
                .build();
        entityManager.persist(metadata);
    }
}
