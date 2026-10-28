package org.booklore.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build files served by {@link WebMvcConfig} through a real Tomcat, gzipped per the
 * {@code server.compression} settings in application.yaml. MockMvc does not compress.
 */
class StaticResourcesCompressionTest {

    @TempDir
    static Path staticDir;

    private static WebServer webServer;
    private static HttpClient httpClient;

    @Configuration
    @EnableWebMvc
    static class StaticResourcesConfig implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            WebMvcConfig.addStaticResourceHandlers(registry, staticDir.toUri().toString());
        }
    }

    @BeforeAll
    static void setUp() throws IOException {
        // Above server.compression.min-response-size, so only the content type decides.
        String js = "console.log('main');\n".repeat(200);
        write("main-PVOU2RTT.js", js);
        write("ngsw-worker.js", js);
        write("assets/pdf.worker-5.6.1113.mjs", js);
        write("styles-FAIPVH6P.css", "body{margin:0}\n".repeat(200));

        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setCompression(compressionFromApplicationYaml());
        webServer = factory.getWebServer(servletContext -> {
            AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
            context.register(StaticResourcesConfig.class);
            servletContext.addServlet("dispatcher", new DispatcherServlet(context)).addMapping("/");
        });
        webServer.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        httpClient.close();
        webServer.stop();
    }

    private static Compression compressionFromApplicationYaml() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("server.compression", Compression.class).get();
    }

    private static void write(String relativePath, String content) throws IOException {
        Path file = staticDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static HttpResponse<byte[]> getGzip(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + webServer.getPort() + path))
                .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void javascript_isGzipped() throws Exception {
        for (String path : new String[]{"/main-PVOU2RTT.js", "/ngsw-worker.js", "/assets/pdf.worker-5.6.1113.mjs"}) {
            HttpResponse<byte[]> response = getGzip(path);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE)).as(path).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/javascript"));
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_ENCODING)).as(path).hasValue("gzip");
        }
    }

    @Test
    void css_isGzipped() throws Exception {
        HttpResponse<byte[]> response = getGzip("/styles-FAIPVH6P.css");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_ENCODING)).hasValue("gzip");
    }
}
