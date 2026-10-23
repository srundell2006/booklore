package org.booklore.service.book;

import org.booklore.model.dto.ComicMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.repository.*;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class BookCreatorService {

    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final MoodRepository moodRepository;
    private final TagRepository tagRepository;
    private final BookRepository bookRepository;
    private final BookMetadataRepository bookMetadataRepository;
    private final ComicMetadataRepository comicMetadataRepository;
    private final ComicCharacterRepository comicCharacterRepository;
    private final ComicTeamRepository comicTeamRepository;
    private final ComicLocationRepository comicLocationRepository;
    private final ComicCreatorRepository comicCreatorRepository;

    public BookCreatorService(AuthorRepository authorRepository,
                              CategoryRepository categoryRepository,
                              MoodRepository moodRepository,
                              TagRepository tagRepository,
                              BookRepository bookRepository,
                              BookMetadataRepository bookMetadataRepository,
                              ComicMetadataRepository comicMetadataRepository,
                              ComicCharacterRepository comicCharacterRepository,
                              ComicTeamRepository comicTeamRepository,
                              ComicLocationRepository comicLocationRepository,
                              ComicCreatorRepository comicCreatorRepository) {
        this.authorRepository = authorRepository;
        this.categoryRepository = categoryRepository;
        this.moodRepository = moodRepository;
        this.tagRepository = tagRepository;
        this.bookRepository = bookRepository;
        this.bookMetadataRepository = bookMetadataRepository;
        this.comicMetadataRepository = comicMetadataRepository;
        this.comicCharacterRepository = comicCharacterRepository;
        this.comicTeamRepository = comicTeamRepository;
        this.comicLocationRepository = comicLocationRepository;
        this.comicCreatorRepository = comicCreatorRepository;
    }

    /**
     * A book's entity, and whether it was created for the file or was already there.
     *
     * @param created  false when the book already stored for the file's path was reused
     * @param restored true when that book was deleted and the file brings it back
     */
    public record ShellBook(BookEntity entity, boolean created, boolean restored) {
        /** A new book, or a reused one that was not deleted. */
        public ShellBook(BookEntity entity, boolean created) {
            this(entity, created, false);
        }
    }

    /**
     * The book for a new file: the book already stored for exactly the file's path, with its file there given the new
     * hash and size and the book undeleted, or else a new book saved with one file and empty metadata. Reads nothing
     * from the file: its hash and size come from {@code facts}, computed once by whoever read the file.
     * <p>
     * The database finds stored paths through the columns' collation, which on MariaDB ignores case and possibly
     * accents, while the walk that found the file new compares paths exactly. A book found at a path that only
     * compares equal holds another file. Reusing it would overwrite that file's hash, undelete the book and give it
     * this file's metadata and cover, and the new file, still without a row of its own, would be new again on every
     * scan. So only a book with a file at exactly the path is reused; otherwise the file gets a book of its own.
     */
    public ShellBook createShellBook(LibraryFile libraryFile, BookFileType bookFileType, FileFacts facts) {
        String subPath = libraryFile.getFileSubPath();
        String fileName = libraryFile.getFileName();
        List<BookFileEntity> stored = bookRepository.findBookFormatsByLibraryIdAndLibraryPathIdAndFileSubPathAndFileName(
                libraryFile.getLibraryEntity().getId(), libraryFile.getLibraryPathEntity().getId(), subPath, fileName);
        Optional<BookFileEntity> atPath = stored.stream().filter(file -> file.isAt(subPath, fileName)).findFirst();

        long fileSizeKb = facts.sizeKb() != null ? facts.sizeKb() : 0L;
        if (atPath.isPresent()) {
            // The file at the path, which need not be the book's primary
            BookFileEntity file = atPath.get();
            BookEntity existingBook = file.getBook();
            boolean restored = Boolean.TRUE.equals(existingBook.getDeleted());
            log.warn("Book already exists for file: {} (book#{}{})", fileName, existingBook.getId(), restored ? ", restored" : "");
            file.setCurrentHash(facts.hash());
            file.setInitialHash(facts.hash());
            file.setFileSizeKb(fileSizeKb);
            file.setFolderBased(libraryFile.isFolderBased());
            existingBook.setDeleted(false);
            existingBook.setDeletedAt(null);
            return new ShellBook(existingBook, false, restored);
        }
        for (BookFileEntity other : stored) {
            log.warn("'{}' in '{}' gets a book of its own: book#{} has '{}' in '{}', which the database compares equal to it but is another path",
                    fileName, subPath, other.getBook().getId(), other.getFileName(), other.getFileSubPath());
        }

        BookEntity bookEntity = BookEntity.builder()
                .library(libraryFile.getLibraryEntity())
                .libraryPath(libraryFile.getLibraryPathEntity())
                .addedOn(Instant.now())
                .bookFiles(new ArrayList<>())
                .build();

        BookFileEntity bookFileEntity = BookFileEntity.builder()
                .book(bookEntity)
                .fileName(libraryFile.getFileName())
                .fileSubPath(libraryFile.getFileSubPath())
                .isBookFormat(true)
                .folderBased(libraryFile.isFolderBased())
                .bookType(bookFileType)
                .fileSizeKb(fileSizeKb)
                .initialHash(facts.hash())
                .currentHash(facts.hash())
                .addedOn(Instant.now())
                .build();
        bookEntity.getBookFiles().add(bookFileEntity);

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(bookEntity)
                .build();
        bookEntity.setMetadata(metadata);

        return new ShellBook(bookRepository.saveAndFlush(bookEntity), true);
    }

    public void addCategoriesToBook(Set<String> categories, BookEntity bookEntity) {
        if (categories == null || categories.isEmpty()) return;
        if (bookEntity.getMetadata().getCategories() == null) {
            bookEntity.getMetadata().setCategories(new HashSet<>());
        }
        categories.stream()
                .map(cat -> truncate(cat, 255))
                .map(truncated -> categoryRepository.findByName(truncated)
                        .orElseGet(() -> categoryRepository.save(CategoryEntity.builder().name(truncated).build())))
                .forEach(catEntity -> bookEntity.getMetadata().getCategories().add(catEntity));
    }

    public void addAuthorsToBook(Collection<String> authors, BookEntity bookEntity) {
        if (authors == null || authors.isEmpty()) return;
        if (bookEntity.getMetadata().getAuthors() == null) {
            bookEntity.getMetadata().setAuthors(new ArrayList<>());
        }
        authors.stream()
                .map(authorName -> truncate(authorName, 255))
                .map(authorName -> authorRepository.findByName(authorName)
                        .orElseGet(() -> authorRepository.save(AuthorEntity.builder().name(authorName).build())))
                .forEach(authorEntity -> bookEntity.getMetadata().getAuthors().add(authorEntity));
        bookEntity.getMetadata().updateSearchText(); // Manually trigger search text update since collection modification doesn't trigger @PreUpdate
    }

    public void addMoodsToBook(Set<String> moods, BookEntity bookEntity) {
        if (moods == null || moods.isEmpty()) return;
        if (bookEntity.getMetadata().getMoods() == null) {
            bookEntity.getMetadata().setMoods(new HashSet<>());
        }
        moods.stream()
                .map(mood -> truncate(mood, 255))
                .map(truncated -> moodRepository.findByName(truncated)
                        .orElseGet(() -> moodRepository.save(MoodEntity.builder().name(truncated).build())))
                .forEach(moodEntity -> bookEntity.getMetadata().getMoods().add(moodEntity));
    }

    public void addTagsToBook(Set<String> tags, BookEntity bookEntity) {
        if (tags == null || tags.isEmpty()) return;
        if (bookEntity.getMetadata().getTags() == null) {
            bookEntity.getMetadata().setTags(new HashSet<>());
        }
        tags.stream()
                .map(tag -> truncate(tag, 255))
                .map(truncated -> tagRepository.findByName(truncated)
                        .orElseGet(() -> tagRepository.save(TagEntity.builder().name(truncated).build())))
                .forEach(tagEntity -> bookEntity.getMetadata().getTags().add(tagEntity));
    }

    private String truncate(String input, int maxLength) {
        if (input == null)
            return null;
        return input.length() <= maxLength ? input : input.substring(0, maxLength);
    }

    /**
     * Saves the book, its metadata and its authors, and its comic metadata when the book has some, filling the comic
     * metadata's characters, teams, locations and creators from {@code comicDto}.
     *
     * @param comicDto the comic metadata read from the file, or null for none
     */
    public void saveConnections(BookEntity bookEntity, ComicMetadata comicDto) {
        if (bookEntity.getMetadata().getAuthors() != null && !bookEntity.getMetadata().getAuthors().isEmpty()) {
            authorRepository.saveAll(bookEntity.getMetadata().getAuthors());
        }
        bookRepository.save(bookEntity);
        bookMetadataRepository.save(bookEntity.getMetadata());

        // Save comic metadata if present
        ComicMetadataEntity comicMetadata = bookEntity.getMetadata().getComicMetadata();
        if (comicMetadata != null && comicMetadata.getBookId() != null) {
            comicMetadataRepository.save(comicMetadata);

            if (comicDto != null) {
                populateComicMetadataRelationships(comicMetadata, comicDto);
            }
        }
    }

    private void populateComicMetadataRelationships(ComicMetadataEntity comic, ComicMetadata dto) {
        // Add characters
        if (dto.getCharacters() != null && !dto.getCharacters().isEmpty()) {
            if (comic.getCharacters() == null) {
                comic.setCharacters(new HashSet<>());
            }
            dto.getCharacters().stream()
                    .map(name -> truncate(name, 255))
                    .map(name -> comicCharacterRepository.findByName(name)
                            .orElseGet(() -> comicCharacterRepository.save(ComicCharacterEntity.builder().name(name).build())))
                    .forEach(entity -> comic.getCharacters().add(entity));
        }

        // Add teams
        if (dto.getTeams() != null && !dto.getTeams().isEmpty()) {
            if (comic.getTeams() == null) {
                comic.setTeams(new HashSet<>());
            }
            dto.getTeams().stream()
                    .map(name -> truncate(name, 255))
                    .map(name -> comicTeamRepository.findByName(name)
                            .orElseGet(() -> comicTeamRepository.save(ComicTeamEntity.builder().name(name).build())))
                    .forEach(entity -> comic.getTeams().add(entity));
        }

        // Add locations
        if (dto.getLocations() != null && !dto.getLocations().isEmpty()) {
            if (comic.getLocations() == null) {
                comic.setLocations(new HashSet<>());
            }
            dto.getLocations().stream()
                    .map(name -> truncate(name, 255))
                    .map(name -> comicLocationRepository.findByName(name)
                            .orElseGet(() -> comicLocationRepository.save(ComicLocationEntity.builder().name(name).build())))
                    .forEach(entity -> comic.getLocations().add(entity));
        }

        // Add creators with roles
        if (comic.getCreatorMappings() == null) {
            comic.setCreatorMappings(new HashSet<>());
        }
        addCreatorsWithRole(comic, dto.getPencillers(), ComicCreatorRole.PENCILLER);
        addCreatorsWithRole(comic, dto.getInkers(), ComicCreatorRole.INKER);
        addCreatorsWithRole(comic, dto.getColorists(), ComicCreatorRole.COLORIST);
        addCreatorsWithRole(comic, dto.getLetterers(), ComicCreatorRole.LETTERER);
        addCreatorsWithRole(comic, dto.getCoverArtists(), ComicCreatorRole.COVER_ARTIST);
        addCreatorsWithRole(comic, dto.getEditors(), ComicCreatorRole.EDITOR);

        // Save the updated comic metadata with relationships
        comicMetadataRepository.save(comic);
    }

    private void addCreatorsWithRole(ComicMetadataEntity comic, Set<String> names, ComicCreatorRole role) {
        if (names == null || names.isEmpty()) {
            return;
        }
        for (String name : names) {
            String truncatedName = truncate(name, 255);
            ComicCreatorEntity creator = comicCreatorRepository.findByName(truncatedName)
                    .orElseGet(() -> comicCreatorRepository.save(ComicCreatorEntity.builder().name(truncatedName).build()));
            // A mapping has no key of its own to stop a second one for the same creator and role
            boolean mapped = comic.getCreatorMappings().stream().anyMatch(existing -> existing.getRole() == role
                    && existing.getCreator() != null && Objects.equals(existing.getCreator().getId(), creator.getId()));
            if (mapped) {
                continue;
            }

            ComicCreatorMappingEntity mapping = ComicCreatorMappingEntity.builder()
                    .comicMetadata(comic)
                    .creator(creator)
                    .role(role)
                    .build();
            comic.getCreatorMappings().add(mapping);
        }
    }
}
