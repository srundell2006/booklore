import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting, TestRequest} from '@angular/common/http/testing';
import {Router} from '@angular/router';
import {MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';
import {of} from 'rxjs';
import {BookService} from './book.service';
import {BookStateService} from './book-state.service';
import {AuthService} from '../../../shared/service/auth.service';
import {API_CONFIG} from '../../../core/config/api-config';
import {Book, ReadStatus} from '../model/book.model';

const BOOKS_URL = `${API_CONFIG.BASE_URL}/api/v1/books`;

function book(id: number): Book {
  return {
    id, libraryId: 1, libraryName: 'Books',
    metadata: {bookId: id, title: `Book ${id}`},
    primaryFile: {id: id * 10, bookId: id, bookType: 'EPUB'},
  } as Book;
}

describe('BookStateService: the list is never partial', () => {
  let service: BookService;
  let state: BookStateService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {provide: AuthService, useValue: {token$: of('token')}},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: Router, useValue: {navigate: vi.fn()}},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ]
    });
    service = TestBed.inject(BookService);
    state = TestBed.inject(BookStateService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const ids = () => state.getCurrentBookState().books?.map(b => b.id);
  const isListRequest = (req: {method: string; url: string}) => req.method === 'GET' && req.url === BOOKS_URL;
  const expectListRequest = (): TestRequest => http.expectOne(isListRequest);
  const unloaded = {books: null, loaded: false, error: null};

  function deleteBooks(...bookIds: number[]): void {
    service.deleteBooks(new Set(bookIds)).subscribe();
    http.expectOne(req => req.method === 'DELETE' && req.url === BOOKS_URL).flush({failedFileDeletions: []});
  }

  function markRead(bookId: number): void {
    service.updateBookReadStatus(bookId, ReadStatus.READ).subscribe();
    http.expectOne(`${BOOKS_URL}/status`).flush([{bookId, readStatus: ReadStatus.READ}]);
  }

  it('should leave the list unloaded after a delete from a card, and download all of it later', () => {
    deleteBooks(2);

    expect(state.getCurrentBookState()).toEqual(unloaded);
    http.expectNone(isListRequest);

    service.bookState$.subscribe();
    expectListRequest().flush([book(1), book(3)]);

    expect(state.getCurrentBookState().loaded).toBe(true);
    expect(ids()).toEqual([1, 3]);
  });

  it('should leave the list unloaded after any other change from a card', () => {
    markRead(2);
    service.togglePhysicalFlag(2, true).subscribe();
    http.expectOne(`${BOOKS_URL}/2/physical?physical=true`).flush(book(2));
    service.updateLastReadTime(2);

    expect(state.getCurrentBookState()).toEqual(unloaded);
    http.expectNone(isListRequest);
  });

  it('should remove a book deleted while the list downloads from the downloaded list, with no refresh', () => {
    service.bookState$.subscribe();
    const download = expectListRequest();

    deleteBooks(2);
    expect(state.getCurrentBookState()).toEqual(unloaded);
    download.flush([book(1), book(2), book(3)]);

    expect(ids()).toEqual([1, 3]);
    http.expectNone(isListRequest);
  });

  it('should download the list once more after a change made while it downloaded', () => {
    service.bookState$.subscribe();
    const download = expectListRequest();

    markRead(2);
    service.togglePhysicalFlag(3, true).subscribe();
    http.expectOne(`${BOOKS_URL}/3/physical?physical=true`).flush(book(3));
    download.flush([book(1), book(2), book(3)]);

    const refresh = expectListRequest();
    refresh.flush([book(1), {...book(2), readStatus: ReadStatus.READ}, {...book(3), isPhysical: true}]);

    expect(state.getCurrentBookState().books?.map(b => [b.id, b.readStatus ?? null, b.isPhysical ?? false])).toEqual([
      [1, null, false], [2, ReadStatus.READ, false], [3, null, true]
    ]);
    http.expectNone(isListRequest);
  });

  it('should apply a websocket change that arrives while the list downloads, with no refresh', () => {
    service.bookState$.subscribe();
    const download = expectListRequest();

    service.handleBookChanges([{kind: 'created', book: book(4)}, {kind: 'removed', ids: [1]}]);
    service.updateLastReadTime(2);
    download.flush([book(1), book(2), book(3)]);

    expect(ids()).toEqual([2, 3, 4]);
    expect(state.getCurrentBookState().books?.find(b => b.id === 2)?.lastReadTime).toBeTruthy();
    http.expectNone(isListRequest);
  });

  it('should not download a list to refresh one that was never loaded', () => {
    service.refreshBooks();

    http.expectNone(isListRequest);
    expect(state.getCurrentBookState()).toEqual(unloaded);
  });

  it('should keep the list when a refresh fails', () => {
    service.bookState$.subscribe();
    expectListRequest().flush([book(1), book(2)]);

    service.refreshBooks();
    expectListRequest().flush(null, {status: 503, statusText: 'Unavailable'});

    expect(ids()).toEqual([1, 2]);
    expect(state.getCurrentBookState().error).toBeTruthy();
  });

  it('should apply changes to a loaded list', () => {
    service.bookState$.subscribe();
    expectListRequest().flush([book(1), book(2), book(3)]);

    markRead(1);
    deleteBooks(2);
    service.handleBookChanges([{kind: 'updated', books: [{...book(3), metadata: {bookId: 3, title: 'Renamed'}}]}]);

    expect(state.getCurrentBookState().books?.map(b => [b.id, b.readStatus ?? null, b.metadata?.title])).toEqual([
      [1, ReadStatus.READ, 'Book 1'], [3, null, 'Renamed']
    ]);
    http.expectNone(isListRequest);
  });

  it('should report every removal, with or without a list', () => {
    const removed: number[][] = [];
    state.removed$.subscribe(batch => removed.push(batch));

    deleteBooks(2, 3);
    service.handleBookChanges([{kind: 'removed', ids: [5]}, {kind: 'created', book: book(6)}]);

    expect(removed).toEqual([[2, 3], [5]]);
  });

  it('should announce websocket changes whether or not the list is loaded', () => {
    const batches: number[] = [];
    service.bookChanges$.subscribe(changes => batches.push(changes.length));

    service.handleBookChanges([{kind: 'created', book: book(4)}, {kind: 'removed', ids: [1]}]);
    service.handleBookChanges([]);

    expect(batches).toEqual([2]);
    expect(state.getCurrentBookState()).toEqual(unloaded);
  });
});

describe('BookService.readBook without the list', () => {
  let service: BookService;
  let router: {navigate: ReturnType<typeof vi.fn>};
  let http: HttpTestingController;

  beforeEach(() => {
    router = {navigate: vi.fn()};
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {provide: AuthService, useValue: {token$: of('token')}},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: Router, useValue: router},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
      ]
    });
    service = TestBed.inject(BookService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('should open the reader for the book it is given', () => {
    service.readBook(book(7));

    expect(router.navigate).toHaveBeenCalledWith(['/ebook-reader/book/7'], undefined);
    http.expectNone(() => true);
  });

  it('should open an alternative format of the book it is given', () => {
    service.readBook(book(7), undefined, 'PDF');

    expect(router.navigate).toHaveBeenCalledWith(['/pdf-reader/book/7'], {queryParams: {bookType: 'PDF'}});
  });

  it('should fetch a book given by id that the list does not hold, then open it', () => {
    service.readBook(8);
    expect(router.navigate).not.toHaveBeenCalled();

    const request = http.expectOne(req => req.url === `${BOOKS_URL}/8`);
    expect(request.request.params.get('withDescription')).toBe('false');
    request.flush({...book(8), primaryFile: {id: 80, bookId: 8, bookType: 'CBX'}});

    expect(router.navigate).toHaveBeenCalledWith(['/cbx-reader/book/8'], undefined);
    http.expectNone(req => req.url === BOOKS_URL);
  });
});
