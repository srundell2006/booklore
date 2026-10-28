package org.booklore.config;

import lombok.RequiredArgsConstructor;
import org.booklore.interceptor.KomgaCleanInterceptor;
import org.booklore.interceptor.KomgaEnabledInterceptor;
import org.booklore.interceptor.OpdsEnabledInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.http.CacheControl;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String STATIC_LOCATION = "classpath:/static/";

    /**
     * A root-level file Angular names after its content, e.g. {@code main-PVOU2RTT.js} or
     * {@code chunk-2ECZ67Y6.js}. Everything under {@code media/} is named the same way.
     */
    private static final String HASHED_ROOT_FILE = "/{file:[\\w.-]+-[A-Z0-9]{8}\\.\\w+}";

    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    private static final CacheControl REVALIDATE = CacheControl.noCache();

    private final OpdsEnabledInterceptor opdsEnabledInterceptor;
    private final KomgaEnabledInterceptor komgaEnabledInterceptor;
    private final KomgaCleanInterceptor komgaCleanInterceptor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new VirtualThreadTaskExecutor("mvc-async-"));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        addStaticResourceHandlers(registry, STATIC_LOCATION);
    }

    /**
     * Serves the Angular build. Hashed files are cached for a year; everything else (index.html,
     * ngsw.json, the service workers, the manifest, /assets) is revalidated on every use.
     *
     * <p>Only unknown paths that are not build files fall back to index.html, so deep links such as
     * {@code /series/Mr. Mercedes} still open the app while a missing font or chunk answers 404
     * instead of an HTML page the browser then caches as that file.
     *
     * <p>Revalidation uses a weak ETag of the content, not Last-Modified: the jar is built with fixed
     * entry timestamps, so every release would look unchanged. Weak because Tomcat skips
     * compression for responses with a strong ETag.
     */
    static void addStaticResourceHandlers(ResourceHandlerRegistry registry, String location) {
        Map<String, String> etags = new ConcurrentHashMap<>();

        withContentEtag(registry.addResourceHandler("/media/**")
                .addResourceLocations(location + "media/")
                .setCacheControl(IMMUTABLE), etags)
                .resourceChain(true);
        withContentEtag(registry.addResourceHandler(HASHED_ROOT_FILE)
                .addResourceLocations(location)
                .setCacheControl(IMMUTABLE), etags)
                .resourceChain(true);
        withContentEtag(registry.addResourceHandler("/assets/**")
                .addResourceLocations(location + "assets/")
                .setCacheControl(REVALIDATE), etags)
                .resourceChain(true);
        withContentEtag(registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCacheControl(REVALIDATE), etags)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = super.getResource(resourcePath, location);
                        return resource != null ? resource : super.getResource("index.html", location);
                    }
                });
    }

    private static ResourceHandlerRegistration withContentEtag(ResourceHandlerRegistration registration, Map<String, String> etags) {
        return registration
                .setUseLastModified(false)
                .setEtagGenerator(resource -> etags.computeIfAbsent(etagKey(resource), key -> contentEtag(resource)));
    }

    private static String etagKey(Resource resource) {
        try {
            return resource.getURL() + "@" + resource.lastModified() + ":" + resource.contentLength();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String contentEtag(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return "W/\"" + DigestUtils.md5DigestAsHex(in) + "\"";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(opdsEnabledInterceptor)
                .addPathPatterns("/api/v1/opds/**", "/api/v2/opds/**");
        registry.addInterceptor(komgaEnabledInterceptor)
                .addPathPatterns("/komga/api/**");
        registry.addInterceptor(komgaCleanInterceptor)
                .addPathPatterns("/komga/api/**");
    }
}
