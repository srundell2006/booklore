package org.booklore.controller;

import org.booklore.service.AuthorMetadataService;
import org.booklore.service.AuthorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthorControllerCountTest {

    @Mock
    private AuthorService authorService;
    @Mock
    private AuthorMetadataService authorMetadataService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthorController(authorService, authorMetadataService)).build();
    }

    @Test
    void count_returnsTheCountNotAnAuthor() throws Exception {
        when(authorMetadataService.countAuthors()).thenReturn(42L);

        mockMvc.perform(get("/api/v1/authors/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"count\":42}"));

        verify(authorMetadataService, never()).getAuthorDetails(org.mockito.ArgumentMatchers.anyLong());
        verify(authorMetadataService, never()).getAllAuthors();
    }
}
