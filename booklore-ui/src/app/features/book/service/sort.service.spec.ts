import {describe, expect, it} from 'vitest';
import {SortService} from './sort.service';
import {Book} from '../model/book.model';
import {SortDirection, SortOption} from '../model/sort.model';

// Deterministic, so a failure can be replayed
function random(seed: number): () => number {
  let state = seed;
  return () => {
    state = (state * 1103515245 + 12345) % 2147483648;
    return state / 2147483648;
  };
}

function books(count: number, seed: number): Book[] {
  const next = random(seed);
  const pick = <T>(values: T[]) => values[Math.floor(next() * values.length)];
  return Array.from({length: count}, (_, i) => ({
    id: i + 1,
    libraryId: 1,
    libraryName: 'Books',
    // Few distinct values, so most books tie with others, and some have none
    addedOn: pick([null, '2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z', '2026-03-01T00:00:00Z']),
    metadata: {
      bookId: i + 1,
      title: pick([undefined, 'Book 2', 'Book 10', 'book 10', 'Apple', 'apple pie', 'Zebra']),
      seriesNumber: pick([null, 1, 2, 2, 10]),
      authors: pick([undefined, ['Ann Lee'], ['Bo Kim', 'Ann Lee'], ['bo kim']]),
    },
  }) as unknown as Book);
}

function sortBy(field: string, direction: SortDirection): SortOption {
  return {label: field, field, direction};
}

describe('SortService.firstSorted', () => {
  const service = new SortService();

  it('should give the same books, in the same order, as sorting everything and taking the first ones', () => {
    for (const field of ['title', 'addedOn', 'seriesNumber', 'author', 'readStatus']) {
      for (const direction of [SortDirection.ASCENDING, SortDirection.DESCENDING]) {
        for (const [count, seed] of [[1, 1], [5, 2], [20, 3], [300, 4], [500, 5]]) {
          const shelf = books(400, seed);
          const option = sortBy(field, direction);

          const expected = service.applySort(shelf, option).slice(0, count).map(b => b.id);

          expect(service.firstSorted(shelf, option, count).map(b => b.id), `${field} ${direction} ${count}`)
            .toEqual(expected);
        }
      }
    }
  });

  it('should keep the shelf order when there is no sort or no such field', () => {
    const shelf = books(10, 6);

    expect(service.firstSorted(shelf, null, 3)).toEqual(shelf.slice(0, 3));
    expect(service.firstSorted(shelf, sortBy('noSuchField', SortDirection.ASCENDING), 3)).toEqual(shelf.slice(0, 3));
  });

  it('should give nothing for a count of zero, and the whole shelf when the count is larger', () => {
    const shelf = books(10, 7);
    const option = sortBy('title', SortDirection.ASCENDING);

    expect(service.firstSorted(shelf, option, 0)).toEqual([]);
    expect(service.firstSorted(shelf, option, 50).map(b => b.id)).toEqual(service.applySort(shelf, option).map(b => b.id));
  });
});
