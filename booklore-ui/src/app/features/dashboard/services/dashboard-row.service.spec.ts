import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {BehaviorSubject, NEVER, Observable, Subject} from 'rxjs';
import {DashboardRowService, DashboardRowState} from './dashboard-row.service';
import {BookService} from '../../book/service/book.service';
import {BookStateService} from '../../book/service/book-state.service';
import {BookChange} from '../../book/service/book-socket.service';
import {CatalogWriteInterceptor} from '../../../core/services/catalog-write.interceptor';
import {API_CONFIG} from '../../../core/config/api-config';
import {Book} from '../../book/model/book.model';
import {CatalogRowKind} from '../../book/model/catalog.model';

const ROWS_URL = `${API_CONFIG.BASE_URL}/api/v1/catalog/rows`;

function book(id: number): Book {
  return {id, libraryId: 1, libraryName: 'Books', metadata: {bookId: id, title: `Book ${id}`}} as Book;
}

describe('DashboardRowService', () => {
  let service: DashboardRowService;
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
        {provide: BookService, useValue: {bookChanges$}},
        DashboardRowService,
      ]
    });
    service = TestBed.inject(DashboardRowService);
    http = TestBed.inject(HttpTestingController);
    bookState = TestBed.inject(BookStateService);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  function watch(kind: CatalogRowKind, legacy: () => Observable<Book[]> = () => NEVER): DashboardRowState[] {
    const states: DashboardRowState[] = [];
    service.row$(kind, 20, legacy).subscribe(state => states.push(state));
    return states;
  }

  const shown = (states: DashboardRowState[]) => states.map(s => `${s.status}:${s.books.map(b => b.id).join(',')}`);
  const answer = (kind: CatalogRowKind, ...ids: number[]) => http.expectOne(`${ROWS_URL}/${kind}?size=20`).flush({books: ids.map(book)});

  function write(path: string): void {
    TestBed.inject(HttpClient).post(`${API_CONFIG.BASE_URL}${path}`, {}).subscribe();
    http.expectOne(`${API_CONFIG.BASE_URL}${path}`).flush(null);
  }

  it('should load a row once, with its size', () => {
    const states = watch('recently-added');

    answer('recently-added', 3, 1);

    expect(shown(states)).toEqual(['loading:', 'ready:3,1']);
  });

  it('should fetch again 500 ms after local writes, keeping the books shown meanwhile', () => {
    const states = watch('continue-reading');
    answer('continue-reading', 1, 2);

    write('/api/v1/books/status');
    write('/api/v1/books/2/physical');
    vi.advanceTimersByTime(499);
    http.expectNone(`${ROWS_URL}/continue-reading?size=20`);
    vi.advanceTimersByTime(1);
    answer('continue-reading', 2, 1);

    expect(shown(states)).toEqual(['loading:', 'ready:1,2', 'ready:2,1']);
  });

  it('should fetch again at the first and last websocket changes within 30 s', () => {
    watch('continue-listening');
    answer('continue-listening', 1);

    bookChanges$.next([{kind: 'created', book: book(5)}]);
    answer('continue-listening', 1);
    bookChanges$.next([{kind: 'created', book: book(6)}]);
    vi.advanceTimersByTime(29_999);
    http.expectNone(`${ROWS_URL}/continue-listening?size=20`);
    vi.advanceTimersByTime(1);
    answer('continue-listening', 1);
  });

  it('should keep the random row as it is after writes and websocket changes', () => {
    const states = watch('random');
    answer('random', 4, 2, 9);

    write('/api/v1/books/status');
    bookChanges$.next([{kind: 'created', book: book(5)}]);
    vi.advanceTimersByTime(60_000);

    http.expectNone(`${ROWS_URL}/random?size=20`);
    expect(shown(states)).toEqual(['loading:', 'ready:4,2,9']);
  });

  it('should drop a removed book from the row at once, the random row too', () => {
    const states = watch('random');
    answer('random', 4, 2, 9);

    bookState.removeBooks([2]);
    bookState.removeBooks([7]);

    expect(shown(states)).toEqual(['loading:', 'ready:4,2,9', 'ready:4,9']);
  });

  it('should show the error with no books, and keep the books on a later failure', () => {
    const states = watch('recently-added');
    http.expectOne(`${ROWS_URL}/recently-added?size=20`).flush(null, {status: 500, statusText: 'Server Error'});
    expect(shown(states)).toEqual(['loading:', 'error:']);

    vi.advanceTimersByTime(5_000);
    answer('recently-added', 1);
    write('/api/v1/books/status');
    vi.advanceTimersByTime(500);
    http.expectOne(`${ROWS_URL}/recently-added?size=20`).flush(null, {status: 0, statusText: 'Unknown Error'});

    expect(shown(states)).toEqual(['loading:', 'error:', 'ready:1']);
    vi.advanceTimersByTime(15_000);
    answer('recently-added', 2, 1);
    expect(shown(states).at(-1)).toBe('ready:2,1');
  });

  it('should not retry an error the server meant', () => {
    const states = watch('recently-added');
    http.expectOne(`${ROWS_URL}/recently-added?size=20`).flush({status: 400, message: 'Bad size'}, {status: 400, statusText: 'Bad Request'});

    vi.advanceTimersByTime(120_000);
    http.expectNone(`${ROWS_URL}/recently-added?size=20`);
    expect(shown(states)).toEqual(['loading:', 'error:']);
  });

  it('should compute the row from the list on a server without the row endpoints', () => {
    const list$ = new BehaviorSubject<Book[]>([]);
    const states = watch('recently-added', () => list$);

    http.expectOne(`${ROWS_URL}/recently-added?size=20`).flush({status: 404, message: 'Resource not found'}, {status: 404, statusText: 'Not Found'});
    bookState.setLoaded([book(8)]);
    list$.next([book(8)]);
    write('/api/v1/books/status');
    vi.advanceTimersByTime(1_000);

    expect(shown(states)).toEqual(['loading:', 'loading:', 'ready:8']);
    http.expectNone(`${ROWS_URL}/recently-added?size=20`);
  });

  it('should stop fetching once unsubscribed', () => {
    const subscription = service.row$('recently-added', 20, () => NEVER).subscribe();
    answer('recently-added', 1);
    subscription.unsubscribe();

    write('/api/v1/books/status');
    bookChanges$.next([{kind: 'created', book: book(5)}]);
    vi.advanceTimersByTime(60_000);

    http.expectNone(`${ROWS_URL}/recently-added?size=20`);
  });
});
