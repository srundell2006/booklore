package org.booklore.service.library.matching;

import org.apache.commons.text.StringEscapeUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A person's name reduced to what two spellings of it share: the surname, and the given names or their initials in
 * order. "Brown, Dale" and "Dale Brown" agree, as do "J.R.R. Tolkien" and "Tolkien, John Ronald Reuel", "Ursula K.
 * Le Guin" and "Le Guin, Ursula". "A. C. Arthur" and "A.M. Arthur" do not: their second initials differ.
 * <p>
 * Parsing is deliberately permissive: a series folder such as "Rogue Angel" parses as a name too. What keeps it
 * safe is that a name only ever counts when it agrees with another name the caller already trusts, such as one of
 * the file's folders or an author a book really has.
 *
 * @param surname    the last word, lower case and without accents ("guin" for "Le Guin")
 * @param givenNames the words before it, in order, without a name particle that belongs to the surname ("le")
 */
public record PersonName(String surname, List<String> givenNames) {

    private static final Set<String> SUFFIXES = Set.of("jr", "sr", "ii", "iii", "iv", "phd", "md", "dr", "mr", "mrs", "ms");

    /** Words that belong to the surname they come before ("Le Guin", "van Gogh", "de la Cruz"). */
    private static final Set<String> PARTICLES = Set.of(
            "de", "del", "della", "der", "den", "di", "da", "du", "la", "le", "van", "von", "ten", "ter");

    /**
     * Separators between several names in one string. Not " and ": folder and title words use it far more often
     * than author lists do ("Foundation and Empire"), while Calibre joins authors with " & ".
     */
    private static final Pattern MULTI = Pattern.compile("\\s*[;&]\\s*");
    /** A part in brackets, such as a role ("(Narrator)", "(ed.)"); one never closed runs to the end. */
    private static final Pattern BRACKETED = Pattern.compile("\\([^)]*\\)?|\\[[^\\]]*]?");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['‘’`]");
    private static final Pattern NON_LETTER = Pattern.compile("[^\\p{L}\\p{N}]+");

    public PersonName {
        givenNames = List.copyOf(givenNames);
    }

    /** Every name in a string such as "Lora Leigh; Michelle Rowan & Ava Gray" or "Aarons, Edward S". */
    public static List<PersonName> parseAll(String s) {
        List<PersonName> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String part : MULTI.split(s)) {
            PersonName name = parse(part);
            if (name != null) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * The authors one stored author name stands for, in order and each once. A book's authors are stored one name
     * each, but some names hold several: "Arthur C. Clarke;Gentry Lee" is Clarke and Lee, where {@link #parse} reads
     * one person named Lee. HTML entities are read ({@code P&eacute;ter} is Péter), and a part in brackets is left out
     * ("Lindsey Priestley (Edited by)", "Kenneth (Lester Dent & Evelyn Coulson) Robeson"). A name with a bracket also
     * stands as written, since an author folder is often named after it: "Alfred Hitchcock (ed.)" is Alfred Hitchcock
     * and, as {@link #parse} reads it, a person named Ed.
     * <p>
     * A name splits at ';' and '&' only when every part is a full name, with a given name or an initial, or no name at
     * all, an honorific such as "M.D." or "Jr." ("Louann Brizendine; M.D."). Otherwise it is one name, as {@link #parse}
     * reads it: "Grosset & Dunlap", or "Rachel; Levithan Cohn", a garbled Rachel Cohn and David Levithan that the folder
     * "Rachel Cohn" agrees with, and "Brian K.; Staples Vaughan" or "E. & M.A. Radford", whose first part is given
     * names without a surname.
     */
    public static List<PersonName> parseAuthor(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        String decoded = stored.indexOf('&') >= 0 ? StringEscapeUtils.unescapeHtml4(stored) : stored;
        String s = BRACKETED.matcher(decoded).replaceAll(" ");
        Set<PersonName> names = new LinkedHashSet<>(namesIn(s));
        if (!s.equals(decoded)) {
            // An author folder is often named as the book has it, bracket included: "Alfred Hitchcock (ed.)"
            PersonName asWritten = parse(decoded);
            if (asWritten != null) {
                names.add(asWritten);
            }
        }
        return List.copyOf(names);
    }

    /** The names in a stored author name without brackets: its parts when all are full names, else the whole. */
    private static List<PersonName> namesIn(String s) {
        List<String> parts = Arrays.stream(MULTI.split(s)).filter(part -> !part.isBlank()).toList();
        if (parts.isEmpty()) {
            return List.of();
        }
        if (parts.size() > 1) {
            List<PersonName> names = new ArrayList<>();
            boolean allFull = true;
            for (String part : parts) {
                PersonName name = parse(part);
                if (name == null) {
                    // "M.D." of "Louann Brizendine; M.D." is no name at all; "Brian K." of "Brian K.; Staples Vaughan"
                    // is no full name, as a surname alone is not ("Rachel" of "Rachel; Levithan Cohn")
                    List<String> words = tokens(part);
                    allFull &= words.isEmpty() || SUFFIXES.contains(String.join("", words));
                } else {
                    allFull &= name.hasGivenName();
                    names.add(name);
                }
            }
            if (allFull && !names.isEmpty()) {
                return names;
            }
        }
        PersonName name = parts.size() == 1 ? parse(parts.getFirst()) : parse(s);
        return name == null ? List.of() : List.of(name);
    }

    /** The authors stored names stand for, as {@link #parseAuthor} reads each, in order and each once. */
    public static List<PersonName> parseAuthors(Collection<String> stored) {
        if (stored == null) {
            return List.of();
        }
        Set<PersonName> names = new LinkedHashSet<>();
        for (String name : stored) {
            names.addAll(parseAuthor(name));
        }
        return List.copyOf(names);
    }

    /** One name, "Last, First M" or "First M. Last"; null when no surname of two or more letters is left. */
    public static PersonName parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        int comma = s.indexOf(',');
        List<String> surnamePart;
        List<String> givenPart;
        if (comma >= 0 && s.indexOf(',', comma + 1) < 0) {
            surnamePart = tokens(s.substring(0, comma));
            givenPart = tokens(s.substring(comma + 1));
        } else {
            List<String> all = tokens(s);
            if (all.isEmpty()) {
                return null;
            }
            surnamePart = List.of(all.getLast());
            givenPart = all.subList(0, all.size() - 1);
        }
        if (surnamePart.isEmpty()) {
            return null;
        }
        String surname = surnamePart.getLast();
        if (surname.length() < 2) {
            return null;
        }
        // "Ursula K. Le Guin" and "Beauvoir, Simone de": the particle is the surname's, not a given name
        int end = givenPart.size();
        while (end > 0 && PARTICLES.contains(givenPart.get(end - 1))) {
            end--;
        }
        return new PersonName(surname, givenPart.subList(0, end));
    }

    /** Lower case words without accents or apostrophes, and without suffixes such as "Jr" or "PhD". */
    private static List<String> tokens(String s) {
        String n = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFKD)).replaceAll("").toLowerCase(Locale.ROOT);
        n = NON_LETTER.matcher(APOSTROPHES.matcher(n).replaceAll("")).replaceAll(" ").trim();
        if (n.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(n.split(" ")).filter(t -> !SUFFIXES.contains(t)).toList();
    }

    /** True when the name has a given name or an initial, not only a surname. */
    public boolean hasGivenName() {
        return !givenNames.isEmpty();
    }

    /**
     * How well two names agree: 2 when the surnames are equal and so are the given names both have, each the same
     * word or one the other's initial; 1 when the surnames are equal and one side has no given name; 0 otherwise.
     */
    public int strength(PersonName other) {
        if (other == null || !surname.equals(other.surname)) {
            return 0;
        }
        if (givenNames.isEmpty() || other.givenNames.isEmpty()) {
            return 1;
        }
        int shared = Math.min(givenNames.size(), other.givenNames.size());
        for (int i = 0; i < shared; i++) {
            if (!compatible(givenNames.get(i), other.givenNames.get(i))) {
                return 0;
            }
        }
        return 2;
    }

    private static boolean compatible(String a, String b) {
        return a.equals(b) || (a.length() == 1 && b.startsWith(a)) || (b.length() == 1 && a.startsWith(b));
    }

    /** The best agreement between any name of one list and any of the other. */
    public static int bestStrength(Collection<PersonName> a, Collection<PersonName> b) {
        int best = 0;
        for (PersonName x : a) {
            for (PersonName y : b) {
                best = Math.max(best, x.strength(y));
            }
        }
        return best;
    }
}
