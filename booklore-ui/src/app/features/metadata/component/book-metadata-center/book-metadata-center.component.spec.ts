import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {HttpRequest, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {ActivatedRoute, convertToParamMap, provideRouter} from '@angular/router';
import {ConfirmationService, MessageService} from 'primeng/api';
import {DialogService} from 'primeng/dynamicdialog';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {BehaviorSubject, NEVER, of} from 'rxjs';
import {BookMetadataCenterComponent} from './book-metadata-center.component';
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

  afterEach(() => http.verify());

  const isListRequest = (req: HttpRequest<unknown>) => req.url.endsWith('/api/v1/books') && req.method === 'GET';
  const bookRequests = () => http.match(req => req.method === 'GET' && req.url === api('/api/v1/books/7'));

  function open(book: Book = apiBook(7)): void {
    fixture = TestBed.createComponent(BookMetadataCenterComponent);
    fixture.detectChanges();
    const requests = bookRequests();
    expect(requests.length).toBe(1);
    expect(requests[0].request.params.get('withDescription')).toBe('true');
    requests[0].flush(book);
    fixture.detectChanges();
    // What the default tab asks for besides the book
    http.match(req => !isListRequest(req) && req.url !== api('/api/v1/books/7')).forEach(req => req.flush(null));
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
    http.match(req => !isListRequest(req) && req.url !== api('/api/v1/books/7')).forEach(req => req.flush(null));

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
    http.match(req => !isListRequest(req) && req.url !== api('/api/v1/books/7')).forEach(req => req.flush(null));
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
