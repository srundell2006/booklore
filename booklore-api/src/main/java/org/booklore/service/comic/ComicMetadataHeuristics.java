package org.booklore.service.comic;

import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Database-only signals: publisher, category strings and title shape.
 *
 * <p>These cost nothing (the metadata is already loaded) but depend entirely on
 * metadata quality, so they are weighted below the structural evidence.
 */
@Component
public class ComicMetadataHeuristics {

    /** Publishers whose output is overwhelmingly comics, manga or graphic novels. */
    private static final Set<String> COMIC_PUBLISHERS = Set.of(
            "marvel", "dc comics", "dc entertainment", "image comics", "dark horse",
            "idw publishing", "boom! studios", "dynamite", "valiant", "oni press",
            "titan comics", "titan books", "vertigo", "archie comics", "fantagraphics",
            "drawn & quarterly", "drawn and quarterly", "first second", "abrams comicarts",
            "viz media", "viz, llc", "kodansha comics", "yen press", "seven seas",
            "square enix manga", "dark horse manga", "vertical comics", "j-novel club",
            "webtoon", "scholastic graphix", "graphix", "humanoids", "europe comics",
            "rebellion", "2000 ad", "aftershock comics", "vault comics", "black mask"
    );

    /** Category fragments that mark the shelf outright. */
    private static final Set<String> COMIC_CATEGORIES = Set.of(
            "comics & graphic novels", "comics and graphic novels", "graphic novel",
            "graphic novels", "comic book", "comic books", "comics", "manga",
            "manhwa", "manhua", "bandes dessinees", "bande dessinee", "webtoon"
    );

    private static final Set<String> MANGA_MARKERS = Set.of(
            "manga", "manhwa", "manhua", "shonen", "shounen", "shojo", "shoujo",
            "seinen", "josei", "light novel"
    );

    private static final Set<String> MANGA_PUBLISHERS = Set.of(
            "viz media", "viz, llc", "kodansha comics", "yen press", "seven seas",
            "square enix manga", "dark horse manga", "vertical comics", "j-novel club"
    );

    /** Issue and collection markers: "#12", "Vol. 3", "Volume 2", "Omnibus", "TPB". */
    private static final Pattern ISSUE_PATTERN = Pattern.compile(
            "(#\\s?\\d{1,4}\\b)|(\\bvol\\.?\\s?\\d{1,3}\\b)|(\\bvolume\\s\\d{1,3}\\b)"
                    + "|(\\bomnibus\\b)|(\\btpb\\b)|(\\btrade paperback\\b)"
                    + "|(\\bchapter\\s\\d{1,4}\\b)|(\\bissue\\s\\d{1,4}\\b)",
            Pattern.CASE_INSENSITIVE);

    public void analyze(BookMetadataEntity metadata, ComicScoreCard card) {
        if (metadata == null) return;

        String publisher = lower(metadata.getPublisher());
        if (!publisher.isBlank()) {
            for (String known : COMIC_PUBLISHERS) {
                if (publisher.contains(known)) {
                    card.add("PUBLISHER_MATCH", 25,
                            "Publisher '" + metadata.getPublisher() + "' is a comics imprint");
                    break;
                }
            }
        }

        Set<CategoryEntity> categories = metadata.getCategories();
        if (categories != null) {
            // Credit the category signal once, even when several categories match.
            outer:
            for (CategoryEntity category : categories) {
                String name = lower(category.getName());
                if (name.isBlank()) continue;
                for (String known : COMIC_CATEGORIES) {
                    if (name.contains(known)) {
                        card.add("CATEGORY_MATCH", 30,
                                "Category '" + category.getName() + "' indicates comics");
                        break outer;
                    }
                }
            }
        }

        String title = lower(metadata.getTitle()) + " " + lower(metadata.getSeriesName());
        if (ISSUE_PATTERN.matcher(title).find()) {
            card.add("TITLE_PATTERN", 10,
                    "Title contains an issue or volume marker");
        }
    }

    /**
     * Returns true when the metadata reads as manga specifically, so the caller
     * can note reading direction alongside the comic flag.
     */
    public boolean looksLikeManga(BookMetadataEntity metadata) {
        if (metadata == null) return false;

        String publisher = lower(metadata.getPublisher());
        for (String known : MANGA_PUBLISHERS) {
            if (!publisher.isBlank() && publisher.contains(known)) return true;
        }

        Set<CategoryEntity> categories = metadata.getCategories();
        if (categories != null) {
            for (CategoryEntity category : categories) {
                String name = lower(category.getName());
                for (String marker : MANGA_MARKERS) {
                    if (name.contains(marker)) return true;
                }
            }
        }
        return false;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
