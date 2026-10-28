package org.booklore.config;

import org.booklore.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The static handlers of {@link WebMvcConfig}, served from a temp copy of a build's layout.
 */
class WebMvcConfigStaticResourcesTest {

    private static final String IMMUTABLE = "max-age=31536000, public, immutable";
    private static final String INDEX_HTML = "<!doctype html><title>Booklore</title>";

    @TempDir
    static Path staticDir;

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;

    @Configuration
    @EnableWebMvc
    static class StaticResourcesConfig implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            WebMvcConfig.addStaticResourceHandlers(registry, staticDir.toUri().toString());
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    @BeforeAll
    static void setUp() throws IOException {
        write("index.html", INDEX_HTML);
        write("main-PVOU2RTT.js", "console.log('main');");
        write("chunk-2ECZ67Y6.js", "console.log('chunk');");
        write("styles-FAIPVH6P.css", "body{}");
        write("ngsw.json", "{}");
        write("ngsw-worker.js", "self;");
        write("manifest.webmanifest", "{}");
        write("favicon-128x128.png", "png");
        write("media/primeicons-4GST5W3O.woff2", "woff2");
        write("assets/i18n/en.json", "{}");

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(StaticResourcesConfig.class);
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterAll
    static void tearDown() {
        context.close();
    }

    private static void write(String relativePath, String content) throws IOException {
        Path file = staticDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void hashedRootFiles_areImmutable() throws Exception {
        for (String path : new String[]{"/main-PVOU2RTT.js", "/chunk-2ECZ67Y6.js", "/styles-FAIPVH6P.css"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, IMMUTABLE));
        }
    }

    @Test
    void mediaFiles_areImmutable() throws Exception {
        mockMvc.perform(get("/media/primeicons-4GST5W3O.woff2"))
                .andExpect(status().isOk())
                .andExpect(content().string("woff2"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, IMMUTABLE));
    }

    @Test
    void unhashedFiles_areRevalidated() throws Exception {
        for (String path : new String[]{"/index.html", "/ngsw.json", "/ngsw-worker.js", "/manifest.webmanifest",
                "/favicon-128x128.png", "/assets/i18n/en.json"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        }
    }

    @Test
    void indexHtml_revalidatesByContentEtagNotLastModified() throws Exception {
        MvcResult first = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED))
                .andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).startsWith("W/\"").endsWith("\"");

        mockMvc.perform(get("/index.html").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
        mockMvc.perform(get("/index.html").header(HttpHeaders.IF_NONE_MATCH, "W/\"an-older-release\""))
                .andExpect(status().isOk())
                .andExpect(content().string(INDEX_HTML));
    }

    @Test
    void etag_changesWithContent() throws Exception {
        write("ngsw-changing.json", "{\"v\":1}");
        String before = mockMvc.perform(get("/ngsw-changing.json")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        write("ngsw-changing.json", "{\"v\":22}");
        String after = mockMvc.perform(get("/ngsw-changing.json")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void missingMediaFile_is404() throws Exception {
        mockMvc.perform(get("/media/primeicons-NOPE1234.woff2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingHashedRootFile_is404() throws Exception {
        mockMvc.perform(get("/chunk-ZZZZZZZZ.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingAssetsFile_is404() throws Exception {
        mockMvc.perform(get("/assets/i18n/xx.json"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dottedDeepLink_getsIndexHtml() throws Exception {
        mockMvc.perform(get("/series/Mr. Mercedes"))
                .andExpect(status().isOk())
                .andExpect(content().string(INDEX_HTML))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
    }

    @Test
    void appRoutes_getIndexHtml() throws Exception {
        for (String path : new String[]{"/book/123", "/dashboard", "/no/such/route", "/Mr. Mercedes"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(INDEX_HTML))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
        }
    }
}
