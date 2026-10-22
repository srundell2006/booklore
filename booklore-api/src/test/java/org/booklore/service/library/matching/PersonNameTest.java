package org.booklore.service.library.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
