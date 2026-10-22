package org.booklore.service.library.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What is known about a name before reading it: the names its folders could be, the authors of the book it is
 * being compared with, and its own folder. {@link TitleKey} only drops a part of a name as an author when that part
 * agrees with one of these; how a part looks (capital letters, two words after a dash) is never enough on its own.
 */
public final class NameContext {

    /** No folders and no authors: nothing can be confirmed as an author except by initials. */
    public static final NameContext NONE = new NameContext(List.of(), List.of(), "");

    /** "American Gods (123)": the id Calibre appends to a book's folder. */
    private static final Pattern CALIBRE_ID = Pattern.compile("\\s*\\(\\d+\\)\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("[/\\\\]");

    private final List<PersonName> folderNames;
    private final List<PersonName> authors;
    private final String leafKey;

    private NameContext(List<PersonName> folderNames, List<PersonName> authors, String leafKey) {
        this.folderNames = folderNames;
        this.authors = authors;
        this.leafKey = leafKey;
    }

    /**
     * The context a file's sub path gives: every folder segment as a possible name, except alphabetical index folders
     * such as "A" or "0-9", and the file's own folder for {@link #isOwnFolder}.
     */
    public static NameContext forFolder(String fileSubPath) {
        if (fileSubPath == null || fileSubPath.isBlank()) {
            return NONE;
        }
        List<PersonName> names = new ArrayList<>();
        String leaf = "";
        for (String rawSegment : SEPARATOR.split(fileSubPath)) {
            String segment = CALIBRE_ID.matcher(rawSegment).replaceAll("").trim();
            if (segment.isEmpty()) {
                continue;
            }
            leaf = segment;
            String key = TitleKey.normalize(segment);
            if (key.length() < 2 || key.chars().allMatch(c -> Character.isDigit(c) || c == ' ')) {
                continue;
            }
            names.addAll(PersonName.parseAll(segment));
        }
        return new NameContext(List.copyOf(names), List.of(), TitleKey.normalize(leaf));
    }

    /** This context plus the authors of the book a name is compared with (a fileless book's, say). */
    public NameContext withAuthors(Collection<String> authorNames) {
        if (authorNames == null || authorNames.isEmpty()) {
            return this;
        }
        List<PersonName> parsed = new ArrayList<>(authors);
        for (String name : authorNames) {
            PersonName person = PersonName.parse(name);
            if (person != null) {
                parsed.add(person);
            }
        }
        return new NameContext(folderNames, List.copyOf(parsed), leafKey);
    }

    /** The names the file's folders could be, for a caller checking a book's authors against them. */
    public List<PersonName> folderNames() {
        return folderNames;
    }

    /**
     * True when {@code text} names a person with a given name or an initial who agrees with a folder name or a known
     * author. A surname on its own is not enough: "KING - Las Vegas Bad Boys" in a folder "Stephen King" is not by
     * Stephen King, and "Dune - Herbert" stays one title rather than risk reading a title word as a surname.
     */
    boolean confirms(String text) {
        List<PersonName> names = PersonName.parseAll(text).stream().filter(PersonName::hasGivenName).toList();
        return PersonName.bestStrength(names, folderNames) > 0 || PersonName.bestStrength(names, authors) > 0;
    }

    /** True when {@code text} names the file's own folder: then it is the book's title or series, not its author. */
    boolean isOwnFolder(String text) {
        return !leafKey.isEmpty() && leafKey.equals(TitleKey.normalize(text));
    }
}
