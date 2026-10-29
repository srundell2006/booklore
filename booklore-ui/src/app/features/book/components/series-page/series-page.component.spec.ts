import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {HttpRequest, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ActivatedRoute, convertToParamMap, provideRouter} from '@angular/router';
import {ConfirmationService, MessageService} from 'primeng/api';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {BehaviorSubject, NEVER, of} from 'rxjs';
import {SeriesPageComponent} from './series-page.component';
import {BookCardComponent} from '../book-browser/book-card/book-card.component';
import {API_CONFIG} from '../../../../core/config/api-config';
import {CatalogWriteInterceptor} from '../../../../core/services/catalog-write.interceptor';
import {AuthService} from '../../../../shared/service/auth.service';
import {RxStompService} from '../../../../shared/websocket/rx-stomp.service';
import {UserService} from '../../../settings/user-management/user.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {BookDialogHelperService} from '../book-browser/book-dialog-helper.service';
import {MediaToolsService} from '../../../settings/media-tools/media-tools.service';
import {EmailService} from '../../../settings/email-v2/email.service';
import {TaskHelperService} from '../../../settings/task-management/task-helper.service';
import {BookNavigationService} from '../../service/book-navigation.service';
import {BookCardOverlayPreferenceService} from '../book-browser/book-card-overlay-preference.service';
import {BookService} from '../../service/book.service';
import {Book, ReadStatus} from '../../model/book.model';
import {SeriesBooks} from '../../model/catalog.model';

const api = (path: string) => `${API_CONFIG.BASE_URL}${path}`;
const SERIES_URL = api('/api/v1/catalog/series/books');

function book(id: number, seriesName: string, seriesNumber?: number): Book {
  return {
    id, libraryId: 1, libraryName: 'Books',
    metadata: {bookId: id, title: `Book ${id}`, seriesName, seriesNumber, authors: ['Author']},
    primaryFile: {id: id * 10, bookId: id, bookType: 'EPUB', fileName: `book-${id}.epub`},
    shelves: [],
  } as Book;
}

const translations = {
  book: {
    seriesPage: {
      truncated: 'Showing the first {{ shown }} of {{ total }} books',
      noBooksFound: 'No books found for this series.',
      loadingSeriesDetails: 'Loading series details...',
    }
  }
};

describe('SeriesPageComponent without the book list', () => {
  let fixture: ComponentFixture<SeriesPageComponent>;
  let http: HttpTestingController;

  const user = {
    id: 1, username: 'reader', name: 'Reader',
    permissions: {admin: false, canDeleteBook: true, canDownload: true},
    userSettings: {metadataCenterViewMode: 'route'},
  };

  beforeEach(() => {
    // jsdom has no media queries, which the cards' menus read
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
      imports: [TranslocoTestingModule.forRoot({langs: {en: translations}, translocoConfig: {availableLangs: ['en'], defaultLang: 'en'}})],
      providers: [
        provideHttpClient(withInterceptors([CatalogWriteInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations(),
        // The name is encoded in the route, as the links encode it
        {provide: ActivatedRoute, useValue: {paramMap: of(convertToParamMap({seriesName: encodeURIComponent('The Expanse')}))}},
        {provide: AuthService, useValue: {token$: of('token'), getInternalAccessToken: () => 'token'}},
        {provide: RxStompService, useValue: {connected$: NEVER, watch: () => NEVER}},
        {provide: UserService, useValue: {userState$: new BehaviorSubject({user, loaded: true, error: null}), getCurrentUser: () => user}},
        {provide: AppSettingsService, useValue: {appSettings$: of(null)}},
        {provide: BookDialogHelperService, useValue: {}},
        {provide: MediaToolsService, useValue: {}},
        {provide: EmailService, useValue: {}},
        {provide: TaskHelperService, useValue: {}},
        {provide: BookNavigationService, useValue: {getAvailableBookIds: () => [], setNavigationContext: vi.fn()}},
        {provide: BookCardOverlayPreferenceService, useValue: {showBookTypePill$: of(true)}},
        MessageService,
        ConfirmationService,
      ]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.unstubAllGlobals();
  });

  const isListRequest = (req: HttpRequest<unknown>) => req.url.endsWith('/api/v1/books') && req.method === 'GET';
  const isSeriesRequest = (req: HttpRequest<unknown>) => req.url === SERIES_URL && req.params.get('name') === 'The Expanse';
  const shownIds = () => fixture.debugElement.queryAll(By.directive(BookCardComponent))
    .map(card => (card.componentInstance as BookCardComponent).book.id);
  const text = () => fixture.nativeElement.textContent as string;

  /** Answers the description of the first book, which the page fetches with it. */
  function flushDescriptions(): void {
    http.match(req => req.method === 'GET' && /\/api\/v1\/books\/\d+$/.test(req.url))
      .forEach(req => req.flush(book(+req.request.url.split('/').pop()!, 'The Expanse')));
  }

  function answer(series: SeriesBooks): void {
    http.expectOne(isSeriesRequest).flush(series);
    fixture.detectChanges();
    flushDescriptions();
    fixture.detectChanges();
  }

  function open(): void {
    fixture = TestBed.createComponent(SeriesPageComponent);
    fixture.detectChanges();
  }

  it('should show the books from the series endpoint in its order, with no request for the book list', () => {
    open();
    expect(fixture.nativeElement.querySelector('.loading-state')).toBeTruthy();
    expect(text()).not.toContain('No books found');

    answer({seriesName: 'The Expanse', total: 3, books: [book(3, 'The Expanse', 1), book(1, 'The Expanse', 2), book(2, 'The Expanse')]});

    expect(shownIds()).toEqual([3, 1, 2]);
    expect(fixture.nativeElement.querySelector('.series-title').textContent.trim()).toBe('The Expanse');
    expect(fixture.nativeElement.querySelector('.truncation-note')).toBeNull();
    http.expectNone(isListRequest);
  });

  it('should fetch the series again after a local write, keeping the books shown meanwhile', async () => {
    open();
    answer({seriesName: 'The Expanse', total: 2, books: [book(1, 'The Expanse', 1), book(2, 'The Expanse', 2)]});

    TestBed.inject(BookService).updateBookReadStatus([1, 2], ReadStatus.READ).subscribe();
    http.expectOne(api('/api/v1/books/status')).flush([{bookId: 1, readStatus: ReadStatus.READ}, {bookId: 2, readStatus: ReadStatus.READ}]);
    await new Promise(resolve => setTimeout(resolve, 600));
    fixture.detectChanges();
    expect(shownIds()).toEqual([1, 2]);

    answer({seriesName: 'The Expanse', total: 3, books: [book(1, 'The Expanse', 1), book(2, 'The Expanse', 2), book(4, 'The Expanse', 3)]});

    expect(shownIds()).toEqual([1, 2, 4]);
    http.expectNone(isListRequest);
  });

  it('should take a deleted book off the page at once', () => {
    open();
    answer({seriesName: 'The Expanse', total: 2, books: [book(1, 'The Expanse', 1), book(2, 'The Expanse', 2)]});

    TestBed.inject(BookService).deleteBooks(new Set([2])).subscribe();
    http.expectOne(req => req.method === 'DELETE').flush({failedFileDeletions: []});
    fixture.detectChanges();

    expect(shownIds()).toEqual([1]);
    http.expectNone(isListRequest);
  });

  it('should say when only the first books of a long series are shown', () => {
    open();
    answer({seriesName: 'The Expanse', total: 12725, books: [book(1, 'The Expanse', 1), book(2, 'The Expanse', 2)]});

    expect(fixture.nativeElement.querySelector('.truncation-note').textContent.trim()).toBe('Showing the first 2 of 12725 books');
  });

  it('should show that no books were found once the server answers with none', () => {
    open();
    answer({seriesName: null, total: 0, books: []});

    expect(fixture.nativeElement.querySelector('.empty-state').textContent.trim()).toBe('No books found for this series.');
    expect(fixture.nativeElement.querySelector('.loading-state')).toBeNull();
  });

  it('should compute the series from the book list on a server without the endpoint', () => {
    open();

    http.expectOne(isSeriesRequest).flush({status: 404, message: 'Resource not found'}, {status: 404, statusText: 'Not Found'});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.loading-state')).toBeTruthy();
    http.expectOne(isListRequest).flush([
      book(5, 'The Expanse'), book(6, 'Other', 1), book(7, ' the expanse ', 2), book(8, 'The Expanse', 1),
    ]);
    fixture.detectChanges();
    flushDescriptions();
    fixture.detectChanges();

    expect(shownIds()).toEqual([8, 7, 5]);
  });
});
