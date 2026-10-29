import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {inject} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {HttpClient, HttpRequest, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {Subject} from 'rxjs';
import {seriesFromList, SeriesBooksService, SeriesBooksState} from './series-books.service';
import {BookService} from './book.service';
import {BookStateService} from './book-state.service';
import {BookChange} from './book-socket.service';
import {CatalogWriteInterceptor} from '../../../core/services/catalog-write.interceptor';
import {API_CONFIG} from '../../../core/config/api-config';
import {Book} from '../model/book.model';
import {SeriesBooks} from '../model/catalog.model';

const SERIES_URL = `${API_CONFIG.BASE_URL}/api/v1/catalog/series/books`;

function book(id: number, seriesName?: string, seriesNumber?: number): Book {
  return {id, libraryId: 1, libraryName: 'Books', metadata: {bookId: id, title: `Book ${id}`, seriesName, seriesNumber}} as Book;
}

describe('seriesFromList', () => {
  it('should match the trimmed name ignoring case, by number with the books without one last', () => {
    const books = [
      book(1, 'Dune', undefined), book(2, ' dune ', 2), book(3, 'Dune', 1), book(4, 'Dune Messiah', 1),
      book(5, 'DUNE', undefined), book(6), book(7, 'Dune', 2),
    ];

    const series = seriesFromList(books, '\tdune ');

    expect(series.books.map(b => b.id)).toEqual([3, 2, 7, 1, 5]);
    expect(series.seriesName).toBe('Dune');
    expect(series.total).toBe(5);
  });

  it('should cut the books at the limit and count them all', () => {
    const series = seriesFromList([book(1, 'A', 1), book(2, 'A', 2), book(3, 'A', 3)], 'A', 2);

    expect(series.books.map(b => b.id)).toEqual([1, 2]);
    expect(series.total).toBe(3);
  });

  it('should match nothing for a blank name', () => {
    expect(seriesFromList([book(1, ' '), book(2)], '  ')).toEqual({seriesName: null, total: 0, books: []});
  });
});

describe('SeriesBooksService', () => {
  let service: SeriesBooksService;
  let http: HttpTestingController;
  let bookChanges$: Subject<BookChange[]>;
  let bookState: BookStateService;

  beforeEach(() => {
    vi.useFakeTimers();
    bookChanges$ = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([CatalogWriteInterceptor])),
        provideHttpClientTesting(),
        // The list as it is held, without downloading it
        {provide: BookService, useFactory: () => ({bookChanges$, bookState$: inject(BookStateService).bookState$})},
      ]
    });
    service = TestBed.inject(SeriesBooksService);
    http = TestBed.inject(HttpTestingController);
    bookState = TestBed.inject(BookStateService);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const isSeriesRequest = (name: string) => (req: HttpRequest<unknown>) => req.url === SERIES_URL && req.params.get('name') === name;
  const answer = (name: string, ids: number[], total = ids.length) =>
    http.expectOne(isSeriesRequest(name)).flush({seriesName: name, total, books: ids.map(id => book(id, name))} satisfies SeriesBooks);

  function watch(name: string, limit?: number): SeriesBooksState[] {
    const states: SeriesBooksState[] = [];
    service.series$(name, limit).subscribe(state => states.push(state));
    return states;
  }

  const shown = (states: SeriesBooksState[]) => states.map(s => `${s.status}:${s.books.map(b => b.id).join(',')}/${s.total}`);

  function write(path: string): void {
    TestBed.inject(HttpClient).post(`${API_CONFIG.BASE_URL}${path}`, {}).subscribe();
    http.expectOne(`${API_CONFIG.BASE_URL}${path}`).flush(null);
  }

  it('should send the name and the limit as query parameters, with every reserved character encoded', () => {
    service.getSeriesBooks('C++ / #1?', 200).subscribe();

    const request = http.expectOne(isSeriesRequest('C++ / #1?'));
    expect(request.request.urlWithParams).toBe(`${SERIES_URL}?name=C%2B%2B%20%2F%20%231%3F&limit=200`);
    request.flush({seriesName: 'C++ / #1?', total: 0, books: []});
  });

  it('should leave the limit to the server when none is given', () => {
    watch('Dune');

    const request = http.expectOne(isSeriesRequest('Dune'));
    expect(request.request.params.has('limit')).toBe(false);
    request.flush({seriesName: 'Dune', total: 0, books: []});
  });

  it('should load the series once, then fetch it again 500 ms after local writes, keeping the books shown meanwhile', () => {
    const states = watch('Dune');
    answer('Dune', [1, 2]);

    write('/api/v1/books/status');
    write('/api/v1/books/2/physical');
    vi.advanceTimersByTime(499);
    http.expectNone(isSeriesRequest('Dune'));
    vi.advanceTimersByTime(1);
    answer('Dune', [2, 1]);

    expect(shown(states)).toEqual(['loading:/0', 'ready:1,2/2', 'ready:2,1/2']);
  });

  it('should not fetch again after a write of reading progress', () => {
    watch('Dune');
    answer('Dune', [1]);

    write('/api/v1/books/1/progress');
    vi.advanceTimersByTime(1_000);

    http.expectNone(isSeriesRequest('Dune'));
  });

  it('should fetch again at the first and last websocket changes within 10 s', () => {
    watch('Dune');
    answer('Dune', [1]);

    bookChanges$.next([{kind: 'created', book: book(5)}]);
    answer('Dune', [1]);
    bookChanges$.next([{kind: 'created', book: book(6)}]);
    vi.advanceTimersByTime(9_999);
    http.expectNone(isSeriesRequest('Dune'));
    vi.advanceTimersByTime(1);
    answer('Dune', [1]);
  });

  it('should drop a removed book at once and count it out of the total', () => {
    const states = watch('Dune');
    answer('Dune', [1, 2, 3], 10);

    bookState.removeBooks([2]);
    bookState.removeBooks([7]);

    expect(shown(states)).toEqual(['loading:/0', 'ready:1,2,3/10', 'ready:1,3/9']);
  });

  it('should keep a removed book out of the fetches started up to the next one after the removal', () => {
    const states = watch('Dune');
    answer('Dune', [1, 2, 3]);

    write('/api/v1/books/status');
    vi.advanceTimersByTime(500);
    const running = http.expectOne(isSeriesRequest('Dune'));
    bookState.removeBooks([2]);
    running.flush({seriesName: 'Dune', total: 3, books: [1, 2, 3].map(id => book(id))});
    // A removal by the server is announced before the book is gone, so the fetch it starts may still read it
    bookChanges$.next([{kind: 'removed', ids: [2]}]);
    answer('Dune', [1, 2, 3]);
    write('/api/v1/books/status');
    vi.advanceTimersByTime(500);
    answer('Dune', [1, 2, 3]);

    expect(shown(states)).toEqual(['loading:/0', 'ready:1,2,3/3', 'ready:1,3/2', 'ready:1,3/2', 'ready:1,3/2', 'ready:1,2,3/3']);
  });

  it('should keep loading through a server failure and retry, then keep the books shown on a later failure', () => {
    const states = watch('Dune');
    http.expectOne(isSeriesRequest('Dune')).flush(null, {status: 500, statusText: 'Server Error'});
    expect(shown(states)).toEqual(['loading:/0']);

    vi.advanceTimersByTime(5_000);
    answer('Dune', [1]);
    write('/api/v1/books/status');
    vi.advanceTimersByTime(500);
    http.expectOne(isSeriesRequest('Dune')).flush(null, {status: 0, statusText: 'Unknown Error'});
    expect(shown(states)).toEqual(['loading:/0', 'ready:1/1']);

    vi.advanceTimersByTime(5_000);
    answer('Dune', [2, 1]);
    expect(shown(states).at(-1)).toBe('ready:2,1/2');
  });

  it('should end loading with no books on an error the server meant, and not retry it', () => {
    const states = watch(' ');
    http.expectOne(isSeriesRequest(' ')).flush({status: 400, message: 'Series name is required'}, {status: 400, statusText: 'Bad Request'});

    vi.advanceTimersByTime(120_000);
    http.expectNone(isSeriesRequest(' '));
    expect(shown(states)).toEqual(['loading:/0', 'error:/0']);
  });

  it('should compute the series from the book list on a server without the endpoint', () => {
    const states = watch('Dune');

    http.expectOne(isSeriesRequest('Dune')).flush({status: 404, message: 'Resource not found'}, {status: 404, statusText: 'Not Found'});
    bookState.setLoaded([book(8, 'Dune', 2), book(9, 'Other'), book(7, 'dune ', 1)]);
    write('/api/v1/books/status');
    vi.advanceTimersByTime(1_000);
    bookState.removeBooks([8]);

    expect(shown(states)).toEqual(['loading:/0', 'ready:7,8/2', 'ready:7/1']);
    http.expectNone(isSeriesRequest('Dune'));
  });

  it('should retry a 404 without the app error body as a passing failure', () => {
    const states = watch('Dune');
    http.expectOne(isSeriesRequest('Dune')).flush('<html>Not Found</html>', {status: 404, statusText: 'Not Found'});

    vi.advanceTimersByTime(5_000);
    answer('Dune', [1]);
    expect(shown(states)).toEqual(['loading:/0', 'ready:1/1']);
  });

  it('should answer getSeriesBooks from the book list on a server without the endpoint', () => {
    const answers: SeriesBooks[] = [];
    service.getSeriesBooks('Dune', 1).subscribe(series => answers.push(series));

    http.expectOne(isSeriesRequest('Dune')).flush({status: 404, message: 'Resource not found'}, {status: 404, statusText: 'Not Found'});
    bookState.setLoaded([book(8, 'Dune', 2), book(7, 'Dune', 1)]);

    expect(answers).toEqual([{seriesName: 'Dune', total: 2, books: [book(7, 'Dune', 1)]}]);
  });

  it('should stop fetching once unsubscribed', () => {
    const subscription = service.series$('Dune').subscribe();
    answer('Dune', [1]);
    subscription.unsubscribe();

    write('/api/v1/books/status');
    bookChanges$.next([{kind: 'created', book: book(5)}]);
    vi.advanceTimersByTime(60_000);

    http.expectNone(isSeriesRequest('Dune'));
  });
});
