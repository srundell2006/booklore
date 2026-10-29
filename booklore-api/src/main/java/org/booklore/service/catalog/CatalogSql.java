package org.booklore.service.catalog;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Text comparisons the catalog queries share, written so that MariaDB and H2 give the answer the UI's JavaScript
 * gives.
 */
final class CatalogSql {

    static final char LIKE_ESCAPE = '!';

    /** {@code String.prototype.trim}: a run of JavaScript whitespace at the start or at the very end. */
    private static final String JS_TRIM_PATTERN = "^[" + JsSemantics.WHITESPACE + "]+|[" + JsSemantics.WHITESPACE + "]+\\z";

    private CatalogSql() {
    }

    /**
     * {@code text.trim()}. SQL TRIM removes spaces only, so the JavaScript whitespace (tabs, line breaks, no-break and
     * ideographic spaces) goes by REGEXP_REPLACE, which H2 and MariaDB both have.
     */
    static Expression<String> jsTrim(CriteriaBuilder cb, Expression<String> text) {
        return cb.function("regexp_replace", String.class, text, cb.literal(JS_TRIM_PATTERN), cb.literal(""));
    }

    /** {@code text === value}, as a LIKE without wildcards: MariaDB's {@code =} ignores trailing spaces. */
    static Predicate matchesExactly(CriteriaBuilder cb, Expression<String> text, String value) {
        return cb.like(text, escapeLike(value), LIKE_ESCAPE);
    }

    /** The value as a LIKE pattern that matches only itself, to be used with {@link #LIKE_ESCAPE}. */
    static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }
}
