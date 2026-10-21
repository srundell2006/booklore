package org.booklore.service.library.importer;

import jakarta.persistence.Entity;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.service.fileprocessor.prepare.AttachmentRequest;
import org.booklore.service.fileprocessor.prepare.ExtractedContent;
import org.booklore.service.fileprocessor.prepare.FileFacts;
import org.booklore.service.fileprocessor.prepare.FolderSnapshot;
import org.booklore.service.fileprocessor.prepare.PersistedBook;
import org.booklore.service.fileprocessor.prepare.PrepareRequest;
import org.booklore.service.fileprocessor.prepare.PreparedAttachment;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;
import org.booklore.service.metadata.extractor.FileMetadataExtractor;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.cover.CropPolicy;
import org.booklore.util.cover.RenderedCover;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What import threads are handed and hand back must hold no entity, LibraryFile or repository, anywhere in its fields:
 * those belong to the scan's thread and its session, and an import thread touching one would load from or write to
 * the database outside any transaction, at the same time as the scan's thread.
 */
class WorkerTypesAreEntityFreeTest {

    private static final List<Class<?>> WORKER_TYPES = List.of(
            PrepareRequest.class, AttachmentRequest.class, CropPolicy.class,
            FileFacts.class, FolderSnapshot.class, ExtractedContent.class, RenderedCover.class,
            PreparedBookFile.class, PreparedAttachment.class, PersistedBook.class,
            SidecarMetadataWriter.SidecarJob.class, FileMetadataExtractor.MetadataAndCover.class, BookMetadata.class);

    @Test
    void whatImportThreadsWorkWithHoldsNoEntityLibraryFileOrRepository() {
        assertThat(forbiddenTypesReachableFrom(WORKER_TYPES)).isEmpty();
    }

    record Leaky(Map<String, List<BookEntity>> books, LibraryFile[] files) {
    }

    @Test
    void theCheckFindsForbiddenTypesInsideGenericArgumentsAndArrays() {
        assertThat(forbiddenTypesReachableFrom(List.of(Leaky.class)))
                .containsExactlyInAnyOrder("Leaky.books -> BookEntity", "Leaky.files -> LibraryFile");
    }

    /** Each forbidden type found, as the field path that reaches it. */
    private static List<String> forbiddenTypesReachableFrom(List<Class<?>> roots) {
        List<String> found = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Reach> pending = new ArrayDeque<>();
        roots.forEach(root -> pending.add(new Reach(root, root.getSimpleName())));
        while (!pending.isEmpty()) {
            Reach reach = pending.removeFirst();
            switch (reach.type()) {
                case ParameterizedType p -> {
                    pending.add(new Reach(p.getRawType(), reach.path()));
                    for (Type argument : p.getActualTypeArguments()) {
                        pending.add(new Reach(argument, reach.path()));
                    }
                }
                case WildcardType w -> {
                    for (Type bound : w.getUpperBounds()) pending.add(new Reach(bound, reach.path()));
                    for (Type bound : w.getLowerBounds()) pending.add(new Reach(bound, reach.path()));
                }
                case GenericArrayType a -> pending.add(new Reach(a.getGenericComponentType(), reach.path()));
                case TypeVariable<?> v -> {
                    for (Type bound : v.getBounds()) pending.add(new Reach(bound, reach.path()));
                }
                case Class<?> c when c.isArray() -> pending.add(new Reach(c.getComponentType(), reach.path()));
                case Class<?> c -> {
                    if (isForbidden(c)) {
                        found.add(reach.path() + " -> " + c.getSimpleName());
                    } else if (!c.isPrimitive() && !c.getName().startsWith("java.") && seen.add(c)) {
                        for (Class<?> t = c; t != null && t != Object.class; t = t.getSuperclass()) {
                            for (Field field : t.getDeclaredFields()) {
                                if (!Modifier.isStatic(field.getModifiers())) {
                                    pending.add(new Reach(field.getGenericType(), c.getSimpleName() + "." + field.getName()));
                                }
                            }
                        }
                    }
                }
                default -> throw new IllegalStateException("Unexpected type " + reach.type());
            }
        }
        return found;
    }

    private static boolean isForbidden(Class<?> type) {
        return type.isAnnotationPresent(Entity.class)
                || LibraryFile.class.isAssignableFrom(type)
                || Repository.class.isAssignableFrom(type);
    }

    private record Reach(Type type, String path) {
    }
}
