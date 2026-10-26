package org.booklore.service.library.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fileless book (one without any book_file row) as {@link ExistingBookMatcher} sees it: the identities its title
 * reads as, the years it gives, its authors and its series. Built from plain projection rows, never from entities, so
 * a rescan can hold every fileless book of a large library at once.
 *
 * @param libraryPathId the library path the book belongs to, or null when it has none yet (a physical book)
 * @param hasCover      whether the book has a cover of its own, ebook or audiobook
 * @param title         the title as the metadata has it, for log lines
 * @param identities    the {@link TitleKey#identity}s the book is found under: its title, its title with its subtitle,
 *                      and the title without a metadata provider's "(Series, #N)" suffix; each read with its authors
 *                      as {@link #authors} has them and as {@link #authorsAsStored} has them, since a title's author
 *                      part is dropped only when it names one of them ("Child of a Dead God - John Hendee" by "Barb
 *                      Hendee; John Hendee" is "Child of a Dead God" only with the name read apart)
 * @param identitiesAsStored the identities its titles read as with its authors as stored, as the matcher read them
 *                      before it read the names apart: the reading the weak rule yields to. The same set as {@link
 *                      #identities} when they read alike, which most do
 * @param years         the publication years its titles give in brackets ("Rick Steves Italy (2020)"), as
 *                      {@link TitleKey#years} reads them; no identity holds them, so they alone tell two editions apart
 * @param authors       its authors, each stored name read as {@link PersonName#parseAuthor} reads it: one name that
 *                      packs several ("Arthur C. Clarke;Gentry Lee") is each of them
 * @param authorsAsStored its authors with each stored name read as one person ({@link PersonName#parse}), as the
 *                      matcher read them before it read them apart: the reading the weak rule yields to ({@link
 *                      ExistingBookMatcher.Match#weak}). The same list as {@link #authors} when they read alike, which
 *                      most do
 * @param seriesNumber  its place in its series when that is a whole number: the metadata's series number, else the
 *                      number of a "(Series, #N)" suffix; null otherwise
 * @param seriesPlace   its place in its series as given, fractions included: the metadata's series number, else the
 *                      number of a "(Series, #N)" suffix; null when it has neither. Where {@link #seriesNumber} is null
 *                      for 1.5, 2.5 and no number alike, this tells them apart
 * @param seriesName    the name of its series as given: the metadata's series name, else the series of a "(Series,
 *                      #N)" suffix ("Storm Front (The Dresden Files, #1)"); null when it has neither. What the matcher
 *                      takes the series to be called is {@link #seriesIdentities}
 * @param seriesIdentities the {@link TitleKey#identity}s the names of its series read as when read as a title: as each
 *                      is written, and without a last word such as "Series" or "Trilogy" ({@link
 *                      TitleWords#SERIES_NOUNS}) when it ends in one. The names are {@link #seriesName}, and the series
 *                      of a "(Series, #N)" suffix that gives the book's place ({@link #seriesPlace}), which is a place
 *                      in that series whatever the metadata calls its own: "Saga (Saga, #2)" is number 2 of a series
 *                      "Saga" also when its series name is "Image Comics". Empty when the series has no name, or one
 *                      that reads as nothing: then nothing says what its series is called
 * @param volumeNumbers the volume numbers its titles name after a series keyword, as {@link TitleKey#keywordNumbers}
 *                      reads them ("Saga: Volume Two" names 2), in order and each once. A bare number is none: "Unbroken:
 *                      A World War II Story" and "The Boys in the Boat: ... the 1936 Berlin Olympics" name no volume.
 *                      Nor is the number of a "(Series, #N)" suffix: it is the book's place in that series, judged
 *                      as {@link #seriesPlace}, unless the metadata's series number is another
 */
public record ShellCandidate(long bookId,
                             Long libraryPathId,
                             boolean physical,
                             boolean hasCover,
                             String title,
                             Set<String> identities,
                             Set<String> identitiesAsStored,
                             Set<Integer> years,
                             List<PersonName> authors,
                             List<PersonName> authorsAsStored,
                             Integer seriesNumber,
                             Float seriesPlace,
                             String seriesName,
                             Set<String> seriesIdentities,
                             List<String> volumeNumbers) {

    /**
     * "Storm Front (The Dresden Files, #1)": the series and number some metadata providers append to a title. The
     * series part must have a letter, so "Saga (#1)" is left alone.
     */
    private static final Pattern PROVIDER_SERIES = Pattern.compile(
            "^(.*?\\S)\\s*[(\\[]([^()\\[\\]]*\\p{L}[^()\\[\\]]*?),?\\s*#\\s*(\\d+(?:\\.\\d+)?)\\s*[)\\]]$");

    public ShellCandidate {
        identities = Set.copyOf(identities);
        // Most books' titles read alike either way: one set then, not two
        identitiesAsStored = identitiesAsStored.equals(identities) ? identities : Set.copyOf(identitiesAsStored);
        years = Set.copyOf(years);
        authors = List.copyOf(authors);
        // Most books' names read alike either way: one list then, not two
        authorsAsStored = authorsAsStored.equals(authors) ? authors : List.copyOf(authorsAsStored);
        seriesIdentities = Set.copyOf(seriesIdentities);
        volumeNumbers = List.copyOf(volumeNumbers);
    }

    /**
     * @param seriesName   the metadata's series name; blank is none
     * @param seriesNumber the metadata's series number
     */
    public static ShellCandidate of(long bookId, Long libraryPathId, boolean physical, boolean hasCover, String title,
                                    String subtitle, String seriesName, Float seriesNumber, Collection<String> authorNames) {
        List<String> names = authorNames == null ? List.of() : authorNames.stream().filter(Objects::nonNull).toList();
        List<PersonName> authors = PersonName.parseAuthors(names);
        List<PersonName> asStored = names.stream().map(PersonName::parse).filter(Objects::nonNull).toList();
        // A title such as "The Hobbit - J.R.R. Tolkien" reads without the author part only when it is this book's author
        NameContext context = NameContext.NONE.withAuthors(names);
        NameContext contextAsStored = NameContext.NONE.withPersons(asStored);

        String fullTitle = title == null ? "" : title.trim();
        String cleanTitle = fullTitle;
        Float suffixPlace = null;
        String suffixSeries = null;
        Matcher provider = PROVIDER_SERIES.matcher(fullTitle);
        if (provider.matches()) {
            cleanTitle = provider.group(1);
            suffixSeries = provider.group(2).trim();
            suffixPlace = Float.parseFloat(provider.group(3));
        }
        List<String> titles = new ArrayList<>();
        titles.add(cleanTitle);
        titles.add(fullTitle);
        if (subtitle != null && !subtitle.isBlank()) {
            titles.add(cleanTitle + ": " + subtitle.trim());
        }

        // The "#2" of "(The Handmaid's Tale, #2)" is the book's place in that series, not a volume its title names,
        // unless the metadata gives it another place
        boolean suffixIsPlace = suffixPlace != null && (seriesNumber == null || seriesNumber.equals(suffixPlace));
        Set<String> identitiesAsStored = new LinkedHashSet<>();
        Set<String> identities = new LinkedHashSet<>();
        Set<Integer> years = new LinkedHashSet<>();
        Set<String> volumeNumbers = new LinkedHashSet<>();
        for (int i = 0; i < titles.size(); i++) {
            // As stored first, as before the names were read apart, then read apart, which may drop an author part
            // that the names as stored did not
            addTitle(TitleKey.ofTitle(titles.get(i), contextAsStored), i == 1 && suffixIsPlace, identitiesAsStored, years,
                    volumeNumbers);
            if (!authors.equals(asStored)) {
                    addTitle(TitleKey.ofTitle(titles.get(i), context), i == 1 && suffixIsPlace, identities, years,
                        volumeNumbers);
            }
        }
        identities.addAll(identitiesAsStored);

        String series = seriesName != null && !seriesName.isBlank() ? seriesName.trim() : suffixSeries;
        // What its series is called is read with the names as stored, as before: the " (1)" guard it serves never
        // turns down a book that it did not turn down then
        Set<String> seriesIdentities = new LinkedHashSet<>();
        addSeriesIdentities(series, contextAsStored, seriesIdentities);
        if (suffixIsPlace) {
            // The suffix's place is in the suffix's series: "Saga (Saga, #2)" is number 2 of a series "Saga", whatever
            // other series the metadata names
            addSeriesIdentities(suffixSeries, contextAsStored, seriesIdentities);
        }

        Integer number = wholeNumber(seriesNumber);
        return new ShellCandidate(bookId, libraryPathId, physical, hasCover, fullTitle, identities, identitiesAsStored,
                years, authors, asStored, number != null ? number : wholeNumber(suffixPlace), seriesNumber != null ? seriesNumber : suffixPlace,
                series, seriesIdentities, List.copyOf(volumeNumbers));
    }

    /**
     * Whether its stored author names read apart are other authors than each read as one person: a name packs
     * several ("Arthur C. Clarke;Gentry Lee"), or holds an HTML entity or a part in brackets.
     */
    public boolean readsAuthorsApart() {
        return authorsAsStored != authors && !authorsAsStored.equals(authors);
    }

    /**
     * Adds the identity a title reads as, and the years and volume numbers it gives.
     *
     * @param placeSuffix whether the title ends in a "(Series, #N)" suffix that gives the book's place, whose number is
     *                    no volume its title names
     */
    private static void addTitle(TitleKey key, boolean placeSuffix, Set<String> identities, Set<Integer> years,
                                 Set<String> volumeNumbers) {
        if (key.identity().isEmpty()) {
            return;
        }
        identities.add(key.identity());
        years.addAll(key.years());
        if (!placeSuffix) {
            volumeNumbers.addAll(key.keywordNumbers());
        }
    }

    /** Adds the identities a series name reads as when read as a title, as {@link #seriesIdentities} has them. */
    private static void addSeriesIdentities(String series, NameContext context, Set<String> seriesIdentities) {
        if (series == null) {
            return;
        }
        TitleKey key = TitleKey.ofTitle(series, context);
        if (!key.identity().isEmpty()) {
            seriesIdentities.add(key.identity());
            String withoutNoun = key.identityWithoutLastWord(TitleWords.SERIES_NOUNS);
            if (withoutNoun != null) {
                seriesIdentities.add(withoutNoun);
            }
        }
    }

    /** 3 for 3.0; null for 2.5 (a novella between two volumes), a negative number or none. */
    private static Integer wholeNumber(Float value) {
        if (value == null || value < 0 || value != Math.floor(value) || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }
}
