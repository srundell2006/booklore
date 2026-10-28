package org.booklore.model.dto.catalog;

import java.util.Map;

/**
 * @param magicShelves every magic shelf the user lists (own, then other users' public ones), by id
 */
public record MagicShelfCounts(Map<Long, MagicShelfCount> magicShelves) {
}
