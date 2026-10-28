package org.booklore.service.catalog;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.repository.UserContentRestrictionRepository;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CatalogViewerResolver {

    private final UserContentRestrictionRepository restrictionRepository;

    /** Admins are never restricted, as in the list, so only a non-admin costs the restriction lookup. */
    public CatalogViewer resolve(BookLoreUser user) {
        boolean admin = user.getPermissions() != null && user.getPermissions().isAdmin();
        if (admin) {
            return new CatalogViewer(user.getId(), true, Set.of(), false);
        }
        Set<Long> libraryIds = user.getAssignedLibraries() == null ? Set.of()
                : user.getAssignedLibraries().stream().map(Library::getId).collect(Collectors.toSet());
        return new CatalogViewer(user.getId(), false, libraryIds, restrictionRepository.existsByUserId(user.getId()));
    }
}
