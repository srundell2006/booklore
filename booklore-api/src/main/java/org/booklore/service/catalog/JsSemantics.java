package org.booklore.service.catalog;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JavaScript conversions {@code book-rule-evaluator.service.ts} applies to rule values, reproduced exactly where
 * the result does not depend on the browser, and refused (null) where it would.
 */
final class JsSemantics {

    /** {@code String.prototype.trim} removes these: WhiteSpace and LineTerminator. */
    static final String WHITESPACE = "\t\n\u000B\f\r   "
            + "           "
            + "    　﻿";

    /** {@code new Date(ms)} is Invalid Date outside this range (TimeClip). */
    private static final double MAX_TIME_MS = 8.64e15;

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Pattern NON_DECIMAL = Pattern.compile("0[xXoObB].*");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern ISO_INSTANT = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{3}))?)?(Z|[+-]\\d{2}:\\d{2})");

    private JsSemantics() {
    }

    enum Kind {MISSING, NULL, STRING, NUMBER, DATE, ARRAY, OBJECT}

    /**
     * A rule value after the evaluator's {@code normalize}: a string is lower-cased, a boolean becomes the string
     * {@code "true"} or {@code "false"}, numbers stay numbers. For a date field a string that parses becomes a date.
     */
    record Norm(Kind kind, String string, double number, Instant date) {
        static final Norm MISSING = new Norm(Kind.MISSING, null, Double.NaN, null);
        static final Norm NULL = new Norm(Kind.NULL, null, Double.NaN, null);
        static final Norm ARRAY = new Norm(Kind.ARRAY, null, Double.NaN, null);
        static final Norm OBJECT = new Norm(Kind.OBJECT, null, Double.NaN, null);

        static Norm string(String s) {
            return new Norm(Kind.STRING, s, Double.NaN, null);
        }

        static Norm number(double n) {
            return new Norm(Kind.NUMBER, null, n, null);
        }

        static Norm date(Instant d) {
            return new Norm(Kind.DATE, null, Double.NaN, d);
        }

        boolean isString() {
            return kind == Kind.STRING;
        }

        /** {@code value == null} in the evaluator: null or undefined. */
        boolean isNullish() {
            return kind == Kind.MISSING || kind == Kind.NULL;
        }
    }

    static boolean isMissing(JsonNode node) {
        return node == null || node.isMissingNode();
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** {@code normalize(rule.value)} for a field that is not a date. */
    static Norm normalize(JsonNode node) {
        if (isMissing(node)) return Norm.MISSING;
        if (node.isNull()) return Norm.NULL;
        if (node.isBoolean()) return Norm.string(String.valueOf(node.booleanValue()));
        if (node.isString()) return Norm.string(lower(node.stringValue()));
        if (node.isNumber()) return Norm.number(node.doubleValue());
        if (node.isArray()) return Norm.ARRAY;
        return Norm.OBJECT;
    }

    /**
     * {@code normalize(rule.value)} for a date field: a string becomes {@code new Date(string)} when that is valid.
     * Only the ISO forms every engine reads alike, as UTC, are taken; any other string returns null, because whether
     * and how it parses depends on the browser (an ISO date-time without an offset is local time).
     */
    static Norm normalizeDate(JsonNode node) {
        if (node != null && node.isString()) {
            Instant date = parseIsoDate(node.stringValue());
            return date == null ? null : Norm.date(date);
        }
        return normalize(node);
    }

    static Instant parseIsoDate(String s) {
        try {
            Matcher m = ISO_DATE.matcher(s);
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            m = ISO_INSTANT.matcher(s);
            if (m.matches()) {
                LocalDateTime local = LocalDateTime.of(
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                        m.group(6) == null ? 0 : Integer.parseInt(m.group(6)),
                        m.group(7) == null ? 0 : Integer.parseInt(m.group(7)) * 1_000_000);
                ZoneOffset offset = "Z".equals(m.group(8)) ? ZoneOffset.UTC : ZoneOffset.of(m.group(8));
                return local.toInstant(offset);
            }
        } catch (DateTimeException e) {
            // Out-of-range fields: engines disagree on whether they roll over or are invalid
        }
        return null;
    }

    /**
     * {@code Number(ruleVal)} for a normalized value: NaN when JavaScript gives NaN, null when the conversion is left
     * to the client (arrays, objects, hex, octal and binary literals).
     */
    static Double toNumber(Norm value) {
        return switch (value.kind()) {
            case MISSING -> Double.NaN;
            case NULL -> 0d;
            case STRING -> stringToNumber(value.string());
            case NUMBER -> value.number();
            case DATE -> (double) value.date().toEpochMilli();
            case ARRAY, OBJECT -> null;
        };
    }

    /** {@code Number(rule.value)} on the raw value, as the relative-date operators read their amount. */
    static Double rawToNumber(JsonNode node) {
        if (isMissing(node)) return Double.NaN;
        if (node.isNull()) return 0d;
        if (node.isBoolean()) return node.booleanValue() ? 1d : 0d;
        if (node.isNumber()) return node.doubleValue();
        if (node.isString()) return stringToNumber(node.stringValue());
        return null;
    }

    /** JavaScript's StringToNumber. Infinity is case-sensitive there, so a lower-cased "infinity" is NaN. */
    static Double stringToNumber(String s) {
        String t = trim(s);
        if (t.isEmpty()) return 0d;
        switch (t) {
            case "Infinity", "+Infinity" -> {
                return Double.POSITIVE_INFINITY;
            }
            case "-Infinity" -> {
                return Double.NEGATIVE_INFINITY;
            }
            default -> {
            }
        }
        if (DECIMAL.matcher(t).matches()) return Double.parseDouble(t);
        if (NON_DECIMAL.matcher(t).matches()) return null;
        return Double.NaN;
    }

    static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && WHITESPACE.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && WHITESPACE.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    /** JavaScript truthiness of a raw rule value. */
    static boolean truthy(JsonNode node) {
        if (isMissing(node) || node.isNull()) return false;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) {
            double d = node.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (node.isString()) return !node.stringValue().isEmpty();
        return true;
    }

    /**
     * The evaluator's {@code ruleList}: each element of an array value through {@code String(v)} and the field's
     * mapping, or a truthy scalar as a one-element list, otherwise empty. Null when an element's {@code String(v)} is
     * left to the client.
     */
    static List<String> ruleList(JsonNode value, UnaryOperator<String> mapping) {
        List<String> list = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode element : value.values()) {
                String s = jsString(element);
                if (s == null) return null;
                list.add(mapping.apply(s));
            }
            return list;
        }
        if (!truthy(value)) return list;
        String s = jsString(value);
        if (s == null) return null;
        list.add(mapping.apply(s));
        return list;
    }

    /** Whether {@link #ruleList} is empty, which needs no conversion of its elements. */
    static boolean ruleListIsEmpty(JsonNode value) {
        return value != null && value.isArray() ? value.size() == 0 : !truthy(value);
    }

    /** {@code String(v)}, or null when it is left to the client (nested arrays and objects, unusual numbers). */
    static String jsString(JsonNode node) {
        if (node.isString()) return node.stringValue();
        if (node.isBoolean()) return String.valueOf(node.booleanValue());
        if (node.isNull()) return "null";
        if (node.isNumber()) return numberToString(node.doubleValue());
        return null;
    }

    /**
     * {@code String(n)} for integers below 2^53 and for other numbers between 0.001 and 10^7, where Java's shortest
     * representation is written the way JavaScript writes it; null otherwise.
     */
    static String numberToString(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        if (d == Math.rint(d)) {
            return Math.abs(d) < 0x1p53 ? Long.toString((long) d) : null;
        }
        double abs = Math.abs(d);
        return abs >= 1e-3 && abs < 1e7 ? Double.toString(d) : null;
    }

    /** {@code mapFileTypeValue}: the rule's file type as the list reports a primary file's {@code bookType}. */
    static String mapFileType(String lowerValue) {
        return switch (lowerValue) {
            case "cbr", "cbz", "cb7" -> "cbx";
            case "azw" -> "azw3";
            default -> lowerValue;
        };
    }

    enum Unit {DAYS, WEEKS}

    /**
     * {@code String(rule.valueEnd ?? 'days').toLowerCase()} as {@code computeDateThreshold} reads it. Months and years
     * use the browser's calendar and time zone, so they return null, as does an array or object.
     */
    static Unit relativeDateUnit(JsonNode valueEnd) {
        if (isMissing(valueEnd) || valueEnd.isNull()) return Unit.DAYS;
        if (valueEnd.isArray() || valueEnd.isObject()) return null;
        if (!valueEnd.isString()) return Unit.DAYS;
        return switch (lower(valueEnd.stringValue())) {
            case "weeks" -> Unit.WEEKS;
            case "months", "years" -> null;
            default -> Unit.DAYS;
        };
    }

    /**
     * {@code computeDateThreshold(amount, unit).getTime()} at {@code now}, with the same floating-point steps, or null
     * where JavaScript makes it an Invalid Date (a NaN amount, or past the Date range), which matches nothing.
     */
    static Long relativeDateThreshold(double amount, Unit unit, Instant now) {
        double span = unit == Unit.WEEKS
                ? amount * 7 * 24 * 60 * 60 * 1000
                : amount * 24 * 60 * 60 * 1000;
        double time = (double) now.toEpochMilli() - span;
        if (Double.isNaN(time) || Math.abs(time) > MAX_TIME_MS) return null;
        return (long) time;
    }

    /** {@code (double)(float) r == r} and the float's shortest text reads back as r: see the compiler's FLOAT note. */
    static boolean isFloatExact(double r) {
        float f = (float) r;
        return (double) f == r && Double.parseDouble(Float.toString(f)) == r;
    }
}
