import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {HttpRequest, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ActivatedRoute, convertToParamMap, provideRouter} from '@angular/router';
import {ConfirmationService, MessageService} from 'primeng/api';
import {DialogService} from 'primeng/dynamicdialog';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {BehaviorSubject, NEVER, of} from 'rxjs';
import {BookMetadataCenterComponent} from './book-metadata-center.component';
import {MetadataEditorComponent} from './metadata-editor/metadata-editor.component';
import {API_CONFIG} from '../../../../core/config/api-config';
import {CatalogWriteInterceptor} from '../../../../core/services/catalog-write.interceptor';
import {AuthService} from '../../../../shared/service/auth.service';
import {RxStompService} from '../../../../shared/websocket/rx-stomp.service';
import {UserService} from '../../../settings/user-management/user.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {BookService} from '../../../book/service/book.service';
import {Book, ReadStatus} from '../../../book/model/book.model';

const api = (path: string) => `${API_CONFIG.BASE_URL}${path}`;

function apiBook(id: number, overrides: Partial<Book> = {}): Book {
  return {
    id, libraryId: 1, libraryName: 'Books',
    metadata: {bookId: id, title: `Book ${id}`, authors: ['Author'], reviewsLocked: true},
    primaryFile: {id: id * 10, bookId: id, bookType: 'EPUB', fileName: `book-${id}.epub`},
    shelves: [],
    ...overrides,
  } as Book;
}

describe('BookMetadataCenterComponent without the book list', () => {
  let fixture: ComponentFixture<BookMetadataCenterComponent>;
  let http: HttpTestingController;

  const user = {
    id: 1, username: 'reader', name: 'Reader',
    permissions: {admin: false, canEditMetadata: true, canDownload: true, canDeleteBook: true, canEmailBook: false},
    userSettings: {metadataCenterViewMode: 'route'},
  };

  beforeEach(() => {
    window.matchMedia ??= ((query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => undefined, removeListener: () => undefined,
      addEventListener: () => undefined, removeEventListener: () => undefined, dispatchEvent: () => false,
    })) as typeof window.matchMedia;
    // The tab list measures itself; jsdom has no ResizeObserver
    vi.stubGlobal('ResizeObserver', class {
      observe = vi.fn();
      unobserve = vi.fn();
      disconnect = vi.fn();
    });

    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        provideHttpClient(withInterceptors([CatalogWriteInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations(),
        {provide: ActivatedRoute, useValue: {paramMap: of(convertToParamMap({bookId: '7'})), queryParamMap: of(convertToParamMap({}))}},
        {provide: AuthService, useValue: {token$: of('token'), getInternalAccessToken: () => 'token'}},
        {provide: RxStompService, useValue: {connected$: NEVER, watch: () => NEVER}},
        {provide: UserService, useValue: {userState$: new BehaviorSubject({user, loaded: true, error: null}), getCurrentUser: () => user}},
        {provide: AppSettingsService, useValue: {appSettings$: of({diskType: 'LOCAL', similarBookRecommendation: false})}},
        MessageService,
        ConfirmationService,
        DialogService,
      ]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.unstubAllGlobals();
  });

  const isListRequest = (req: HttpRequest<unknown>) => req.url.endsWith('/api/v1/books') && req.method === 'GET';
  const isSeriesRequest = (req: HttpRequest<unknown>) => req.url === api('/api/v1/catalog/series/books');
  const bookRequests = () => http.match(req => req.method === 'GET' && req.url === api('/api/v1/books/7'));

  /** Answers what the default tab asks for besides the book and its series. */
  function flushOtherRequests(): void {
    http.match(req => !isListRequest(req) && !isSeriesRequest(req) && req.url !== api('/api/v1/books/7'))
      .forEach(req => req.flush(req.request.url.endsWith('/sidecar/status') ? {status: 'NOT_APPLICABLE'} : null));
  }

  function inSeries(id: number, seriesNumber: number, seriesName = 'The Expanse'): Book {
    const book = apiBook(id);
    return {...book, metadata: {...book.metadata!, seriesName, seriesNumber}};
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function open(book: Book = apiBook(7)): void {
    fixture = TestBed.createComponent(BookMetadataCenterComponent);
    fixture.detectChanges();
    const requests = bookRequests();
    expect(requests.length).toBe(1);
    expect(requests[0].request.params.get('withDescription')).toBe('true');
    requests[0].flush(book);
    fixture.detectChanges();
    flushOtherRequests();
  }

  it('should show the book from GET /books/{id} with no request for the book list', () => {
    open();

    expect(fixture.nativeElement.textContent).toContain('Book 7');
    http.expectNone(isListRequest);
  });

  it('should fetch the book again after a write to it, and not after one to another book', async () => {
    open();

    TestBed.inject(BookService).updateBookReadStatus(7, ReadStatus.READ).subscribe();
    http.expectOne(api('/api/v1/books/status')).flush([{bookId: 7, readStatus: ReadStatus.READ}]);
    TestBed.inject(BookService).updateBookReadStatus(8, ReadStatus.READ).subscribe();
    http.expectOne(api('/api/v1/books/status')).flush([{bookId: 8, readStatus: ReadStatus.READ}]);
    await new Promise(resolve => setTimeout(resolve, 350));

    const refetch = bookRequests();
    expect(refetch.length).toBe(1);
    refetch[0].flush(apiBook(7, {readStatus: ReadStatus.READ}));
    fixture.detectChanges();
    flushOtherRequests();

    TestBed.inject(BookService).updateBookReadStatus(8, ReadStatus.READ).subscribe();
    http.expectOne(api('/api/v1/books/status')).flush([{bookId: 8, readStatus: ReadStatus.READ}]);
    await new Promise(resolve => setTimeout(resolve, 350));
    expect(bookRequests()).toEqual([]);
    http.expectNone(isListRequest);
  });

  it('should fetch the book again after a websocket change to it', async () => {
    open();

    TestBed.inject(BookService).handleBookChanges([{kind: 'coverUpdated', patches: [{id: 7, coverUpdatedOn: 'now'}]}]);
    await new Promise(resolve => setTimeout(resolve, 350));

    bookRequests().forEach(req => req.flush(apiBook(7)));
    fixture.detectChanges();
    flushOtherRequests();
    http.expectNone(isListRequest);
  });

  it('should open the reviews tab without the book list', async () => {
    open();

    const reviewsTab = fixture.nativeElement.querySelector('p-tab[value="reviews"]') as HTMLElement;
    expect(reviewsTab).toBeTruthy();
    reviewsTab.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-book-reviews')).toBeTruthy();
    flushOtherRequests();
    http.expectNone(isListRequest);
  });

  it('should download the book list only once the Edit tab opens, for its suggestions', async () => {
    open();
    http.expectNone(isListRequest);

    (fixture.nativeElement.querySelector('p-tab[value="edit"]') as HTMLElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-metadata-editor')).toBeTruthy();
    http.expectOne(isListRequest).flush([apiBook(7)]);
    fixture.detectChanges();
    flushOtherRequests();
  });

  it('should keep what is typed in the Edit tab when the book list arrives, and fetch again on a later change to it', async () => {
    open();
    (fixture.nativeElement.querySelector('p-tab[value="edit"]') as HTMLElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const editor = fixture.debugElement.query(By.directive(MetadataEditorComponent)).componentInstance as MetadataEditorComponent;
    const title = editor.metadataForm.get('title')!;
    title.setValue('Typed by user');
    title.markAsDirty();
    http.expectOne(isListRequest).flush([apiBook(7)]);
    fixture.detectChanges();
    flushOtherRequests();
    await new Promise(resolve => setTimeout(resolve, 350));

    const refetches = bookRequests();
    refetches.forEach(req => req.flush(apiBook(7)));
    fixture.detectChanges();
    flushOtherRequests();
    expect(title.value).toBe('Typed by user');
    expect(refetches.length).toBe(0);

    TestBed.inject(BookService).updateLastReadTime(7);
    await new Promise(resolve => setTimeout(resolve, 350));
    const refetch = bookRequests();
    expect(refetch.length).toBe(1);
    refetch[0].flush(apiBook(7));
    fixture.detectChanges();
    flushOtherRequests();
  });

  it('should show the "More in series" tab from the series endpoint with no book list loaded', async () => {
    open(inSeries(7, 2, ' The Expanse '));

    const request = http.expectOne(isSeriesRequest);
    expect(request.request.params.get('name')).toBe('The Expanse');
    expect(request.request.params.get('limit')).toBe('200');
    request.flush({seriesName: 'The Expanse', total: 3, books: [inSeries(5, 1), inSeries(7, 2), inSeries(9, 3)]});
    await settle();
    flushOtherRequests();

    expect(fixture.nativeElement.querySelector('p-tab[value="series"]')).toBeTruthy();
    const cards = Array.from(fixture.nativeElement.querySelectorAll('app-book-card-lite-component img') as NodeListOf<HTMLImageElement>)
      .map(img => img.alt);
    expect(cards).toEqual(['Cover of Book 5', 'Cover of Book 9']);
    http.expectNone(isListRequest);
  });

  it('should draw a coverless book in the "More in series" tab from its title, with no book list loaded', async () => {
    open(inSeries(7, 2));
    const withCover = inSeries(9, 3);
    http.expectOne(isSeriesRequest).flush({seriesName: 'The Expanse', total: 3, books: [
      inSeries(5, 1), inSeries(7, 2), {...withCover, metadata: {...withCover.metadata!, coverUpdatedOn: '2024-01-01T00:00:00Z'}},
    ]});
    await settle();
    flushOtherRequests();

    const covers = Array.from(fixture.nativeElement.querySelectorAll('app-book-card-lite-component img') as NodeListOf<HTMLImageElement>)
      .map(img => img.getAttribute('src'));
    expect(covers.length).toBe(2);
    expect(covers[0]).toMatch(/^data:image\/svg\+xml;base64,/);
    expect(covers[1]).toBe(api('/api/v1/media/book/9/thumbnail?2024-01-01T00:00:00Z&token=token'));
    http.expectNone(isListRequest);
  });

  it('should not fetch the series again when the same book is fetched again, and should when its series changes', async () => {
    open(inSeries(7, 2));
    http.expectOne(isSeriesRequest).flush({seriesName: 'The Expanse', total: 2, books: [inSeries(5, 1), inSeries(7, 2)]});
    await settle();
    flushOtherRequests();

    TestBed.inject(BookService).updateBookReadStatus(7, ReadStatus.READ).subscribe();
    http.expectOne(api('/api/v1/books/status')).flush([{bookId: 7, readStatus: ReadStatus.READ}]);
    await new Promise(resolve => setTimeout(resolve, 350));
    bookRequests().forEach(req => req.flush({...inSeries(7, 2), readStatus: ReadStatus.READ}));
    await settle();
    flushOtherRequests();
    http.expectNone(isSeriesRequest);

    TestBed.inject(BookService).handleBookChanges([{kind: 'updated', books: [inSeries(7, 1, 'Leviathan')]}]);
    await new Promise(resolve => setTimeout(resolve, 350));
    bookRequests().forEach(req => req.flush(inSeries(7, 1, 'Leviathan')));
    await settle();
    flushOtherRequests();
    const refetch = http.expectOne(isSeriesRequest);
    expect(refetch.request.params.get('name')).toBe('Leviathan');
    refetch.flush({seriesName: 'Leviathan', total: 1, books: [inSeries(7, 1, 'Leviathan')]});
    await settle();

    expect(fixture.nativeElement.querySelector('p-tab[value="series"]')).toBeNull();
    http.expectNone(isListRequest);
  });

  it('should show no "More in series" tab, and ask for no series, for a book without one', async () => {
    open(apiBook(7, {metadata: {bookId: 7, title: 'Book 7', seriesName: '  ', reviewsLocked: true}}));
    await settle();

    http.expectNone(isSeriesRequest);
    expect(fixture.nativeElement.querySelector('p-tab[value="series"]')).toBeNull();
    http.expectNone(isListRequest);
  });

  it('should read the book shown, not one from the list', () => {
    open(apiBook(7, {primaryFile: {id: 70, bookId: 7, bookType: 'PDF', fileName: 'book-7.pdf'}}));
    const router = TestBed.inject(BookService)['router'];
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const readButton = Array.from(fixture.nativeElement.querySelectorAll('p-button, p-splitbutton') as NodeListOf<HTMLElement>)
      .find(button => button.classList.contains('read-button-responsive'));
    expect(readButton).toBeTruthy();
    (readButton!.querySelector('button') as HTMLButtonElement).click();

    expect(navigate).toHaveBeenCalledWith(['/pdf-reader/book/7'], undefined);
    http.expectNone(isListRequest);
  });
});
