package org.booklore.config;

import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.metadata.parser.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class BookParserConfig {

    @Bean
    public Map<MetadataProvider, BookParser> parserMap(
            GoogleParser googleParser,
            AmazonBookParser amazonBookParser,
            GoodReadsParser goodReadsParser,
            HardcoverParser hardcoverParser,
            OpenLibraryParser openLibraryParser,
            OpenLibraryLocalParser openLibraryLocalParser,
            ComicvineBookParser comicvineBookParser,
            DoubanBookParser doubanBookParser,
            RanobeDbParser ranobedbParser,
            LubimyCzytacParser lubimyczytacParser,
            AudibleParser audibleParser,
            OllamaMetadataParser ollamaMetadataParser) {

        // Map.of() supports at most 10 entries — use Map.ofEntries for 11+
        return Map.ofEntries(
                Map.entry(MetadataProvider.Amazon,           amazonBookParser),
                Map.entry(MetadataProvider.GoodReads,        goodReadsParser),
                Map.entry(MetadataProvider.Google,           googleParser),
                Map.entry(MetadataProvider.Hardcover,        hardcoverParser),
                Map.entry(MetadataProvider.OpenLibrary,      openLibraryParser),
                Map.entry(MetadataProvider.OpenLibraryLocal, openLibraryLocalParser),
                Map.entry(MetadataProvider.Comicvine,        comicvineBookParser),
                Map.entry(MetadataProvider.Douban,           doubanBookParser),
                Map.entry(MetadataProvider.Lubimyczytac,     lubimyczytacParser),
                Map.entry(MetadataProvider.Ranobedb,         ranobedbParser),
                Map.entry(MetadataProvider.Audible,          audibleParser),
                Map.entry(MetadataProvider.Ollama,           ollamaMetadataParser)
        );
    }
}
