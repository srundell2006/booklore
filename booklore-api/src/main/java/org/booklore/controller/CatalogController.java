package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.catalog.CatalogCounts;
import org.booklore.model.dto.catalog.DashboardRow;
import org.booklore.model.dto.catalog.MagicShelfCounts;
import org.booklore.model.dto.catalog.SeriesBooks;
import org.booklore.service.catalog.CatalogCountService;
import org.booklore.service.catalog.DashboardRowKind;
import org.booklore.service.catalog.DashboardRowService;
import org.booklore.service.catalog.MagicShelfCountService;
import org.booklore.service.catalog.SeriesBooksService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Catalog", description = "Sidebar counts, dashboard rows and series books, computed on the server so the client need not load the whole book list")
@RequestMapping("/api/v1/catalog")
@RestController
@AllArgsConstructor
public class CatalogController {

    private final AuthenticationService authenticationService;
    private final CatalogCountService catalogCountService;
    private final MagicShelfCountService magicShelfCountService;
    private final DashboardRowService dashboardRowService;
    private final SeriesBooksService seriesBooksService;

    @Operation(summary = "Get sidebar counts",
            description = "Count the user's books overall, by library, by own shelf, unshelved, and their series. "
                    + "clientSide=true asks the client to compute them from the book list instead.")
    @ApiResponse(responseCode = "200", description = "Counts returned successfully")
    @GetMapping("/counts")
    public ResponseEntity<CatalogCounts> getCounts() {
        return ResponseEntity.ok(catalogCountService.getCounts(currentUser()));
    }

    @Operation(summary = "Get magic shelf counts",
            description = "Count the books on every magic shelf the user lists. A shelf whose rules the server cannot "
                    + "count exactly is UNSUPPORTED, with the rules as reasons.")
    @ApiResponse(responseCode = "200", description = "Counts returned successfully")
    @GetMapping("/magic-counts")
    public ResponseEntity<MagicShelfCounts> getMagicShelfCounts() {
        return ResponseEntity.ok(magicShelfCountService.getCounts(currentUser()));
    }

    @Operation(summary = "Get a dashboard row",
            description = "The books of one fixed dashboard row: continue-reading, continue-listening, recently-added or random.")
    @ApiResponse(responseCode = "200", description = "Row returned successfully")
    @ApiResponse(responseCode = "404", description = "No such row")
    @GetMapping("/rows/{kind}")
    public ResponseEntity<DashboardRow> getRow(
            @Parameter(description = "The row") @PathVariable String kind,
            @Parameter(description = "How many books, 1 to 50 (default 20)") @RequestParam(required = false) Integer size) {
        DashboardRowKind rowKind = DashboardRowKind.fromPath(kind)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No dashboard row " + kind));
        return ResponseEntity.ok(new DashboardRow(dashboardRowService.getRow(currentUser(), rowKind, clampSize(size))));
    }

    static int clampSize(Integer size) {
        if (size == null) return DashboardRowService.DEFAULT_SIZE;
        return Math.clamp(size, 1, DashboardRowService.MAX_SIZE);
    }

    @Operation(summary = "Get the books of a series",
            description = "The books whose series name, trimmed and lower-cased, equals the given name trimmed and "
                    + "lower-cased, by series number with unnumbered books last. total counts every match, also those "
                    + "past the limit.")
    @ApiResponse(responseCode = "200", description = "Books returned successfully")
    @ApiResponse(responseCode = "400", description = "No series name")
    @GetMapping("/series/books")
    public ResponseEntity<SeriesBooks> getSeriesBooks(
            @Parameter(description = "The series name") @RequestParam(required = false) String name,
            @Parameter(description = "How many books, 1 to 5000 (default 1000)") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(seriesBooksService.getSeriesBooks(currentUser(), name, clampLimit(limit)));
    }

    static int clampLimit(Integer limit) {
        if (limit == null) return SeriesBooksService.DEFAULT_LIMIT;
        return Math.clamp(limit, 1, SeriesBooksService.MAX_LIMIT);
    }

    private BookLoreUser currentUser() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null) {
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Not authenticated");
        }
        return user;
    }
}
