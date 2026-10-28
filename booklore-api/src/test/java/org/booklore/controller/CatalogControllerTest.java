package org.booklore.controller;

import jakarta.servlet.Filter;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.MagicShelfEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ContentRestrictionType;
import org.booklore.service.catalog.CatalogDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The catalog endpoints as the browser sees them: the JSON the application's mapper writes, the size bounds, and
 * that nothing is answered without a signed-in user.
 */
class CatalogControllerTest extends CatalogDatabaseTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Filter springSecurityFilterChain;

    private MockMvc mockMvc;
    private LibraryEntity library;
    private BookLoreUserEntity adminUser;
    private ShelfEntity shelf;
    private MagicShelfEntity okShelf;
    private MagicShelfEntity unsupportedShelf;
    private MagicShelfEntity invalidShelf;

    @BeforeEach
    void fixture() {
        // The security chain is checked on its own below; here the user is put in the context directly
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        library = library("fiction", BookFileType.EPUB);
        adminUser = user("admin");
        BookEntity first = null;
        for (int i = 0; i < 55; i++) {
            BookEntity book = book(library, "Book " + i, NOW.minus(i, ChronoUnit.MINUTES));
            if (first == null) first = book;
        }
        seriesName(first, "Saga");
        shelf = shelf(adminUser, "Picks", first);
        okShelf = magicShelf(adminUser, "Book 1x", "{\"type\":\"group\",\"join\":\"and\",\"rules\":["
                + "{\"field\":\"title\",\"operator\":\"starts_with\",\"value\":\"book 1\"}]}", false);
        unsupportedShelf = magicShelf(adminUser, "Series", "{\"type\":\"group\",\"join\":\"and\",\"rules\":["
                + "{\"field\":\"seriesStatus\",\"operator\":\"equals\",\"value\":\"reading\"}]}", false);
        invalidShelf = magicShelf(adminUser, "Broken", "{", false);
        em.flush();
        em.clear();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(BookLoreUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    void countsShape() throws Exception {
        signIn(viewer(adminUser, true));

        mockMvc.perform(get("/api/v1/catalog/counts"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"clientSide\":false,\"allBooks\":55,\"series\":1,\"unshelved\":54,"
                        + "\"libraries\":{\"" + library.getId() + "\":55},\"shelves\":{\"" + shelf.getId() + "\":1}}", JsonCompareMode.STRICT));
    }

    @Test
    void restrictedCountsAreOnlyTheFlag() throws Exception {
        BookLoreUserEntity restricted = user("restricted");
        restrict(restricted, ContentRestrictionType.TAG, "hidden");
        signIn(viewer(restricted, false, library));

        mockMvc.perform(get("/api/v1/catalog/counts"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"clientSide\":true}"));
    }

    @Test
    void magicCountsShape() throws Exception {
        signIn(viewer(adminUser, true));

        // Book 1 and Book 10 to Book 19
        mockMvc.perform(get("/api/v1/catalog/magic-counts"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"magicShelves\":{"
                        + "\"" + okShelf.getId() + "\":{\"status\":\"OK\",\"count\":11},"
                        + "\"" + unsupportedShelf.getId() + "\":{\"status\":\"UNSUPPORTED\",\"count\":null,\"reasons\":[\"seriesStatus:equals\"]},"
                        + "\"" + invalidShelf.getId() + "\":{\"status\":\"INVALID\",\"count\":0}}}", JsonCompareMode.STRICT))
                // count is written even when null; reasons only for UNSUPPORTED
                .andExpect(jsonPath("$.magicShelves." + unsupportedShelf.getId()).value(hasKey("count")))
                .andExpect(jsonPath("$.magicShelves." + okShelf.getId()).value(not(hasKey("reasons"))))
                .andExpect(jsonPath("$.magicShelves." + invalidShelf.getId()).value(not(hasKey("reasons"))));
    }

    @Test
    void rowShapeIsTheBookList() throws Exception {
        signIn(viewer(adminUser, true));

        mockMvc.perform(get("/api/v1/catalog/rows/recently-added").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.books", hasSize(2)))
                .andExpect(jsonPath("$.books[0].metadata.title").value("Book 0"))
                .andExpect(jsonPath("$.books[0].libraryId").value(library.getId()))
                .andExpect(jsonPath("$.books[0].addedOn").value("2026-10-28T12:00:00Z"))
                .andExpect(jsonPath("$.books[0].shelves[0].id").value(shelf.getId()))
                .andExpect(jsonPath("$.books[1].metadata.title").value("Book 1"));

        for (String kind : List.of("continue-reading", "continue-listening", "random")) {
            mockMvc.perform(get("/api/v1/catalog/rows/" + kind))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.books").isArray());
        }
    }

    @Test
    void sizeIsClampedToOneToFifty() throws Exception {
        signIn(viewer(adminUser, true));

        expectRowSize(null, 20);
        expectRowSize("7", 7);
        expectRowSize("0", 1);
        expectRowSize("-3", 1);
        expectRowSize("51", 50);
        expectRowSize("100000", 50);
    }

    private void expectRowSize(String size, int expected) throws Exception {
        var request = get("/api/v1/catalog/rows/recently-added");
        if (size != null) request.param("size", size);
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.books", hasSize(expected)));
    }

    @Test
    void anUnknownRowIsNotFound() throws Exception {
        signIn(viewer(adminUser, true));

        mockMvc.perform(get("/api/v1/catalog/rows/magic-shelf"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/catalog/counts", "/api/v1/catalog/magic-counts", "/api/v1/catalog/rows/random"})
    void withoutAUserNothingIsAnswered(String path) throws Exception {
        MockMvc secured = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();

        secured.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        // Past the chain, the controller still refuses a request without a user
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());
    }
}
