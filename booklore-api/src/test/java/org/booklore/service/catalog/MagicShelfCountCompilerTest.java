package org.booklore.service.catalog;

import org.booklore.service.catalog.MagicShelfCountCompiler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which filters the compiler takes, and how it reads their structure. What the SQL selects is checked against the UI's
 * evaluator in {@link CatalogParityTest}.
 */
class MagicShelfCountCompilerTest {

    private static final List<String> TEXT_FIELDS = List.of("title", "publisher", "seriesName", "language", "isbn13", "isbn10", "narrator");
    private static final List<String> LIST_FIELDS = List.of("authors", "categories", "moods", "tags");
    private static final List<String> NUMBER_FIELDS = List.of("metadataScore", "pageCount", "amazonRating", "amazonReviewCount",
            "goodreadsRating", "goodreadsReviewCount", "hardcoverRating", "hardcoverReviewCount", "ranobedbRating");
    private static final List<String> FLAG_FIELDS = List.of("isPhysical", "isComic", "titleChangedByAutoFetch");

    /** Every operator of the UI's RuleOperator, each with a value of the kind its editor writes. */
    private static final List<String> OPERATORS = List.of("equals", "not_equals", "contains", "does_not_contain",
            "starts_with", "ends_with", "greater_than", "greater_than_equal_to", "less_than", "less_than_equal_to",
            "in_between", "is_empty", "is_not_empty", "includes_any", "excludes_all", "includes_all", "within_last",
            "older_than", "this_period");

    private final MagicShelfCountCompiler compiler = new MagicShelfCountCompiler();

    // ------------------------------------------------------------------ production rules

    @Test
    void everyStoredProductionRuleIsCountedOnTheServer() {
        List<String> stored = List.of(
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":50},{\"type\":\"group\",\"join\":\"or\",\"rules\":[{\"field\":\"isbn13\",\"operator\":\"is_not_empty\"},{\"field\":\"isbn10\",\"operator\":\"is_not_empty\"}]}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"titleChangedByAutoFetch\",\"operator\":\"equals\",\"value\":\"true\"}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"metadataScore\",\"operator\":\"less_than_equal_to\",\"value\":10},{\"field\":\"title\",\"operator\":\"starts_with\",\"value\":\"A\"}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"isbn13\",\"operator\":\"is_empty\"},{\"field\":\"isbn10\",\"operator\":\"is_empty\"},{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"isComic\",\"operator\":\"not_equals\",\"value\":\"true\"}]}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"isbn13\",\"operator\":\"is_empty\"},{\"field\":\"isbn10\",\"operator\":\"is_empty\"},{\"field\":\"authors\",\"operator\":\"equals\",\"value\":\"Tom Clancy\"}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"metadataScore\",\"operator\":\"greater_than\",\"value\":60},{\"field\":\"goodreadsRating\",\"operator\":\"is_empty\"}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"Child Love\"}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"addedOn\",\"operator\":\"within_last\",\"value\":14,\"valueEnd\":\"days\"}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"addedOn\",\"operator\":\"within_last\",\"value\":3,\"valueEnd\":\"days\"},{\"field\":\"metadataScore\",\"operator\":\"less_than_equal_to\",\"value\":70}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":60},{\"field\":\"library\",\"operator\":\"equals\",\"value\":3}]}",
                "{\"type\":\"group\",\"join\":\"or\",\"rules\":[{\"field\":\"authors\",\"operator\":\"is_empty\"},{\"field\":\"authors\",\"operator\":\"equals\",\"value\":\"Unknown Author\"}]}",
                "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"tags\",\"operator\":\"includes_any\",\"value\":[\"File Not Found\"]}]}");
        for (String filterJson : stored) {
            Compiled compiled = compiler.compile(filterJson);
            assertThat(compiled).as(filterJson).isInstanceOf(Supported.class);
            assertThat(((Supported) compiled).needsFileTypeFilter()).as(filterJson).isFalse();
        }
    }

    @Test
    void theFileTypeShelfRunsItsFileTypeRuleInJava() {
        Compiled compiled = compiler.compile("{\"type\":\"group\",\"join\":\"and\",\"rules\":["
                + "{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":50},"
                + "{\"field\":\"authors\",\"operator\":\"equals\",\"value\":\"unknown\"},"
                + "{\"field\":\"fileType\",\"operator\":\"excludes_all\",\"value\":[\"cbr\",\"cbz\",\"cb7\",\"opus\",\"mp3\",\"m4a\",\"m4b\"]}]}");

        assertThat(compiled).isInstanceOf(Supported.class);
        Supported supported = (Supported) compiled;
        assertThat(supported.fileTypeReasons()).containsExactly("fileType:excludes_all");
        assertThat(supported.fileTypeTests()).hasSize(1);
        // The SQL keeps the other two rules of the AND root
        assertThat(supported.sql()).isInstanceOfSatisfying(Group.class, group -> {
            assertThat(group.and()).isTrue();
            assertThat(group.children()).hasSize(2).allMatch(Leaf.class::isInstance);
        });

        FileTypeTest excludes = supported.fileTypeTests().getFirst();
        // cbr, cbz and cb7 read as the primary file's CBX; the audio extensions match no bookType
        assertThat(excludes.test("cbx")).isFalse();
        assertThat(excludes.test("audiobook")).isTrue();
        assertThat(excludes.test("epub")).isTrue();
        // No primary file: [''] holds none of the values
        assertThat(excludes.test(null)).isTrue();
    }

    // ------------------------------------------------------------------ whitelist

    static Stream<Arguments> supportedPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (String field : TEXT_FIELDS) {
            for (String op : List.of("equals", "not_equals", "contains", "does_not_contain", "starts_with", "ends_with",
                    "is_empty", "is_not_empty", "includes_any", "excludes_all", "includes_all")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String field : LIST_FIELDS) {
            for (String op : List.of("equals", "not_equals", "contains", "does_not_contain", "starts_with", "ends_with",
                    "is_empty", "is_not_empty", "includes_any", "excludes_all", "includes_all")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String field : NUMBER_FIELDS) {
            for (String op : List.of("equals", "not_equals", "greater_than", "greater_than_equal_to", "less_than",
                    "less_than_equal_to", "in_between", "is_empty", "is_not_empty")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String field : FLAG_FIELDS) {
            for (String op : List.of("equals", "not_equals", "is_empty", "is_not_empty")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String op : List.of("equals", "not_equals", "greater_than", "greater_than_equal_to", "less_than",
                "less_than_equal_to", "in_between", "is_empty", "is_not_empty", "within_last", "older_than")) {
            pairs.add(Arguments.of("addedOn", op));
        }
        for (String op : List.of("equals", "not_equals", "includes_any", "excludes_all", "includes_all", "is_empty", "is_not_empty")) {
            pairs.add(Arguments.of("library", op));
            pairs.add(Arguments.of("readStatus", op));
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("supportedPairs")
    void compilesEverySupportedPair(String field, String operator) {
        assertThat(compiler.compile(shelf(rule(field, operator)))).isInstanceOf(Supported.class);
    }

    static Stream<Arguments> unsupportedPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (String field : TEXT_FIELDS) {
            for (String op : List.of("greater_than", "less_than_equal_to", "in_between", "within_last", "older_than", "this_period")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String field : LIST_FIELDS) {
            for (String op : List.of("greater_than", "in_between", "within_last", "this_period")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String field : NUMBER_FIELDS) {
            for (String op : List.of("within_last", "older_than", "this_period")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String field : FLAG_FIELDS) {
            for (String op : List.of("contains", "greater_than", "in_between", "within_last")) {
                pairs.add(Arguments.of(field, op));
            }
        }
        for (String op : List.of("contains", "starts_with", "includes_any", "this_period")) {
            pairs.add(Arguments.of("addedOn", op));
        }
        for (String op : List.of("contains", "greater_than", "within_last")) {
            pairs.add(Arguments.of("library", op));
            pairs.add(Arguments.of("readStatus", op));
        }
        // Fields the server does not count at all: whatever the operator
        for (String field : List.of("shelf", "subtitle", "description", "publishedDate", "seriesNumber", "seriesTotal",
                "personalRating", "fileSize", "dateFinished", "lastReadTime", "lubimyczytacRating", "ageRating",
                "contentRating", "audibleRating", "audibleReviewCount", "abridged", "audiobookDuration", "audiobookCodec",
                "audiobookChapterCount", "audiobookBitrate", "seriesStatus", "seriesGaps", "seriesPosition",
                "readingProgress", "metadataPresence", "noSuchField")) {
            for (String op : OPERATORS) {
                pairs.add(Arguments.of(field, op));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unsupportedPairs")
    void refusesEveryOtherPairWithItsName(String field, String operator) {
        assertThat(compiler.compile(shelf(rule(field, operator))))
                .isInstanceOfSatisfying(Unsupported.class, unsupported ->
                        assertThat(unsupported.reasons()).containsExactly(field + ":" + operator));
    }

    @Test
    void anUnknownOperatorIsRefused() {
        assertThat(compiler.compile(shelf("{\"field\":\"title\",\"operator\":\"sounds_like\",\"value\":\"a\"}")))
                .isEqualTo(new Unsupported(List.of("title:sounds_like")));
    }

    // ------------------------------------------------------------------ never fail open

    @Test
    void oneUnsupportedRuleAnywhereRefusesTheWholeShelf() {
        // Dropping the shelf rule from the OR would count every book titled "a" and miss the shelf's books
        String filterJson = "{\"join\":\"and\",\"rules\":[{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":50},"
                + "{\"type\":\"group\",\"join\":\"or\",\"rules\":[{\"field\":\"title\",\"operator\":\"equals\",\"value\":\"a\"},"
                + "{\"type\":\"group\",\"join\":\"and\",\"rules\":[{\"field\":\"shelf\",\"operator\":\"includes_any\",\"value\":[1]}]}]}]}";

        assertThat(compiler.compile(filterJson)).isEqualTo(new Unsupported(List.of("shelf:includes_any")));
    }

    @Test
    void collectsEveryReasonOnceInOrder() {
        String filterJson = shelf(rule("seriesStatus", "equals"), rule("title", "equals"), rule("shelf", "equals"),
                rule("seriesStatus", "equals"));

        assertThat(compiler.compile(filterJson)).isEqualTo(new Unsupported(List.of("seriesStatus:equals", "shelf:equals")));
    }

    @Test
    void fileTypeIsRefusedOutsideAnAndRoot() {
        String inOrRoot = "{\"join\":\"or\",\"rules\":[" + rule("fileType", "equals") + "]}";
        String inNestedGroup = shelf("{\"type\":\"group\",\"join\":\"and\",\"rules\":[" + rule("fileType", "equals") + "]}");

        assertThat(compiler.compile(inOrRoot)).isEqualTo(new Unsupported(List.of("fileType:equals")));
        assertThat(compiler.compile(inNestedGroup)).isEqualTo(new Unsupported(List.of("fileType:equals")));
    }

    @Test
    void fileTypeRefusesAnOperatorItDoesNotKnow() {
        assertThat(compiler.compile(shelf(rule("fileType", "greater_than"))))
                .isEqualTo(new Unsupported(List.of("fileType:greater_than")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Number([50]) and Number({}) are left to the client
            "{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":[50]}",
            "{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":{\"n\":50}}",
            // A FLOAT column against a number a float cannot hold
            "{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":0.1}",
            "{\"field\":\"goodreadsRating\",\"operator\":\"equals\",\"value\":3.7}",
            // Hexadecimal: Number("0x10") is 16 in JavaScript
            "{\"field\":\"metadataScore\",\"operator\":\"greater_than\",\"value\":\"0x10\"}",
            // Months and years use the browser's calendar and time zone
            "{\"field\":\"addedOn\",\"operator\":\"within_last\",\"value\":1,\"valueEnd\":\"months\"}",
            "{\"field\":\"addedOn\",\"operator\":\"older_than\",\"value\":2,\"valueEnd\":\"YEARS\"}",
            "{\"field\":\"addedOn\",\"operator\":\"within_last\",\"value\":[14],\"valueEnd\":\"days\"}",
            // An ISO date-time without an offset is the browser's local time
            "{\"field\":\"addedOn\",\"operator\":\"greater_than\",\"value\":\"2026-01-01T10:00:00\"}",
            "{\"field\":\"addedOn\",\"operator\":\"equals\",\"value\":\"January 1, 2026\"}",
            // String([[1]]) and String({}) in a rule list
            "{\"field\":\"tags\",\"operator\":\"includes_any\",\"value\":[[\"a\"]]}",
            "{\"field\":\"library\",\"operator\":\"includes_any\",\"value\":[{\"id\":3}]}"
    })
    void refusesValuesWhoseConversionIsLeftToTheBrowser(String rule) {
        assertThat(compiler.compile(shelf(rule))).isInstanceOf(Unsupported.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Number("50") is 50 and Number("abc") is NaN, which matches nothing: both as in the UI
            "{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":\"50\"}",
            "{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":\"abc\"}",
            "{\"field\":\"metadataScore\",\"operator\":\"less_than\",\"value\":59.5}",
            "{\"field\":\"addedOn\",\"operator\":\"within_last\",\"value\":\"14\"}",
            "{\"field\":\"addedOn\",\"operator\":\"within_last\",\"value\":2,\"valueEnd\":\"Weeks\"}",
            "{\"field\":\"addedOn\",\"operator\":\"greater_than\",\"value\":\"2026-01-01T10:00:00+02:00\"}",
            "{\"field\":\"library\",\"operator\":\"includes_any\",\"value\":[1,\"3\"]}",
            "{\"field\":\"title\",\"operator\":\"equals\",\"value\":true}",
            "{\"field\":\"title\",\"operator\":\"contains\",\"value\":42}"
    })
    void takesValuesThatConvertTheSameEverywhere(String rule) {
        assertThat(compiler.compile(shelf(rule))).isInstanceOf(Supported.class);
    }

    // ------------------------------------------------------------------ groups

    @Test
    void onlyTheExactJoinAndIsAnd() {
        assertThat(rootOf("{\"join\":\"and\",\"rules\":[]}").and()).isTrue();
        assertThat(rootOf("{\"join\":\"AND\",\"rules\":[]}").and()).isFalse();
        assertThat(rootOf("{\"join\":\"or\",\"rules\":[]}").and()).isFalse();
        assertThat(rootOf("{\"join\":\"xor\",\"rules\":[]}").and()).isFalse();
        assertThat(rootOf("{\"join\":true,\"rules\":[]}").and()).isFalse();
        assertThat(rootOf("{\"rules\":[]}").and()).isFalse();
    }

    @Test
    void emptyGroupsAreKeptForTheirJoin() {
        // An empty AND is every book, an empty OR none: Group.children() empty, read by its join
        Group root = rootOf("{\"join\":\"or\",\"rules\":[{\"type\":\"group\",\"join\":\"and\",\"rules\":[]},"
                + "{\"type\":\"group\",\"join\":\"or\",\"rules\":[]}]}");

        assertThat(root.and()).isFalse();
        assertThat(root.children()).containsExactly(new Group(true, List.of()), new Group(false, List.of()));
    }

    @Test
    void nestsGroupsAsTheFilterNestsThem() {
        Group root = rootOf("{\"join\":\"and\",\"rules\":[" + rule("title", "equals") + ","
                + "{\"type\":\"group\",\"join\":\"or\",\"rules\":[" + rule("isbn13", "is_empty") + ","
                + "{\"type\":\"group\",\"join\":\"and\",\"rules\":[" + rule("tags", "includes_any") + "]}]}]}");

        assertThat(root.and()).isTrue();
        assertThat(root.children()).hasSize(2);
        assertThat(root.children().get(0)).isInstanceOf(Leaf.class);
        Group or = (Group) root.children().get(1);
        assertThat(or.and()).isFalse();
        assertThat(or.children().get(0)).isInstanceOf(Leaf.class);
        assertThat(((Group) or.children().get(1)).children()).singleElement().isInstanceOf(Leaf.class);
    }

    @Test
    void aRuleWithTypeGroupIsAGroupEvenWithAField() {
        // 'type' in rule && rule.type === 'group' comes first in evaluateGroup
        Group root = rootOf(shelf("{\"type\":\"group\",\"field\":\"title\",\"operator\":\"equals\",\"join\":\"and\",\"rules\":[]}"));

        assertThat(root.children()).containsExactly(new Group(true, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"join\":\"and\"}",
            "{\"join\":\"and\",\"rules\":{}}",
            "{\"join\":\"and\",\"rules\":\"title\"}",
            "{\"join\":\"and\",\"rules\":[{\"type\":\"group\",\"join\":\"and\"}]}"
    })
    void refusesAGroupWithoutARulesArray(String filterJson) {
        assertThat(compiler.compile(filterJson)).isEqualTo(new Unsupported(List.of("group:rules")));
    }

    @Test
    void refusesWhatTheEvaluatorWouldThrowOnOrReadAsNoField() {
        assertThat(compiler.compile(shelf("null"))).isEqualTo(new Unsupported(List.of("rule:null")));
        assertThat(compiler.compile(shelf("3"))).isEqualTo(new Unsupported(List.of("rule:null")));
        assertThat(compiler.compile(shelf("[]"))).isEqualTo(new Unsupported(List.of("rule:field")));
        assertThat(compiler.compile(shelf("{\"operator\":\"equals\",\"value\":\"a\"}"))).isEqualTo(new Unsupported(List.of("rule:field")));
        assertThat(compiler.compile(shelf("{\"field\":\"title\",\"value\":\"a\"}"))).isEqualTo(new Unsupported(List.of("rule:operator")));
    }

    @Test
    void refusesARootThatIsNoGroup() {
        assertThat(compiler.compile(null)).isEqualTo(new Unsupported(List.of("group:root")));
        assertThat(compiler.compile("null")).isEqualTo(new Unsupported(List.of("group:root")));
        assertThat(compiler.compile("[]")).isEqualTo(new Unsupported(List.of("group:root")));
        assertThat(compiler.compile("\"and\"")).isEqualTo(new Unsupported(List.of("group:root")));
    }

    @Test
    void refusesNestingPastTheDepthLimit() {
        String filterJson = "{\"join\":\"and\",\"rules\":[]}";
        for (int i = 0; i < 70; i++) {
            filterJson = "{\"type\":\"group\",\"join\":\"and\",\"rules\":[" + filterJson + "]}";
        }
        assertThat(compiler.compile(filterJson)).isEqualTo(new Unsupported(List.of("group:depth")));
    }

    // ------------------------------------------------------------------ invalid

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{", "not json", "{\"join\":\"and\",\"rules\":[]} trailing",
            "{\"join\":\"and\",\"rules\":[],}", "{'join':'and','rules':[]}", "\uFEFF{\"join\":\"and\",\"rules\":[]}"})
    void whatJsonParseRejectsIsInvalid(String filterJson) {
        assertThat(compiler.compile(filterJson)).isInstanceOf(Invalid.class);
    }

    // ------------------------------------------------------------------ fileType tests

    @Test
    void fileTypeTestsReadThePrimaryFileTypeAsTheUiDoes() {
        assertThat(fileTypeTest("equals", "\"CBZ\"").test("cbx")).isTrue();
        assertThat(fileTypeTest("equals", "\"azw\"").test("azw3")).isTrue();
        assertThat(fileTypeTest("equals", "\"epub\"").test(null)).isFalse();
        assertThat(fileTypeTest("not_equals", "\"epub\"").test(null)).isTrue();
        assertThat(fileTypeTest("not_equals", "\"epub\"").test("epub")).isFalse();
        assertThat(fileTypeTest("includes_any", "[\"pdf\",\"cbr\"]").test("cbx")).isTrue();
        assertThat(fileTypeTest("includes_any", "[\"pdf\",\"cbr\"]").test("epub")).isFalse();
        // Two different values can never both be the one primary type
        assertThat(fileTypeTest("includes_all", "[\"pdf\",\"epub\"]").test("pdf")).isFalse();
        assertThat(fileTypeTest("includes_all", "[\"cbr\",\"cbz\"]").test("cbx")).isTrue();
        assertThat(fileTypeTest("includes_all", "[]").test(null)).isTrue();
        assertThat(fileTypeTest("is_empty", "null").test(null)).isTrue();
        assertThat(fileTypeTest("is_not_empty", "null").test("pdf")).isTrue();
        assertThat(fileTypeTest("contains", "\"ub\"").test("epub")).isTrue();
        assertThat(fileTypeTest("does_not_contain", "\"ub\"").test(null)).isTrue();
        assertThat(fileTypeTest("starts_with", "\"AUDIO\"").test("audiobook")).isTrue();
        assertThat(fileTypeTest("ends_with", "\"3\"").test("azw3")).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private FileTypeTest fileTypeTest(String operator, String value) {
        Compiled compiled = compiler.compile(shelf("{\"field\":\"fileType\",\"operator\":\"" + operator + "\",\"value\":" + value + "}"));
        assertThat(compiled).isInstanceOf(Supported.class);
        return ((Supported) compiled).fileTypeTests().getFirst();
    }

    private Group rootOf(String filterJson) {
        Compiled compiled = compiler.compile(filterJson);
        assertThat(compiled).as(filterJson).isInstanceOf(Supported.class);
        return (Group) ((Supported) compiled).sql();
    }

    private static String shelf(String... rules) {
        return "{\"type\":\"group\",\"join\":\"and\",\"rules\":[" + String.join(",", rules) + "]}";
    }

    /** A rule with the value the shelf editor writes for this kind of field and operator. */
    private static String rule(String field, String operator) {
        String value;
        String extra = "";
        switch (operator) {
            case "includes_any", "excludes_all", "includes_all" -> value = field.equals("library") ? "[1,3]" : "[\"a\",\"b\"]";
            case "in_between" -> {
                value = "null";
                extra = field.equals("addedOn")
                        ? ",\"valueStart\":\"2026-01-01\",\"valueEnd\":\"2026-02-01\""
                        : ",\"valueStart\":10,\"valueEnd\":20";
            }
            case "within_last", "older_than" -> {
                value = "14";
                extra = ",\"valueEnd\":\"days\"";
            }
            case "this_period" -> value = "\"year\"";
            case "is_empty", "is_not_empty" -> value = "null";
            default -> value = switch (field) {
                case "library" -> "3";
                case "addedOn" -> "\"2026-01-01\"";
                case "isPhysical", "isComic", "titleChangedByAutoFetch" -> "\"true\"";
                case "readStatus" -> "\"READ\"";
                default -> NUMBER_FIELDS.contains(field) ? "50" : "\"a\"";
            };
        }
        return "{\"field\":\"" + field + "\",\"operator\":\"" + operator + "\",\"value\":" + value + extra + "}";
    }
}
