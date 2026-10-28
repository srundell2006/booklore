import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {BookRuleEvaluatorService} from './book-rule-evaluator.service';
import {Book} from '../../book/model/book.model';
import {GroupRule} from '../component/magic-shelf-component';

// The fixture is shared with the backend: its magic-shelf count compiler test loads the same books into H2
// and must return the same ids for every case, so the server counts stay what the browser computes today.
// books.json documents the format. Every array expect was produced by this evaluator, not written by hand.
const FIXTURE_DIR = resolve(process.cwd(), '../booklore-api/src/test/resources/catalog-parity');

interface ParityCase {
  name: string;
  filterJson: string;
  now: string;
  expect: number[] | 'UNSUPPORTED' | 'INVALID';
  required?: boolean;
}

const readJson = <T>(file: string): T => JSON.parse(readFileSync(resolve(FIXTURE_DIR, file), 'utf8')) as T;

const fixture = readJson<{books: Record<string, unknown>[]}>('books.json');
const cases = readJson<ParityCase[]>('cases.json');

// "fixture" holds what the backend needs to rebuild the entities; it is not part of the list-view DTO
const books: Book[] = fixture.books.map(entry => {
  const book = {...entry};
  delete book['fixture'];
  return book as Book;
});

const evaluatedCases = cases.filter(c => Array.isArray(c.expect));

function hasExplicitNull(value: unknown): boolean {
  if (value === null) return true;
  if (Array.isArray(value)) return value.some(hasExplicitNull);
  if (typeof value === 'object') return Object.values(value as object).some(hasExplicitNull);
  return false;
}

describe('catalog parity fixture', () => {
  let evaluator: BookRuleEvaluatorService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    evaluator = TestBed.inject(BookRuleEvaluatorService);
    vi.useFakeTimers({toFake: ['Date']});
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should keep the books in the list-view shape the server streams', () => {
    const ids = books.map(b => b.id);
    expect(ids).toEqual([...ids].sort((a, b) => a - b));
    expect(new Set(ids).size).toBe(ids.length);
    // Jackson omits nulls; an explicit null would change the UI result (Number(null) is 0, Number(undefined) is NaN)
    expect(books.filter(hasExplicitNull).map(b => b.id)).toEqual([]);
  });

  it('should hold cases with unique names and ascending expects', () => {
    expect(new Set(cases.map(c => c.name)).size).toBe(cases.length);
    expect(evaluatedCases.length).toBeGreaterThan(0);
    for (const c of evaluatedCases) {
      const ids = c.expect as number[];
      expect(ids, c.name).toEqual([...ids].sort((a, b) => a - b));
    }
  });

  it.each(evaluatedCases.map(c => [c.name, c] as const))('%s', (_name, c) => {
    vi.setSystemTime(new Date(c.now));
    const group = JSON.parse(c.filterJson) as GroupRule;

    const matched = books.filter(book => evaluator.evaluateGroup(book, group, books)).map(book => book.id);

    expect(matched).toEqual(c.expect);
  });

  it('should fail to parse every INVALID case, as the shelf count does', () => {
    const invalid = cases.filter(c => c.expect === 'INVALID');
    expect(invalid.length).toBeGreaterThan(0);
    for (const c of invalid) {
      expect(() => JSON.parse(c.filterJson), c.name).toThrow();
    }
  });
});
