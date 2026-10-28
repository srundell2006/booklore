package org.booklore.service.catalog;

import jakarta.persistence.criteria.*;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.service.catalog.JsSemantics.Kind;
import org.booklore.service.catalog.JsSemantics.Norm;
import org.booklore.service.catalog.JsSemantics.Unit;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static org.booklore.service.catalog.JsSemantics.*;

/**
 * Compiles a magic shelf's {@code filterJson} into a JPA Criteria predicate that selects exactly the books the UI's
 * {@code BookRuleEvaluatorService.evaluateGroup} ({@code book-rule-evaluator.service.ts}) matches in the list
 * {@code GET /api/v1/books} streams. The UI is the oracle; every branch below follows a branch there, and the
 * shared fixture in {@code src/test/resources/catalog-parity} holds the cases both sides are checked against.
 *
 * <p>Only a whitelist of (field, operator) pairs is compiled. Anything else, an unexpected value type, a rule value
 * whose JavaScript conversion depends on the browser, or a structure the UI would throw on, makes the whole shelf
 * {@link Unsupported} with {@code "field:operator"} reasons. Nothing fails open.
 *
 * <p>Every rule predicate is two-valued (never SQL NULL), so NOT, AND and OR combine like the JavaScript booleans.
 * List fields are correlated EXISTS subqueries and the metadata join is one-to-one, so a count needs no DISTINCT.
 *
 * <p>Differences accepted on MariaDB, where H2 compares exactly:
 * <ul>
 *     <li>Text is compared through the column collation, which also folds accents and pads trailing spaces in
 *     {@code =} and {@code IN} (not in LIKE). Both sides are lower-cased, the value in Java with {@link Locale#ROOT};
 *     the database's LOWER can differ from JavaScript's toLowerCase for rare characters.</li>
 *     <li>FLOAT columns ({@code book.metadata_match_score} and the ratings) are compared as stored, against rule
 *     numbers that a float represents exactly (other numbers are unsupported). The UI holds the value MariaDB's text
 *     protocol returned for the float; where that text is rounded (to six significant digits), a stored value within
 *     that rounding of the rule number can compare differently.</li>
 * </ul>
 *
 * <p>{@code fileType} is the primary file's type, which depends on each library's format priority. It is supported
 * only as rules directly in an AND root: {@link Supported#fileTypeTests()} are then checked in Java on the SQL
 * matches, and the SQL holds the rest of the root.
 */
@Component
public class MagicShelfCountCompiler {

    private static final char LIKE_ESCAPE = '!';
    private static final int MAX_DEPTH = 64;

    private static final SqlRule TRUE = ctx -> ctx.cb().conjunction();
    private static final SqlRule FALSE = ctx -> ctx.cb().disjunction();

    private static final Set<String> TEXT_FIELDS = Set.of("title", "publisher", "seriesName", "language", "isbn13", "isbn10", "narrator");
    private static final Set<String> LIST_FIELDS = Set.of("authors", "categories", "moods", "tags");
    private static final Set<String> RATING_FIELDS = Set.of("amazonRating", "goodreadsRating", "hardcoverRating", "ranobedbRating");
    private static final Set<String> COUNT_FIELDS = Set.of("amazonReviewCount", "goodreadsReviewCount", "hardcoverReviewCount", "pageCount");

    // JSON.parse rejects trailing content, which Jackson otherwise ignores
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    // ------------------------------------------------------------------ results

    public sealed interface Compiled permits Supported, Unsupported, Invalid {
    }

    /**
     * @param sql             the rule as SQL, without the fileType rules
     * @param fileTypeTests   fileType rules of the AND root, checked in Java against the primary file's type
     * @param fileTypeReasons their {@code field:operator}, for when the post-filter cannot run
     */
    public record Supported(Node sql, List<FileTypeTest> fileTypeTests, List<String> fileTypeReasons) implements Compiled {
        public boolean needsFileTypeFilter() {
            return !fileTypeTests.isEmpty();
        }
    }

    public record Unsupported(List<String> reasons) implements Compiled {
    }

    /** filterJson does not parse: {@code JSON.parse} throws, and the UI counts 0. */
    public record Invalid() implements Compiled {
    }

    public sealed interface Node permits Group, Leaf {
    }

    /** {@code join === 'and'} exactly means AND; anything else OR. No children: TRUE for AND, FALSE for OR. */
    public record Group(boolean and, List<Node> children) implements Node {
    }

    public record Leaf(SqlRule rule) implements Node {
    }

    @FunctionalInterface
    public interface SqlRule {
        Predicate build(RuleContext ctx);
    }

    /** One rule on the lower-cased {@code bookType} of the primary file, or null when there is none. */
    @FunctionalInterface
    public interface FileTypeTest {
        boolean test(String primaryBookType);
    }

    /** What a predicate is built against: the query, its book, the viewer whose progress counts, and the time. */
    public static final class RuleContext {
        private final CriteriaBuilder cb;
        private final AbstractQuery<?> query;
        private final From<?, BookEntity> book;
        private final Long viewerId;
        private final Instant now;
        private Join<BookEntity, BookMetadataEntity> metadata;

        public RuleContext(CriteriaBuilder cb, AbstractQuery<?> query, From<?, BookEntity> book, Long viewerId, Instant now) {
            this.cb = cb;
            this.query = query;
            this.book = book;
            this.viewerId = viewerId;
            this.now = now;
        }

        CriteriaBuilder cb() {
            return cb;
        }

        AbstractQuery<?> query() {
            return query;
        }

        From<?, BookEntity> book() {
            return book;
        }

        Long viewerId() {
            return viewerId;
        }

        Instant now() {
            return now;
        }

        /** A LEFT join, made once: the list keeps books without a metadata row, whose fields all read as absent. */
        Join<BookEntity, BookMetadataEntity> metadata() {
            if (metadata == null) {
                metadata = book.join("metadata", JoinType.LEFT);
            }
            return metadata;
        }
    }

    @FunctionalInterface
    private interface Column<T> {
        Expression<T> of(RuleContext ctx);
    }

    // ------------------------------------------------------------------ compile

    public Compiled compile(String filterJson) {
        if (filterJson == null) {
            return new Unsupported(List.of("group:root"));
        }
        if (filterJson.startsWith("﻿")) {
            return new Invalid();
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(filterJson);
        } catch (JacksonException e) {
            return new Invalid();
        }
        if (root == null || root.isMissingNode()) {
            return new Invalid();
        }
        if (!root.isObject()) {
            return new Unsupported(List.of("group:root"));
        }

        Compilation compilation = new Compilation();
        Node sql = compilation.group(root, true, 0);
        if (!compilation.reasons.isEmpty()) {
            return new Unsupported(List.copyOf(compilation.reasons));
        }
        return new Supported(sql, List.copyOf(compilation.fileTypeTests), List.copyOf(compilation.fileTypeReasons));
    }

    public Predicate toPredicate(Supported compiled, RuleContext ctx) {
        return build(compiled.sql(), ctx);
    }

    private static Predicate build(Node node, RuleContext ctx) {
        if (node instanceof Leaf leaf) {
            return leaf.rule().build(ctx);
        }
        Group group = (Group) node;
        CriteriaBuilder cb = ctx.cb();
        if (group.children().isEmpty()) {
            return group.and() ? cb.conjunction() : cb.disjunction();
        }
        Predicate[] parts = group.children().stream().map(child -> build(child, ctx)).toArray(Predicate[]::new);
        return group.and() ? cb.and(parts) : cb.or(parts);
    }

    private static final class Compilation {
        private final Set<String> reasons = new LinkedHashSet<>();
        private final List<FileTypeTest> fileTypeTests = new ArrayList<>();
        private final Set<String> fileTypeReasons = new LinkedHashSet<>();

        /** {@code evaluateGroup}: {@code group.rules.map(...)} throws unless rules is an array. */
        Node group(JsonNode group, boolean root, int depth) {
            if (depth > MAX_DEPTH) {
                reasons.add("group:depth");
                return new Leaf(FALSE);
            }
            JsonNode rules = group.get("rules");
            if (rules == null || !rules.isArray()) {
                reasons.add("group:rules");
                return new Leaf(FALSE);
            }
            JsonNode join = group.get("join");
            boolean and = join != null && join.isString() && "and".equals(join.stringValue());

            List<Node> children = new ArrayList<>();
            for (JsonNode rule : rules.values()) {
                if (rule.isArray()) {
                    // 'type' in [] is false, so it is read as a rule without a field
                    reasons.add("rule:field");
                    continue;
                }
                if (!rule.isObject()) {
                    // 'type' in rule throws for null and for primitives
                    reasons.add("rule:null");
                    continue;
                }
                JsonNode type = rule.get("type");
                if (type != null && type.isString() && "group".equals(type.stringValue())) {
                    children.add(group(rule, false, depth + 1));
                    continue;
                }
                Node leaf = rule(rule, root && and);
                if (leaf != null) {
                    children.add(leaf);
                }
            }
            return new Group(and, children);
        }

        /** Returns the rule as SQL, or null when it was handled otherwise (a fileType post-filter, or a reason). */
        private Node rule(JsonNode rule, boolean inAndRoot) {
            JsonNode fieldNode = rule.get("field");
            if (fieldNode == null || !fieldNode.isString()) {
                reasons.add("rule:field");
                return null;
            }
            JsonNode operatorNode = rule.get("operator");
            if (operatorNode == null || !operatorNode.isString()) {
                reasons.add("rule:operator");
                return null;
            }
            String field = fieldNode.stringValue();
            String operator = operatorNode.stringValue();
            String reason = field + ":" + operator;
            JsonNode value = rule.get("value");

            if ("fileType".equals(field)) {
                FileTypeTest test = inAndRoot ? fileType(operator, value) : null;
                if (test == null) {
                    reasons.add(reason);
                } else {
                    fileTypeTests.add(test);
                    fileTypeReasons.add(reason);
                }
                return null;
            }

            SqlRule compiled = compileRule(field, operator, value, rule.get("valueStart"), rule.get("valueEnd"));
            if (compiled == null) {
                reasons.add(reason);
                return null;
            }
            return new Leaf(compiled);
        }
    }

    /** Null when the pair, or this value for it, is not supported. */
    private static SqlRule compileRule(String field, String operator, JsonNode value, JsonNode valueStart, JsonNode valueEnd) {
        if (TEXT_FIELDS.contains(field)) return text(field, operator, value);
        if (LIST_FIELDS.contains(field)) return list(field, operator, value);
        if (RATING_FIELDS.contains(field)) {
            return number(new NumberColumn(ctx -> ctx.metadata().get(field), NumberColumn.Storage.FLOAT_AS_DOUBLE),
                    operator, value, valueStart, valueEnd);
        }
        if (COUNT_FIELDS.contains(field)) {
            return number(new NumberColumn(ctx -> ctx.metadata().get(field), NumberColumn.Storage.INTEGER),
                    operator, value, valueStart, valueEnd);
        }
        return switch (field) {
            case "metadataScore" -> number(new NumberColumn(ctx -> ctx.book().get("metadataMatchScore"), NumberColumn.Storage.FLOAT),
                    operator, value, valueStart, valueEnd);
            case "isPhysical", "isComic" -> bookFlag(field, operator, value);
            case "titleChangedByAutoFetch" -> titleChangedByAutoFetch(operator, value);
            case "addedOn" -> addedOn(operator, value, valueStart, valueEnd);
            case "library" -> library(operator, value);
            case "readStatus" -> readStatus(operator, value);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ text: lower(c) or null

    private static SqlRule text(String attribute, String operator, JsonNode value) {
        Column<String> column = ctx -> ctx.metadata().get(attribute);
        Norm v = normalize(value);
        return switch (operator) {
            case "equals" -> textEquals(column, v);
            case "not_equals" -> not(textEquals(column, v));
            case "contains" -> v.isString() ? textLike(column, "%" + escapeLike(v.string()) + "%") : FALSE;
            case "starts_with" -> v.isString() ? textLike(column, escapeLike(v.string()) + "%") : FALSE;
            case "ends_with" -> v.isString() ? textLike(column, "%" + escapeLike(v.string())) : FALSE;
            case "does_not_contain" -> v.isString() ? not(textLike(column, "%" + escapeLike(v.string()) + "%")) : TRUE;
            case "is_empty" -> blank(column);
            case "is_not_empty" -> not(blank(column));
            case "includes_any", "excludes_all", "includes_all" -> textList(column, operator, value);
            default -> null;
        };
    }

    /** {@code value === ruleVal}: a string matches the lower-cased column, null matches a missing value. */
    private static SqlRule textEquals(Column<String> column, Norm v) {
        return switch (v.kind()) {
            case STRING -> ctx -> {
                Expression<String> c = column.of(ctx);
                return ctx.cb().and(ctx.cb().isNotNull(c), ctx.cb().equal(ctx.cb().lower(c), v.string()));
            };
            case NULL -> isNull(column);
            default -> FALSE;
        };
    }

    private static SqlRule textLike(Column<String> column, String pattern) {
        return ctx -> {
            Expression<String> c = column.of(ctx);
            return ctx.cb().and(ctx.cb().isNotNull(c), ctx.cb().like(ctx.cb().lower(c), pattern, LIKE_ESCAPE));
        };
    }

    /**
     * {@code value.trim() === ''}, or a missing value. JavaScript's trim also removes tabs, line breaks and the
     * Unicode spaces, which SQL TRIM does not, so every such character is removed and the length tested.
     */
    private static SqlRule blank(Column<String> column) {
        return ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Expression<String> c = column.of(ctx);
            Expression<String> stripped = c;
            for (int i = 0; i < WHITESPACE.length(); i++) {
                stripped = cb.function("replace", String.class, stripped,
                        cb.literal(String.valueOf(WHITESPACE.charAt(i))), cb.literal(""));
            }
            return cb.or(cb.isNull(c), cb.equal(cb.length(stripped), 0));
        };
    }

    /** {@code getArrayField} gives a text field as the one-element list {@code [lower(value ?? '')]}. */
    private static SqlRule textList(Column<String> column, String operator, JsonNode value) {
        List<String> list = ruleList(value, JsSemantics::lower);
        if (list == null) return null;
        Set<String> values = new LinkedHashSet<>(list);
        Column<String> asListed = ctx -> ctx.cb().lower(ctx.cb().coalesce(column.of(ctx), ""));
        SqlRule any = values.isEmpty() ? FALSE : ctx -> asListed.of(ctx).in(values);
        return switch (operator) {
            case "includes_any" -> any;
            case "excludes_all" -> not(any);
            default -> values.isEmpty() ? TRUE : values.size() == 1 ? any : FALSE;
        };
    }

    // ------------------------------------------------------------------ lists: lower-cased names

    private static SqlRule list(String collection, String operator, JsonNode value) {
        Norm v = normalize(value);
        return switch (operator) {
            case "equals", "includes_any", "not_equals", "excludes_all", "includes_all" -> {
                List<String> list = ruleList(value, JsSemantics::lower);
                if (list == null) yield null;
                Set<String> values = new LinkedHashSet<>(list);
                SqlRule any = values.isEmpty() ? FALSE : hasEntry(collection, (cb, name) -> cb.lower(name).in(values));
                yield switch (operator) {
                    case "equals", "includes_any" -> any;
                    case "not_equals", "excludes_all" -> not(any);
                    default -> and(values.stream()
                            .map(entry -> hasEntry(collection, (cb, name) -> cb.equal(cb.lower(name), entry)))
                            .toList());
                };
            }
            case "contains" -> v.isString() ? hasEntryLike(collection, "%" + escapeLike(v.string()) + "%") : FALSE;
            case "starts_with" -> v.isString() ? hasEntryLike(collection, escapeLike(v.string()) + "%") : FALSE;
            case "ends_with" -> v.isString() ? hasEntryLike(collection, "%" + escapeLike(v.string())) : FALSE;
            case "does_not_contain" -> v.isString() ? not(hasEntryLike(collection, "%" + escapeLike(v.string()) + "%")) : TRUE;
            case "is_empty" -> not(hasEntry(collection, null));
            case "is_not_empty" -> hasEntry(collection, null);
            default -> null;
        };
    }

    private static SqlRule hasEntryLike(String collection, String pattern) {
        return hasEntry(collection, (cb, name) -> cb.like(cb.lower(name), pattern, LIKE_ESCAPE));
    }

    /** EXISTS an entry of the book's metadata collection (meeting the condition, when one is given). */
    private static SqlRule hasEntry(String collection, BiFunction<CriteriaBuilder, Expression<String>, Predicate> condition) {
        return ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Subquery<Integer> entries = ctx.query().subquery(Integer.class);
            Root<BookMetadataEntity> metadata = entries.from(BookMetadataEntity.class);
            Join<BookMetadataEntity, ?> entry = metadata.join(collection);
            Predicate ofBook = cb.equal(metadata.get("bookId"), ctx.book().get("id"));
            entries.select(cb.literal(1))
                    .where(condition == null ? ofBook : cb.and(ofBook, condition.apply(cb, entry.<String>get("name"))));
            return cb.exists(entries);
        };
    }

    // ------------------------------------------------------------------ numbers: the number, or undefined

    private record NumberColumn(Column<? extends Number> column, Storage storage) {
        enum Storage {
            /** {@code Float} mapped on a FLOAT column. */
            FLOAT,
            /** {@code Double} mapped on a FLOAT column (MariaDB) or a DOUBLE column (H2). */
            FLOAT_AS_DOUBLE,
            INTEGER
        }

        Number bind(double r) {
            return storage == Storage.FLOAT ? (Number) (float) r : (Number) r;
        }
    }

    private static SqlRule number(NumberColumn column, String operator, JsonNode value, JsonNode valueStart, JsonNode valueEnd) {
        Norm v = normalize(value);
        return switch (operator) {
            case "equals" -> numberEquals(column, v);
            case "not_equals" -> {
                SqlRule equals = numberEquals(column, v);
                yield equals == null ? null : not(equals);
            }
            case "greater_than", "greater_than_equal_to", "less_than", "less_than_equal_to" -> {
                Double r = toNumber(v);
                yield r == null ? null : compare(column, operator, r);
            }
            case "in_between" -> {
                Norm start = normalize(valueStart);
                Norm end = normalize(valueEnd);
                if (start.isNullish() || end.isNullish()) yield FALSE;
                Double s = toNumber(start);
                Double e = toNumber(end);
                if (s == null || e == null) yield null;
                SqlRule lower = compare(column, "greater_than_equal_to", s);
                SqlRule upper = compare(column, "less_than_equal_to", e);
                yield lower == null || upper == null ? null : and(List.of(lower, upper));
            }
            case "is_empty" -> isNull(column.column());
            case "is_not_empty" -> not(isNull(column.column()));
            // A number is no string, and getArrayField gives no list for it
            case "contains", "starts_with", "ends_with", "includes_any" -> FALSE;
            case "does_not_contain", "excludes_all" -> TRUE;
            case "includes_all" -> ruleListIsEmpty(value) ? TRUE : FALSE;
            default -> null;
        };
    }

    /** {@code value === ruleVal}: only a number can match a number, and a missing rule value matches a missing value. */
    private static SqlRule numberEquals(NumberColumn column, Norm v) {
        return switch (v.kind()) {
            case NUMBER -> {
                double n = v.number();
                if (Double.isInfinite(n)) yield FALSE;
                if (column.storage() == NumberColumn.Storage.INTEGER) {
                    yield n == Math.rint(n) && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE
                            ? numberTest(column, (cb, c) -> cb.equal(c, (int) n)) : FALSE;
                }
                Number bound = column.bind(n);
                yield isFloatExact(n) ? numberTest(column, (cb, c) -> cb.equal(c, bound)) : null;
            }
            case MISSING -> isNull(column.column());
            default -> FALSE;
        };
    }

    /**
     * {@code Number(value) op r}: an absent value is NaN and fails. An integer column is compared against the integer
     * bound the operator implies, so 7.5 needs no cast; a float column only against a float-exact r.
     */
    private static SqlRule compare(NumberColumn column, String operator, double r) {
        if (Double.isNaN(r)) return FALSE;
        boolean upward = operator.startsWith("greater_than");
        if (Double.isInfinite(r) || (column.storage() == NumberColumn.Storage.INTEGER && Math.abs(r) > 1e15)) {
            return upward == (r < 0) ? not(isNull(column.column())) : FALSE;
        }
        if (column.storage() == NumberColumn.Storage.INTEGER) {
            long bound = switch (operator) {
                case "greater_than" -> (long) Math.floor(r) + 1;
                case "greater_than_equal_to" -> (long) Math.ceil(r);
                case "less_than" -> (long) Math.ceil(r) - 1;
                default -> (long) Math.floor(r);
            };
            if (upward) {
                if (bound > Integer.MAX_VALUE) return FALSE;
                if (bound <= Integer.MIN_VALUE) return not(isNull(column.column()));
                return numberTest(column, (cb, c) -> cb.ge(c, (int) bound));
            }
            if (bound < Integer.MIN_VALUE) return FALSE;
            if (bound >= Integer.MAX_VALUE) return not(isNull(column.column()));
            return numberTest(column, (cb, c) -> cb.le(c, (int) bound));
        }
        if (!isFloatExact(r)) return null;
        Number bound = column.bind(r);
        return switch (operator) {
            case "greater_than" -> numberTest(column, (cb, c) -> cb.gt(c, bound));
            case "greater_than_equal_to" -> numberTest(column, (cb, c) -> cb.ge(c, bound));
            case "less_than" -> numberTest(column, (cb, c) -> cb.lt(c, bound));
            default -> numberTest(column, (cb, c) -> cb.le(c, bound));
        };
    }

    private static SqlRule numberTest(NumberColumn column, BiFunction<CriteriaBuilder, Expression<? extends Number>, Predicate> test) {
        return ctx -> {
            Expression<? extends Number> c = column.column().of(ctx);
            return ctx.cb().and(ctx.cb().isNotNull(c), test.apply(ctx.cb(), c));
        };
    }

    // ------------------------------------------------------------------ booleans: "true", "false" or undefined

    private static SqlRule bookFlag(String attribute, String operator, JsonNode value) {
        Column<Boolean> column = ctx -> ctx.book().get(attribute);
        Norm v = normalize(value);
        SqlRule equals = switch (v.kind()) {
            case STRING -> "true".equals(v.string()) ? flagIs(column, true)
                    : "false".equals(v.string()) ? flagIs(column, false) : FALSE;
            // undefined === undefined: a missing rule value matches a book without the flag
            case MISSING -> isNull(column);
            default -> FALSE;
        };
        return flagRule(operator, value, equals, isNull(column));
    }

    /** {@code metadata?.titleChangedByAutoFetch ?? false}: never empty, and false without a metadata row. */
    private static SqlRule titleChangedByAutoFetch(String operator, JsonNode value) {
        SqlRule isTrue = flagIs(ctx -> ctx.metadata().get("titleChangedByAutoFetch"), true);
        Norm v = normalize(value);
        SqlRule equals = v.isString() && "true".equals(v.string()) ? isTrue
                : v.isString() && "false".equals(v.string()) ? not(isTrue) : FALSE;
        return flagRule(operator, value, equals, FALSE);
    }

    private static SqlRule flagRule(String operator, JsonNode value, SqlRule equals, SqlRule empty) {
        return switch (operator) {
            case "equals" -> equals;
            case "not_equals" -> not(equals);
            case "is_empty" -> empty;
            case "is_not_empty" -> not(empty);
            case "includes_any" -> FALSE;
            case "excludes_all" -> TRUE;
            case "includes_all" -> ruleListIsEmpty(value) ? TRUE : FALSE;
            default -> null;
        };
    }

    private static SqlRule flagIs(Column<Boolean> column, boolean expected) {
        return ctx -> {
            Expression<Boolean> c = column.of(ctx);
            return ctx.cb().and(ctx.cb().isNotNull(c), ctx.cb().equal(c, expected));
        };
    }

    // ------------------------------------------------------------------ addedOn: a Date, or null

    private static SqlRule addedOn(String operator, JsonNode value, JsonNode valueStart, JsonNode valueEnd) {
        Column<Instant> column = ctx -> ctx.book().get("addedOn");
        return switch (operator) {
            case "equals", "not_equals" -> {
                Norm v = normalizeDate(value);
                if (v == null) yield null;
                SqlRule equals = switch (v.kind()) {
                    case DATE -> dateTest(column, (cb, c) -> cb.equal(c, v.date()));
                    case NULL -> isNull(column);
                    default -> FALSE;
                };
                yield operator.equals("equals") ? equals : not(equals);
            }
            case "greater_than", "greater_than_equal_to", "less_than", "less_than_equal_to" -> {
                Norm v = normalizeDate(value);
                if (v == null) yield null;
                yield switch (v.kind()) {
                    case DATE -> dateCompare(column, operator, v.date());
                    // Number(undefined) and Number("true") are NaN
                    case MISSING, STRING -> FALSE;
                    default -> null;
                };
            }
            case "in_between" -> {
                Norm start = normalizeDate(valueStart);
                Norm end = normalizeDate(valueEnd);
                if ((start != null && start.isNullish()) || (end != null && end.isNullish())) yield FALSE;
                if (start == null || end == null || start.kind() != Kind.DATE || end.kind() != Kind.DATE) yield null;
                yield dateTest(column, (cb, c) -> cb.and(cb.greaterThanOrEqualTo(c, start.date()), cb.lessThanOrEqualTo(c, end.date())));
            }
            case "is_empty" -> isNull(column);
            case "is_not_empty" -> not(isNull(column));
            case "within_last", "older_than" -> relativeDate(column, operator.equals("within_last"), value, valueEnd);
            default -> null;
        };
    }

    /** Both Dates compare by time; a missing value is {@code Number(null)}, 0, against the rule's time. */
    private static SqlRule dateCompare(Column<Instant> column, String operator, Instant date) {
        long ms = date.toEpochMilli();
        boolean nullMatches = switch (operator) {
            case "greater_than" -> 0 > ms;
            case "greater_than_equal_to" -> 0 >= ms;
            case "less_than" -> 0 < ms;
            default -> 0 <= ms;
        };
        SqlRule present = switch (operator) {
            case "greater_than" -> dateTest(column, (cb, c) -> cb.greaterThan(c, date));
            case "greater_than_equal_to" -> dateTest(column, (cb, c) -> cb.greaterThanOrEqualTo(c, date));
            case "less_than" -> dateTest(column, (cb, c) -> cb.lessThan(c, date));
            default -> dateTest(column, (cb, c) -> cb.lessThanOrEqualTo(c, date));
        };
        return nullMatches ? or(List.of(present, isNull(column))) : present;
    }

    /** {@code computeDateThreshold}, in days or weeks; months and years use the browser's time zone. */
    private static SqlRule relativeDate(Column<Instant> column, boolean within, JsonNode value, JsonNode valueEnd) {
        Double amount = rawToNumber(value);
        Unit unit = relativeDateUnit(valueEnd);
        if (amount == null || unit == null) return null;
        return ctx -> {
            Long threshold = relativeDateThreshold(amount, unit, ctx.now());
            if (threshold == null) return ctx.cb().disjunction();
            Instant at = Instant.ofEpochMilli(threshold);
            return dateTest(column, (cb, c) -> within ? cb.greaterThanOrEqualTo(c, at) : cb.lessThan(c, at)).build(ctx);
        };
    }

    private static SqlRule dateTest(Column<Instant> column, BiFunction<CriteriaBuilder, Expression<Instant>, Predicate> test) {
        return ctx -> {
            Expression<Instant> c = column.of(ctx);
            return ctx.cb().and(ctx.cb().isNotNull(c), test.apply(ctx.cb(), c));
        };
    }

    // ------------------------------------------------------------------ library: the number libraryId

    private static SqlRule library(String operator, JsonNode value) {
        Column<Long> column = ctx -> ctx.book().get("library").get("id");
        Norm v = normalize(value);
        SqlRule equals = v.kind() == Kind.NUMBER && v.number() == Math.rint(v.number()) && Math.abs(v.number()) < 0x1p53
                ? libraryIn(column, Set.of((long) v.number())) : FALSE;
        return switch (operator) {
            case "equals" -> equals;
            case "not_equals" -> not(equals);
            case "includes_any", "excludes_all", "includes_all" -> {
                // String(v), not lower-cased: the list matches String(book.libraryId)
                List<String> list = ruleList(value, UnaryOperator.identity());
                if (list == null) yield null;
                Set<String> values = new LinkedHashSet<>(list);
                Set<Long> ids = new LinkedHashSet<>();
                values.forEach(s -> {
                    Long id = canonicalId(s);
                    if (id != null) ids.add(id);
                });
                SqlRule any = ids.isEmpty() ? FALSE : libraryIn(column, ids);
                yield switch (operator) {
                    case "includes_any" -> any;
                    case "excludes_all" -> not(any);
                    default -> values.isEmpty() ? TRUE : values.size() == 1 ? any : FALSE;
                };
            }
            case "is_empty" -> FALSE;
            case "is_not_empty" -> TRUE;
            default -> null;
        };
    }

    private static SqlRule libraryIn(Column<Long> column, Set<Long> ids) {
        return ctx -> column.of(ctx).in(ids);
    }

    /** The id whose {@code String(id)} is exactly s, if any. */
    private static Long canonicalId(String s) {
        if (!s.matches("-?\\d{1,18}")) return null;
        long id = Long.parseLong(s);
        return Long.toString(id).equals(s) ? id : null;
    }

    // ------------------------------------------------------------------ readStatus: lower(readStatus ?? 'UNSET')

    private static SqlRule readStatus(String operator, JsonNode value) {
        Norm v = normalize(value);
        return switch (operator) {
            case "equals" -> v.isString() ? status(v.string()) : FALSE;
            case "not_equals" -> not(v.isString() ? status(v.string()) : FALSE);
            case "includes_any", "excludes_all", "includes_all" -> {
                List<String> list = ruleList(value, JsSemantics::lower);
                if (list == null) yield null;
                Set<String> values = new LinkedHashSet<>(list);
                SqlRule any = or(values.stream().map(MagicShelfCountCompiler::status).toList());
                yield switch (operator) {
                    case "includes_any" -> any;
                    case "excludes_all" -> not(any);
                    default -> values.isEmpty() ? TRUE : values.size() == 1 ? any : FALSE;
                };
            }
            // is_empty reads book.readStatus itself, which only a progress row sets
            case "is_empty" -> not(hasProgress(null));
            case "is_not_empty" -> hasProgress(null);
            default -> null;
        };
    }

    /** The viewer's status is this lower-cased name: "unset" for no row, a NULL status, or UNSET. */
    private static SqlRule status(String lowered) {
        if (lower(ReadStatus.UNSET.name()).equals(lowered)) {
            return not(hasProgress((cb, p) -> cb.and(cb.isNotNull(p.get("readStatus")),
                    cb.notEqual(p.get("readStatus"), ReadStatus.UNSET))));
        }
        for (ReadStatus status : ReadStatus.values()) {
            if (lower(status.name()).equals(lowered)) {
                return hasProgress((cb, p) -> cb.equal(p.get("readStatus"), status));
            }
        }
        return FALSE;
    }

    /** EXISTS the viewer's progress row for the book: (user, book) is unique, as the list's progress map needs. */
    private static SqlRule hasProgress(BiFunction<CriteriaBuilder, Root<UserBookProgressEntity>, Predicate> condition) {
        return ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Subquery<Integer> rows = ctx.query().subquery(Integer.class);
            Root<UserBookProgressEntity> progress = rows.from(UserBookProgressEntity.class);
            Predicate own = cb.and(cb.equal(progress.get("book").get("id"), ctx.book().get("id")),
                    cb.equal(progress.get("user").get("id"), ctx.viewerId()));
            rows.select(cb.literal(1)).where(condition == null ? own : cb.and(own, condition.apply(cb, progress)));
            return cb.exists(rows);
        };
    }

    // ------------------------------------------------------------------ fileType, in Java

    private static FileTypeTest fileType(String operator, JsonNode value) {
        Norm v = normalize(value);
        return switch (operator) {
            case "equals", "not_equals" -> {
                FileTypeTest equals = switch (v.kind()) {
                    case STRING -> {
                        String expected = mapFileType(v.string());
                        yield expected::equals;
                    }
                    case NULL -> Objects::isNull;
                    default -> type -> false;
                };
                yield operator.equals("equals") ? equals : type -> !equals.test(type);
            }
            case "includes_any", "excludes_all", "includes_all" -> {
                List<String> list = ruleList(value, s -> mapFileType(lower(s)));
                if (list == null) yield null;
                Set<String> values = new LinkedHashSet<>(list);
                // getArrayField: [String(primaryFile?.bookType ?? '').toLowerCase()]
                FileTypeTest any = type -> values.contains(type == null ? "" : type);
                yield switch (operator) {
                    case "includes_any" -> any;
                    case "excludes_all" -> type -> !any.test(type);
                    default -> values.isEmpty() ? type -> true : values.size() == 1 ? any : type -> false;
                };
            }
            case "is_empty" -> Objects::isNull;
            case "is_not_empty" -> Objects::nonNull;
            case "contains" -> v.isString() ? type -> type != null && type.contains(v.string()) : type -> false;
            case "starts_with" -> v.isString() ? type -> type != null && type.startsWith(v.string()) : type -> false;
            case "ends_with" -> v.isString() ? type -> type != null && type.endsWith(v.string()) : type -> false;
            case "does_not_contain" -> v.isString() ? type -> type == null || !type.contains(v.string()) : type -> true;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ helpers

    private static SqlRule not(SqlRule rule) {
        if (rule == TRUE) return FALSE;
        if (rule == FALSE) return TRUE;
        return ctx -> ctx.cb().not(rule.build(ctx));
    }

    private static SqlRule and(List<SqlRule> rules) {
        if (rules.isEmpty()) return TRUE;
        if (rules.size() == 1) return rules.getFirst();
        return ctx -> ctx.cb().and(rules.stream().map(rule -> rule.build(ctx)).toArray(Predicate[]::new));
    }

    private static SqlRule or(List<SqlRule> rules) {
        if (rules.isEmpty()) return FALSE;
        if (rules.size() == 1) return rules.getFirst();
        return ctx -> ctx.cb().or(rules.stream().map(rule -> rule.build(ctx)).toArray(Predicate[]::new));
    }

    private static <T> SqlRule isNull(Column<T> column) {
        return ctx -> ctx.cb().isNull(column.of(ctx));
    }

    private static String escapeLike(String value) {
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
