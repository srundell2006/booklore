import {Book} from './book.model';

/** GET /api/v1/catalog/counts. With clientSide set, the counts are computed from the book list instead. */
export interface CatalogCounts {
  clientSide: boolean;
  allBooks: number;
  series: number;
  unshelved: number;
  libraries: Record<number, number>;
  /** The viewer's own shelves only; other users' public shelves keep the count GET /shelves returns. */
  shelves: Record<number, number>;
}

export type MagicCountStatus = 'OK' | 'UNSUPPORTED' | 'INVALID' | 'ERROR';

export interface MagicShelfCount {
  status: MagicCountStatus;
  count: number | null;
  /** For UNSUPPORTED: each rule the server cannot count, as "field:operator", or "restrictions". */
  reasons?: string[];
}

/** GET /api/v1/catalog/magic-counts: one entry for every magic shelf the viewer sees. */
export interface MagicCounts {
  magicShelves: Record<number, MagicShelfCount>;
}

export type CatalogRowKind = 'continue-reading' | 'continue-listening' | 'recently-added' | 'random';

/** GET /api/v1/catalog/rows/{kind}: the books of a dashboard row, in order, as GET /api/v1/books lists them. */
export interface CatalogRowResponse {
  books: Book[];
}

/** 'legacy': every count is computed from the book list, as before the catalog endpoints. */
export type CatalogMode = 'server' | 'legacy';
