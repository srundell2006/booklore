package org.booklore.service.library.matching;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fileless book (one without any book_file row) as {@link ExistingBookMatcher} sees it: the identities its title
 * reads as, its authors and its series number. Built from plain projection rows, never from entities, so a rescan can
 * hold every fileless book of a large library at once.
 *
 * @param libraryPathId the library path the book belongs to, or null when it has none yet (a physical book)
 * @param hasCover      whether the book has a cover of its own, ebook or audiobook
 * @param title         the title as the metadata has it, for log lines
 * @param identities    the {@link TitleKey#identity}s the book is found under: its title, its title with its subtitle,
 *                      and the title without a metadata provider's "(Series, #N)" suffix
 * @param authors       its authors, parsed
 * @param seriesNumber  its place in its series when that is a whole number: the metadata's series number, else the
 *                      number of a "(Series, #N)" suffix; null otherwise
 * @param seriesPlace   its place in its series as given, fractions included: the metadata's series number, else the
 *                      number of a "(Series, #N)" suffix; null when it has neither. Where {@link #seriesNumber} is null
 *                      for 1.5, 2.5 and no number alike, this tells them apart
 */
public record ShellCandidate(long bookId,
                             Long libraryPathId,
                             boolean physical,
                             boolean hasCover,
                             String title,
                             Set<String> identities,
                             List<PersonName> authors,
                             Integer seriesNumber,
                             Float seriesPlace) {

    /**
     * "Storm Front (The Dresden Files, #1)": the series and number some metadata providers append to a title. The
     * series part must have a letter, so "Saga (#1)" is left alone.
     */
    private static final Pattern PROVIDER_SERIES = Pattern.compile(
            "^(.*?\\S)\\s*[(\\[]([^()\\[\\]]*\\p{L}[^()\\[\\]]*?),?\\s*#\\s*(\\d+(?:\\.\\d+)?)\\s*[)\\]]$");

    public ShellCandidate {
        identities = Set.copyOf(identities);
        authors = List.copyOf(authors);
    }

    public static ShellCandidate of(long bookId, Long libraryPathId, boolean physical, boolean hasCover, String title,
                                    String subtitle, Float seriesNumber, Collection<String> authorNames) {
        List<String> names = authorNames == null ? List.of() : authorNames.stream().filter(Objects::nonNull).toList();
        List<PersonName> authors = names.stream().map(PersonName::parse).filter(Objects::nonNull).toList();
        // A title such as "The Hobbit - J.R.R. Tolkien" reads without the author part only when it is this book's author
        NameContext context = NameContext.NONE.withAuthors(names);

        String fullTitle = title == null ? "" : title.trim();
        String cleanTitle = fullTitle;
        Float suffixPlace = null;
        Matcher provider = PROVIDER_SERIES.matcher(fullTitle);
        if (provider.matches()) {
            cleanTitle = provider.group(1);
            suffixPlace = Float.parseFloat(provider.group(3));
        }

        Set<String> identities = new LinkedHashSet<>();
        identities.add(TitleKey.ofTitle(cleanTitle, context).identity());
        identities.add(TitleKey.ofTitle(fullTitle, context).identity());
        if (subtitle != null && !subtitle.isBlank()) {
            identities.add(TitleKey.ofTitle(cleanTitle + ": " + subtitle.trim(), context).identity());
        }
        identities.remove("");

        Integer number = wholeNumber(seriesNumber);
        return new ShellCandidate(bookId, libraryPathId, physical, hasCover, fullTitle, identities, authors,
                number != null ? number : wholeNumber(suffixPlace), seriesNumber != null ? seriesNumber : suffixPlace);
    }

    /** 3 for 3.0; null for 2.5 (a novella between two volumes), a negative number or none. */
    private static Integer wholeNumber(Float value) {
        if (value == null || value < 0 || value != Math.floor(value) || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }
}
