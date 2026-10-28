package org.booklore.service.catalog;

import org.booklore.service.catalog.JsSemantics.Kind;
import org.booklore.service.catalog.JsSemantics.Unit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The JavaScript conversions the compiler reproduces, each against what a browser gives. */
class JsSemanticsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String value) {
        return JSON.readTree(value);
    }

    @Test
    void stringToNumberFollowsNumberOfAString() {
        assertThat(JsSemantics.stringToNumber("50")).isEqualTo(50d);
        assertThat(JsSemantics.stringToNumber(" \t12.5\n")).isEqualTo(12.5);
        assertThat(JsSemantics.stringToNumber("")).isEqualTo(0d);
        assertThat(JsSemantics.stringToNumber("   ")).isEqualTo(0d);
        assertThat(JsSemantics.stringToNumber("1e3")).isEqualTo(1000d);
        assertThat(JsSemantics.stringToNumber(".5")).isEqualTo(0.5);
        assertThat(JsSemantics.stringToNumber("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY);
        // Lower-cased by normalize, "infinity" is no longer Infinity
        assertThat(JsSemantics.stringToNumber("infinity")).isNaN();
        assertThat(JsSemantics.stringToNumber("12abc")).isNaN();
        assertThat(JsSemantics.stringToNumber("true")).isNaN();
        // Hexadecimal, octal and binary literals are left to the client
        assertThat(JsSemantics.stringToNumber("0x10")).isNull();
        assertThat(JsSemantics.stringToNumber("0b1")).isNull();
    }

    @Test
    void toNumberOfANormalizedValue() {
        assertThat(JsSemantics.toNumber(JsSemantics.normalize(null))).isNaN();
        assertThat(JsSemantics.toNumber(JsSemantics.normalize(json("null")))).isEqualTo(0d);
        assertThat(JsSemantics.toNumber(JsSemantics.normalize(json("true")))).isNaN();
        assertThat(JsSemantics.toNumber(JsSemantics.normalize(json("70")))).isEqualTo(70d);
        assertThat(JsSemantics.toNumber(JsSemantics.normalize(json("[70]")))).isNull();
    }

    @Test
    void normalizeLowerCasesStringsAndWritesBooleansAsStrings() {
        assertThat(JsSemantics.normalize(json("\"Tom Clancy\""))).isEqualTo(JsSemantics.Norm.string("tom clancy"));
        assertThat(JsSemantics.normalize(json("true"))).isEqualTo(JsSemantics.Norm.string("true"));
        assertThat(JsSemantics.normalize(json("3")).kind()).isEqualTo(Kind.NUMBER);
        assertThat(JsSemantics.normalize(json("null")).isNullish()).isTrue();
        assertThat(JsSemantics.normalize(null).isNullish()).isTrue();
    }

    @Test
    void ruleListIsTheEvaluatorsList() {
        assertThat(JsSemantics.ruleList(json("[\"File Not Found\", 3, true, null]"), JsSemantics::lower))
                .containsExactly("file not found", "3", "true", "null");
        assertThat(JsSemantics.ruleList(json("\"Tom\""), JsSemantics::lower)).containsExactly("tom");
        // A falsy scalar is no list at all
        assertThat(JsSemantics.ruleList(json("\"\""), JsSemantics::lower)).isEmpty();
        assertThat(JsSemantics.ruleList(json("0"), JsSemantics::lower)).isEmpty();
        assertThat(JsSemantics.ruleList(null, JsSemantics::lower)).isEmpty();
        assertThat(JsSemantics.ruleList(json("[{\"a\":1}]"), JsSemantics::lower)).isNull();
    }

    @Test
    void numberToStringOnlyWhereJavaWritesItAsJavaScriptDoes() {
        assertThat(JsSemantics.numberToString(3)).isEqualTo("3");
        assertThat(JsSemantics.numberToString(-12)).isEqualTo("-12");
        assertThat(JsSemantics.numberToString(2.5)).isEqualTo("2.5");
        assertThat(JsSemantics.numberToString(1e21)).isNull();
        assertThat(JsSemantics.numberToString(1e-7)).isNull();
        assertThat(JsSemantics.numberToString(Double.NaN)).isNull();
    }

    @Test
    void jsTrimRemovesUnicodeSpacesToo() {
        assertThat(JsSemantics.trim("\u00A0\u3000 a b \uFEFF\t")).isEqualTo("a b");
        assertThat(JsSemantics.trim("\u200B")).isEqualTo("\u200B");
    }

    @Test
    void relativeDateUnits() {
        assertThat(JsSemantics.relativeDateUnit(null)).isEqualTo(Unit.DAYS);
        assertThat(JsSemantics.relativeDateUnit(json("null"))).isEqualTo(Unit.DAYS);
        assertThat(JsSemantics.relativeDateUnit(json("\"days\""))).isEqualTo(Unit.DAYS);
        assertThat(JsSemantics.relativeDateUnit(json("\"Weeks\""))).isEqualTo(Unit.WEEKS);
        // computeDateThreshold's default branch: an unknown unit counts days
        assertThat(JsSemantics.relativeDateUnit(json("\"fortnights\""))).isEqualTo(Unit.DAYS);
        assertThat(JsSemantics.relativeDateUnit(json("\"months\""))).isNull();
        assertThat(JsSemantics.relativeDateUnit(json("\"years\""))).isNull();
        assertThat(JsSemantics.relativeDateUnit(json("[\"days\"]"))).isNull();
    }

    @Test
    void relativeDateThresholdIsNowLessTheSpan() {
        Instant now = Instant.parse("2026-10-28T12:00:00Z");

        assertThat(JsSemantics.relativeDateThreshold(14, Unit.DAYS, now))
                .isEqualTo(Instant.parse("2026-10-14T12:00:00Z").toEpochMilli());
        assertThat(JsSemantics.relativeDateThreshold(2, Unit.WEEKS, now))
                .isEqualTo(Instant.parse("2026-10-14T12:00:00Z").toEpochMilli());
        assertThat(JsSemantics.relativeDateThreshold(0.5, Unit.DAYS, now))
                .isEqualTo(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli());
        // Invalid Date: nothing is within it
        assertThat(JsSemantics.relativeDateThreshold(Double.NaN, Unit.DAYS, now)).isNull();
        assertThat(JsSemantics.relativeDateThreshold(1e12, Unit.DAYS, now)).isNull();
    }

    @Test
    void parsesOnlyTheIsoFormsEveryEngineReadsAsUtc() {
        assertThat(JsSemantics.parseIsoDate("2026-01-01")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(JsSemantics.parseIsoDate("2026-01-01T10:00:00.250Z")).isEqualTo(Instant.parse("2026-01-01T10:00:00.250Z"));
        assertThat(JsSemantics.parseIsoDate("2026-01-01T10:00+02:00")).isEqualTo(Instant.parse("2026-01-01T08:00:00Z"));
        assertThat(JsSemantics.parseIsoDate("2026-01-01T10:00:00")).isNull();
        assertThat(JsSemantics.parseIsoDate("2026-02-30")).isNull();
        assertThat(JsSemantics.parseIsoDate("01/02/2026")).isNull();
    }

    @Test
    void mapsFileTypesAsMapFileTypeValueDoes() {
        assertThat(List.of("cbr", "cbz", "cb7", "azw", "epub", "m4b").stream().map(JsSemantics::mapFileType))
                .containsExactly("cbx", "cbx", "cbx", "azw3", "epub", "m4b");
    }

    @Test
    void floatExactNumbers() {
        assertThat(JsSemantics.isFloatExact(50)).isTrue();
        assertThat(JsSemantics.isFloatExact(59.5)).isTrue();
        assertThat(JsSemantics.isFloatExact(0.1)).isFalse();
        assertThat(JsSemantics.isFloatExact(59.99)).isFalse();
    }
}
