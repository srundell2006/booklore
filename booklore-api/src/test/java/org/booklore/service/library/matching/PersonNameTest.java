package org.booklore.service.library.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNameTest {

    @ParameterizedTest(name = "{0} -> {1}, {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            Dale Brown            | brown   | dale
            Brown, Dale           | brown   | dale
            Aarons, Edward S      | aarons  | edward s
            Edward S. Aarons      | aarons  | edward s
            J.R.R. Tolkien        | tolkien | j r r
            Tolkien, J.R.R.       | tolkien | j r r
            Ursula K. Le Guin     | guin    | ursula k
            Le Guin, Ursula K.    | guin    | ursula k
            Le Guin               | guin    | ""
            Charlotte Brontë      | bronte  | charlotte
            Martin Luther King Jr. | king   | martin luther
            Tolkien               | tolkien | ""
            """)
    void readsTheSurnameAndTheGivenNamesInOrder(String name, String surname, String given) {
        PersonName person = PersonName.parse(name);

        assertThat(person.surname()).isEqualTo(surname);
        assertThat(person.givenNames()).isEqualTo(given.isEmpty() ? List.of() : List.of(given.split(" ")));
    }

    @Test
    void readsNoNameWithoutASurnameOfTwoLetters() {
        assertThat(PersonName.parse("U.S.A.")).isNull();
        assertThat(PersonName.parse("  ")).isNull();
        assertThat(PersonName.parse(null)).isNull();
    }

    @Test
    void splitsSeveralNamesOnSemicolonsAndAmpersandsOnly() {
        assertThat(PersonName.parseAll("Lora Leigh; Michelle Rowan & Ava Gray")).extracting(PersonName::surname)
                .containsExactly("leigh", "rowan", "gray");
        assertThat(PersonName.parseAll("Foundation and Empire")).hasSize(1);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            Arthur C. Clarke;Gentry Lee                         | clarke arthur c; lee gentry
            Daron Acemoglu;Simon Johnson;                       | acemoglu daron; johnson simon
            Kafka, Franz; Mitchell, Mike; Robertson, Ritchie    | kafka franz; mitchell mike; robertson ritchie
            Hobson, J. Allan;                                   | hobson j allan
            Walton, Charles; Walton, Charles;                   | walton charles
            Barb Hendee; J. C. Hendee                           | hendee barb; hendee j c
            Nora Roberts; J. D. Robb                            | roberts nora; robb j d
            Danielle Steel; illustrated by Kristi Valiant       | steel danielle; valiant illustrated by kristi
            Shunryū Suzuki; Trudy Dixon; Richard Baker          | suzuki shunryu; dixon trudy; baker richard
            Katie Tsang & Kevin Tsang                           | tsang katie; tsang kevin
            Rachel; Levithan Cohn                               | cohn rachel levithan
            Lauren & Devon Royal                                | royal lauren devon
            Grosset & Dunlap                                    | dunlap grosset
            Niccol&ograve; Machiavelli                          | machiavelli niccolo
            P&eacute;ter N&aacute;das                           | nadas peter
            Mima Simi&#263;                                     | simic mima
            Patrick K. O&rsquo;Donnell                          | odonnell patrick k
            Aaron&nbsp;C. Davis                                 | davis aaron c
            Kenneth (Lester Dent & Evelyn Coulson) Robeson      | robeson kenneth
            Lindsey Priestley (Edited by)                       | priestley lindsey
            David G. Hartwell (ed.)                             | hartwell david g
            Oscar Lewis; Alberto Beltran (illustrations         | lewis oscar; beltran alberto
            Louann Brizendine; M.D.                             | brizendine louann
            Ronald C White; Jr.                                 | white ronald c
            ANGEL PAYNE &                                       | payne angel
            """)
    void readsTheAuthorsAStoredNameStandsFor(String stored, String expected) {
        // Each expected author is its surname and then its given names
        assertThat(PersonName.parseAuthor(stored)).extracting(name -> name.surname() + " " + String.join(" ", name.givenNames()))
                .containsExactly(expected.split("; "));
    }

    @Test
    void readsNoAuthorInABlankStoredName() {
        assertThat(PersonName.parseAuthor(null)).isEmpty();
        assertThat(PersonName.parseAuthor("")).isEmpty();
        assertThat(PersonName.parseAuthor(" ; ")).isEmpty();
        assertThat(PersonName.parseAuthors(null)).isEmpty();
    }

    @Test
    void readsTheAuthorsOfSeveralStoredNamesInOrderEachOnce() {
        assertThat(PersonName.parseAuthors(List.of("Barb Hendee", "J.C. Hendee")))
                .isEqualTo(PersonName.parseAuthor("Barb Hendee; J. C. Hendee"));
        assertThat(PersonName.parseAuthors(Arrays.asList("Arthur C. Clarke;Gentry Lee", null, "Lee, Gentry")))
                .extracting(PersonName::surname).containsExactly("clarke", "lee");
    }

    @Test
    void readsAFolderNameAsBefore() {
        // Only a stored author name is read as a list of full names; parse and parseAll read what they did
        assertThat(PersonName.parse("Arthur C. Clarke;Gentry Lee").surname()).isEqualTo("lee");
        assertThat(PersonName.parseAll("Rachel; Levithan Cohn")).extracting(PersonName::surname).containsExactly("rachel", "cohn");
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            Dale Brown           | Brown, Dale               | 2
            J.R.R. Tolkien       | Tolkien, John Ronald Reuel | 2
            Edward S. Aarons     | Aarons, Edward            | 2
            Edward S. Aarons     | E. Aarons                 | 2
            Ursula K. Le Guin    | Le Guin, Ursula           | 2
            Charlotte Bronte     | Brontë, Charlotte         | 2
            Dale Brown           | Brown                     | 1
            Tolkien              | J.R.R. Tolkien            | 1
            A. C. Arthur         | A.M. Arthur               | 0
            Ann Smith            | Bob Smith                 | 0
            Stephen King         | Stephen Hunter            | 0
            Verda H. Foster      | Alex Archer               | 0
            """)
    void agreesOnTheSurnameAndEveryGivenNameBothHave(String a, String b, int strength) {
        PersonName x = PersonName.parse(a);
        PersonName y = PersonName.parse(b);

        assertThat(x.strength(y)).isEqualTo(strength);
        assertThat(y.strength(x)).isEqualTo(strength);
    }
}
