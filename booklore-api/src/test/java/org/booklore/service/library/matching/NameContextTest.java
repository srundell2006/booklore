package org.booklore.service.library.matching;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NameContextTest {

    @Test
    void confirmsEachAuthorAStoredNamePacks() {
        NameContext context = NameContext.NONE.withAuthors(List.of("Arthur C. Clarke;Gentry Lee"));

        assertThat(context.confirms("Gentry Lee")).isTrue();
        assertThat(context.confirms("Arthur C. Clarke")).isTrue();
        assertThat(context.confirms("Gentry Clarke")).isFalse();
    }

    @Test
    void confirmsAStoredNameWithEntitiesAndARole() {
        assertThat(NameContext.NONE.withAuthors(List.of("P&eacute;ter N&aacute;das")).confirms("Peter Nadas")).isTrue();
        assertThat(NameContext.NONE.withAuthors(List.of("Lindsey Priestley (Edited by)")).confirms("Lindsey Priestley")).isTrue();
    }
}
