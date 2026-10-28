import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {BehaviorSubject, of, Subject} from 'rxjs';
import {CatalogCountsService} from './catalog-counts.service';
import {BookService} from './book.service';
import {BookChange} from './book-socket.service';
import {AuthService} from '../../../shared/service/auth.service';
import {RxStompService} from '../../../shared/websocket/rx-stomp.service';
import {CatalogWriteInterceptor} from '../../../core/services/catalog-write.interceptor';
import {API_CONFIG} from '../../../core/config/api-config';
import {CatalogCounts, CatalogMode} from '../model/catalog.model';

const COUNTS_URL = `${API_CONFIG.BASE_URL}/api/v1/catalog/counts`;
const MAGIC_COUNTS_URL = `${API_CONFIG.BASE_URL}/api/v1/catalog/magic-counts`;

function counts(allBooks: number): CatalogCounts {
  return {clientSide: false, allBooks, series: 3, unshelved: 2, libraries: {1: allBooks}, shelves: {4: 1}};
}

describe('CatalogCountsService', () => {
  let service: CatalogCountsService;
  let http: HttpTestingController;
  let client: HttpClient;
  let token$: BehaviorSubject<string | null>;
  let bookChanges$: Subject<BookChange[]>;
  let connected$: Subject<unknown>;
  let visibility: DocumentVisibilityState;

  beforeEach(() => {
    vi.useFakeTimers();
    token$ = new BehaviorSubject<string | null>('token');
    bookChanges$ = new Subject();
    connected$ = new Subject();
    visibility = 'visible';
    Object.defineProperty(document, 'visibilityState', {configurable: true, get: () => visibility});

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([CatalogWriteInterceptor])),
        provideHttpClientTesting(),
        {provide: AuthService, useValue: {token$}},
        {provide: BookService, useValue: {bookChanges$}},
        {provide: RxStompService, useValue: {connected$}},
      ]
    });
    service = TestBed.inject(CatalogCountsService);
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
    delete (document as unknown as Record<string, unknown>)['visibilityState'];
  });

  function watchCounts(): (CatalogCounts | null)[] {
    const values: (CatalogCounts | null)[] = [];
    service.counts$.subscribe(value => values.push(value));
    return values;
  }

  function write(path: string): void {
    client.post(`${API_CONFIG.BASE_URL}${path}`, {bookIds: [1]}).subscribe();
    http.expectOne(`${API_CONFIG.BASE_URL}${path}`).flush(null);
  }

  const bookChange: BookChange[] = [{kind: 'removed', ids: [1]}];

  it('should fetch only while something shows the counts', () => {
    http.expectNone(COUNTS_URL);
    bookChanges$.next(bookChange);
    http.expectNone(COUNTS_URL);

    const subscription = service.counts$.subscribe();
    http.expectOne(COUNTS_URL).flush(counts(10));
    subscription.unsubscribe();

    write('/api/v1/books/status');
    vi.advanceTimersByTime(60_000);
    bookChanges$.next(bookChange);
    http.expectNone(COUNTS_URL);
    http.expectNone(MAGIC_COUNTS_URL);
  });

  it('should show the last counts at once to a new subscriber, and fetch them again', () => {
    service.counts$.subscribe().unsubscribe();
    http.expectOne(COUNTS_URL).flush(counts(10));

    const values = watchCounts();

    expect(values).toEqual([counts(10)]);
    http.expectOne(COUNTS_URL).flush(counts(11));
    expect(values).toEqual([counts(10), counts(11)]);
  });

  it('should start at null and share one fetch between subscribers', () => {
    const first = watchCounts();
    const second = watchCounts();

    expect(first).toEqual([null]);
    http.expectOne(COUNTS_URL).flush(counts(10));
    expect(first).toEqual([null, counts(10)]);
    expect(second).toEqual([null, counts(10)]);
  });

  it('should fetch once, 500 ms after the last of several local writes', () => {
    watchCounts();
    http.expectOne(COUNTS_URL).flush(counts(10));

    write('/api/v1/books/status');
    vi.advanceTimersByTime(300);
    write('/api/v1/shelves/4');
    write('/api/magic-shelves');
    vi.advanceTimersByTime(499);
    http.expectNone(COUNTS_URL);

    vi.advanceTimersByTime(1);
    http.expectOne(COUNTS_URL).flush(counts(9));
  });

  it('should not fetch after a write of reading progress or viewer settings', () => {
    watchCounts();
    http.expectOne(COUNTS_URL).flush(counts(10));

    write('/api/v1/books/progress');
    write('/api/v1/books/1/viewer-setting');
    vi.advanceTimersByTime(5_000);

    http.expectNone(COUNTS_URL);
  });

  it('should fetch at the first and the last of the websocket changes within 30 s', () => {
    watchCounts();
    http.expectOne(COUNTS_URL).flush(counts(10));

    bookChanges$.next(bookChange);
    http.expectOne(COUNTS_URL).flush(counts(11));
    vi.advanceTimersByTime(10_000);
    bookChanges$.next(bookChange);
    bookChanges$.next(bookChange);
    vi.advanceTimersByTime(19_999);
    http.expectNone(COUNTS_URL);

    vi.advanceTimersByTime(1);
    http.expectOne(COUNTS_URL).flush(counts(12));
    vi.advanceTimersByTime(60_000);
    http.expectNone(COUNTS_URL);
  });

  it('should fetch again after a websocket reconnect, not after the first connection', () => {
    watchCounts();
    http.expectOne(COUNTS_URL).flush(counts(10));

    connected$.next('OPEN');
    http.expectNone(COUNTS_URL);

    connected$.next('OPEN');
    http.expectOne(COUNTS_URL).flush(counts(11));
  });

  it('should fetch again when the page shows after more than 60 s hidden', () => {
    watchCounts();
    http.expectOne(COUNTS_URL).flush(counts(10));

    const setVisibility = (state: DocumentVisibilityState) => {
      visibility = state;
      document.dispatchEvent(new Event('visibilitychange'));
    };
    setVisibility('hidden');
    vi.advanceTimersByTime(30_000);
    setVisibility('visible');
    http.expectNone(COUNTS_URL);

    setVisibility('hidden');
    vi.advanceTimersByTime(61_000);
    setVisibility('visible');
    http.expectOne(COUNTS_URL).flush(counts(11));
  });

  it('should keep the last counts on an error and retry after 5, 15 and 60 s', () => {
    const values = watchCounts();
    http.expectOne(COUNTS_URL).flush(counts(10));

    bookChanges$.next(bookChange);
    http.expectOne(COUNTS_URL).flush(null, {status: 502, statusText: 'Bad Gateway'});
    vi.advanceTimersByTime(4_999);
    http.expectNone(COUNTS_URL);
    vi.advanceTimersByTime(1);
    http.expectOne(COUNTS_URL).error(new ProgressEvent('error'));
    vi.advanceTimersByTime(14_999);
    http.expectNone(COUNTS_URL);
    vi.advanceTimersByTime(1);
    http.expectOne(COUNTS_URL).flush({status: 500, message: 'Database down'}, {status: 500, statusText: 'Server Error'});
    vi.advanceTimersByTime(60_000);
    http.expectOne(COUNTS_URL).flush(null, {status: 503, statusText: 'Unavailable'});
    vi.advanceTimersByTime(60_000);
    http.expectOne(COUNTS_URL).flush(counts(12));

    expect(values).toEqual([null, counts(10), counts(12)]);
  });

  it('should stop retrying when nothing shows the counts', () => {
    const subscription = service.counts$.subscribe();
    http.expectOne(COUNTS_URL).flush(null, {status: 502, statusText: 'Bad Gateway'});
    subscription.unsubscribe();

    vi.advanceTimersByTime(120_000);
    http.expectNone(COUNTS_URL);
  });

  it('should treat a 404 without the app error body as a passing failure', () => {
    const modes: CatalogMode[] = [];
    service.mode$.subscribe(mode => modes.push(mode));
    watchCounts();

    http.expectOne(COUNTS_URL).flush('<html>Not Found</html>', {status: 404, statusText: 'Not Found'});
    vi.advanceTimersByTime(5_000);
    http.expectOne(COUNTS_URL).flush(counts(10));

    expect(modes).toEqual(['server']);
  });

  it('should switch to legacy mode on a 404 with the app error body, as an older server sends', () => {
    const legacy$ = new BehaviorSubject(7);
    const shown: (number | null)[] = [];
    service.view$(c => c.allBooks, () => legacy$).subscribe(value => shown.push(value));

    http.expectOne(COUNTS_URL).flush({status: 404, message: 'Resource not found'}, {status: 404, statusText: 'Not Found'});
    legacy$.next(8);

    expect(shown).toEqual([null, 7, 8]);
    bookChanges$.next(bookChange);
    vi.advanceTimersByTime(60_000);
    http.expectNone(COUNTS_URL);
  });

  it('should switch to legacy mode when the server asks for counts from the list', () => {
    const shown: (number | null)[] = [];
    service.view$(c => c.allBooks, () => of(7)).subscribe(value => shown.push(value));
    const magic: unknown[] = [];
    service.magicCounts$.subscribe(value => magic.push(value));
    http.expectOne(MAGIC_COUNTS_URL).flush({magicShelves: {}});

    http.expectOne(COUNTS_URL).flush({clientSide: true});

    expect(shown).toEqual([null, 7]);
    bookChanges$.next(bookChange);
    http.expectNone(COUNTS_URL);
    http.expectNone(MAGIC_COUNTS_URL);
  });

  it('should show the server counts through view$', () => {
    const shown: (number | null)[] = [];
    service.view$(c => c.libraries[1] ?? 0, () => of(-1)).subscribe(value => shown.push(value));

    http.expectOne(COUNTS_URL).flush(counts(144));

    expect(shown).toEqual([null, 144]);
  });

  it('should clear everything at logout', () => {
    const values = watchCounts();
    const modes: CatalogMode[] = [];
    service.mode$.subscribe(mode => modes.push(mode));
    http.expectOne(COUNTS_URL).flush({clientSide: true});

    token$.next(null);

    expect(values).toEqual([null, null]);
    expect(modes).toEqual(['server', 'legacy', 'server']);
  });

  it('should fetch the magic shelf counts on their own', () => {
    const values: unknown[] = [];
    service.magicCounts$.subscribe(value => values.push(value));

    http.expectNone(COUNTS_URL);
    http.expectOne(MAGIC_COUNTS_URL).flush({magicShelves: {3: {status: 'OK', count: 184}}});

    expect(values).toEqual([null, {magicShelves: {3: {status: 'OK', count: 184}}}]);
  });
});
