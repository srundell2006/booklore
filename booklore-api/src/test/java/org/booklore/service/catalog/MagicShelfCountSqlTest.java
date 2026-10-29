package org.booklore.service.catalog;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.catalog.MagicShelfCountCompiler.Compiled;
import org.booklore.service.catalog.MagicShelfCountCompiler.RuleContext;
import org.booklore.service.catalog.MagicShelfCountCompiler.Supported;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What H2, which compares text exactly, cannot show of MariaDB: under its PAD SPACE collations {@code =} and
 * {@code IN} ignore trailing spaces, so {@code 'Child Love '} would equal {@code 'child love'} where the UI's
 * {@code ===} and {@code includes} do not. LIKE does not pad, so text equality has to be a LIKE without wildcards.
 */
class MagicShelfCountSqlTest extends CatalogDatabaseTest {

    private static final Instant ADDED = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private MagicShelfCountCompiler compiler;
    @Autowired
    private MagicShelfCountService countService;

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"Child Love\"}",
            "{\"field\":\"title\",\"operator\":\"not_equals\",\"value\":\"Child Love\"}",
            "{\"field\":\"isbn13\",\"operator\":\"includes_any\",\"value\":[\"\"]}",
            "{\"field\":\"title\",\"operator\":\"excludes_all\",\"value\":[\"Child Love\",\"About Time\"]}",
            "{\"field\":\"title\",\"operator\":\"includes_all\",\"value\":[\"anthem\"]}",
            "{\"field\":\"authors\",\"operator\":\"equals\",\"value\":\"Tom Clancy\"}",
            "{\"field\":\"authors\",\"operator\":\"not_equals\",\"value\":\"Tom Clancy\"}",
            "{\"field\":\"authors\",\"operator\":\"includes_any\",\"value\":[\"Tom Clancy\",\"Dan Brown\"]}",
            "{\"field\":\"tags\",\"operator\":\"excludes_all\",\"value\":[\"File Not Found\"]}",
            "{\"field\":\"tags\",\"operator\":\"includes_all\",\"value\":[\"File Not Found\",\"Comics\"]}"
    })
    void textEqualityIsALikeWithoutWildcards(String rule) {
        List<String> operators = operatorsOnLowerCasedText(hql(rule));

        assertThat(operators).as(rule).isNotEmpty()
                .allMatch(operator -> operator.equals("like") || operator.equals("not like"));
    }

    @Test
    void likeMetacharactersInAnEqualityMatchThemselves() {
        LibraryEntity library = library("shop", BookFileType.EPUB);
        BookLoreUserEntity reader = user("reader");
        BookEntity sale = book(library, "50%_Off!", ADDED);
        tag(sale, "to_do!");
        BookEntity lookalike = book(library, "50% off!", ADDED);
        tag(lookalike, "toxdo!");
        BookEntity longer = book(library, "50%_off!!", ADDED);
        tag(longer, "to_do!!");
        CatalogViewer viewer = new CatalogViewer(reader.getId(), false, Set.of(library.getId()), false);

        for (String rule : List.of(
                "{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"50%_off!\"}",
                "{\"field\":\"title\",\"operator\":\"includes_any\",\"value\":[\"50%_off!\"]}",
                "{\"field\":\"tags\",\"operator\":\"equals\",\"value\":\"TO_DO!\"}",
                "{\"field\":\"tags\",\"operator\":\"includes_all\",\"value\":[\"to_do!\"]}")) {
            assertThat(countService.matchingIds(viewer, supported(rule), ADDED)).as(rule).containsExactly(sale.getId());
        }
        assertThat(countService.matchingIds(viewer, supported(
                "{\"field\":\"title\",\"operator\":\"excludes_all\",\"value\":[\"50%_off!\"]}"), ADDED))
                .containsExactly(lookalike.getId(), longer.getId());
    }

    // ------------------------------------------------------------------ helpers

    private Supported supported(String rule) {
        Compiled compiled = compiler.compile("{\"type\":\"group\",\"join\":\"and\",\"rules\":[" + rule + "]}");
        assertThat(compiled).as(rule).isInstanceOf(Supported.class);
        return (Supported) compiled;
    }

    /** The count query of a shelf with this one rule, as HQL. */
    private String hql(String rule) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<BookEntity> book = query.from(BookEntity.class);
        RuleContext ctx = new RuleContext(cb, query, book, 1L, ADDED);
        query.select(cb.count(book)).where(compiler.toPredicate(supported(rule), ctx));
        return ((SqmSelectStatement<?>) query).toHqlString();
    }

    /** The operator after each {@code lower(...)}, such as {@code =}, {@code in} or {@code not like}. */
    static List<String> operatorsOnLowerCasedText(String hql) {
        List<String> operators = new ArrayList<>();
        String lowerCased = hql.toLowerCase(Locale.ROOT);
        for (int at = lowerCased.indexOf("lower("); at >= 0; at = lowerCased.indexOf("lower(", at + 1)) {
            int depth = 0;
            int end = at + "lower".length();
            do {
                char ch = lowerCased.charAt(end++);
                if (ch == '(') depth++;
                if (ch == ')') depth--;
            } while (depth > 0);
            while (Character.isWhitespace(lowerCased.charAt(end))) end++;
            int start = end;
            if (Character.isLetter(lowerCased.charAt(end))) {
                while (Character.isLetter(lowerCased.charAt(end))) end++;
                if (lowerCased.startsWith("not ", start)) {
                    end++;
                    while (Character.isLetter(lowerCased.charAt(end))) end++;
                }
            } else {
                while ("=<>!".indexOf(lowerCased.charAt(end)) >= 0) end++;
            }
            operators.add(lowerCased.substring(start, end));
        }
        return operators;
    }
}
