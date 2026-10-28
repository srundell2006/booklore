import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {BehaviorSubject, firstValueFrom, NEVER, of} from 'rxjs';
import {HttpRequest, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideRouter} from '@angular/router';
import {ConfirmationService, MessageService} from 'primeng/api';
import {MainDashboardComponent} from './main-dashboard.component';
import {BookService} from '../../../book/service/book.service';
import {LibraryService} from '../../../book/service/library.service';
import {DialogLauncherService} from '../../../../shared/services/dialog-launcher.service';
import {UserService} from '../../../settings/user-management/user.service';
import {DashboardConfigService} from '../../services/dashboard-config.service';
import {MagicShelfService} from '../../../magic-shelf/service/magic-shelf.service';
import {BookRuleEvaluatorService} from '../../../magic-shelf/service/book-rule-evaluator.service';
import {PageTitleService} from '../../../../shared/service/page-title.service';
import {DEFAULT_DASHBOARD_CONFIG, ScrollerConfig, ScrollerType} from '../../models/dashboard-config.model';
import {Book} from '../../../book/model/book.model';
import {DashboardRowService} from '../../services/dashboard-row.service';
import {API_CONFIG} from '../../../../core/config/api-config';
import {CatalogWriteInterceptor} from '../../../../core/services/catalog-write.interceptor';
import {AuthService} from '../../../../shared/service/auth.service';
import {RxStompService} from '../../../../shared/websocket/rx-stomp.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {BookDialogHelperService} from '../../../book/components/book-browser/book-dialog-helper.service';
import {MediaToolsService} from '../../../settings/media-tools/media-tools.service';
import {EmailService} from '../../../settings/email-v2/email.service';
import {TaskHelperService} from '../../../settings/task-management/task-helper.service';
import {BookNavigationService} from '../../../book/service/book-navigation.service';
import {BookCardOverlayPreferenceService} from '../../../book/components/book-browser/book-card-overlay-preference.service';
import {BookStateService} from '../../../book/service/book-state.service';

function book(id: number, title: string): Book {
  return {id, libraryId: 1, libraryName: 'Books', metadata: {bookId: id, title}} as Book;
}

describe('MainDashboardComponent magic shelf row', () => {
  let component: MainDashboardComponent;

  // In shelf order the first two are the last two by title
  const books = [book(1, 'Zebra'), book(2, 'Yak'), book(3, 'Apple'), book(4, 'Banana'), book(5, 'Cherry')];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        {provide: BookService, useValue: {bookState$: of({books, loaded: true, error: null})}},
        {provide: LibraryService, useValue: {libraryState$: of({libraries: [], loaded: true, error: null})}},
        {provide: DialogLauncherService, useValue: {}},
        {provide: UserService, useValue: {}},
        {provide: DashboardConfigService, useValue: {config$: NEVER, ready$: NEVER}},
        {provide: MagicShelfService, useValue: {getShelf: vi.fn(() => of({id: 7, name: 'All', filterJson: '{}'})), shelvesState$: NEVER}},
        {provide: BookRuleEvaluatorService, useValue: {evaluateGroup: vi.fn(() => true)}},
        {provide: PageTitleService, useValue: {setPageTitle: vi.fn()}},
        {provide: DashboardRowService, useValue: {}},
      ]
    });
    component = TestBed.runInInjectionContext(() => new MainDashboardComponent());
  });

  function scroller(overrides: Partial<ScrollerConfig>): ScrollerConfig {
    return {id: 'magic', type: ScrollerType.MAGIC_SHELF, title: 'Magic', enabled: true, order: 1, maxItems: 2, magicShelfId: 7, ...overrides};
  }

  it('should sort the whole shelf before taking the first books', async () => {
    const row = await firstValueFrom(component.getBooksForScroller(scroller({sortField: 'title', sortDirection: 'asc'})));

    expect(row.map(b => b.metadata?.title)).toEqual(['Apple', 'Banana']);
  });

  it('should sort descending over the whole shelf', async () => {
    const row = await firstValueFrom(component.getBooksForScroller(scroller({sortField: 'title', sortDirection: 'desc'})));

    expect(row.map(b => b.metadata?.title)).toEqual(['Zebra', 'Yak']);
  });

  it('should keep the shelf order when no sort is set', async () => {
    const row = await firstValueFrom(component.getBooksForScroller(scroller({})));

    expect(row.map(b => b.id)).toEqual([1, 2]);
  });
});

describe('MainDashboardComponent without the book list', () => {
  const api = (path: string) => `${API_CONFIG.BASE_URL}${path}`;
  const ROWS = ['continue-listening', 'continue-reading', 'recently-added', 'random'];
  let fixture: ComponentFixture<MainDashboardComponent>;
  let http: HttpTestingController;
  let userState$: BehaviorSubject<{user: unknown; loaded: boolean; error: null}>;

  const user = {
    id: 1, username: 'reader', name: 'Reader',
    permissions: {admin: false, canManageLibrary: false, canDownload: true, canDeleteBook: true},
    userSettings: {metadataCenterViewMode: 'route'},
  };

  beforeEach(() => {
    // jsdom has no media queries, which the cards' menus read
    window.matchMedia ??= ((query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => undefined, removeListener: () => undefined,
      addEventListener: () => undefined, removeEventListener: () => undefined, dispatchEvent: () => false,
    })) as typeof window.matchMedia;
    userState$ = new BehaviorSubject<{user: unknown; loaded: boolean; error: null}>({user: null, loaded: false, error: null});
    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        provideHttpClient(withInterceptors([CatalogWriteInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        {provide: AuthService, useValue: {token$: of('token'), getInternalAccessToken: () => 'token'}},
        {provide: RxStompService, useValue: {connected$: NEVER, watch: () => NEVER}},
        {provide: UserService, useValue: {userState$, getCurrentUser: () => user, updateUserSetting: vi.fn()}},
        {provide: AppSettingsService, useValue: {appSettings$: of(null)}},
        {provide: DialogLauncherService, useValue: {}},
        {provide: BookDialogHelperService, useValue: {}},
        {provide: MediaToolsService, useValue: {}},
        {provide: EmailService, useValue: {}},
        {provide: TaskHelperService, useValue: {}},
        {provide: BookNavigationService, useValue: {getAvailableBookIds: () => [], setNavigationContext: vi.fn()}},
        {provide: BookCardOverlayPreferenceService, useValue: {showBookTypePill$: of(true)}},
        {provide: PageTitleService, useValue: {setPageTitle: vi.fn()}},
        MessageService,
        ConfirmationService,
      ]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const isListRequest = (req: HttpRequest<unknown>) => req.url.endsWith('/api/v1/books') && req.method === 'GET';
  const rowRequests = () => http.match(req => req.url.startsWith(api('/api/v1/catalog/rows/')));
  const renderedIds = () => Array.from(fixture.nativeElement.querySelectorAll('app-dashboard-scroller') as NodeListOf<HTMLElement>)
    .map(scroller => scroller.querySelectorAll('app-book-card').length);

  function open(): void {
    fixture = TestBed.createComponent(MainDashboardComponent);
    fixture.detectChanges();
    http.expectOne(api('/api/v1/libraries')).flush([{id: 1, name: 'Books', watch: false, paths: []}]);
    http.expectOne(api('/api/magic-shelves')).flush([]);
    userState$.next({user, loaded: true, error: null});
    fixture.detectChanges();
  }

  function answerRows(books: Record<string, Book[]>): void {
    for (const request of rowRequests()) {
      const kind = request.request.url.split('/').pop()!;
      request.flush({books: books[kind] ?? []});
    }
    fixture.detectChanges();
  }

  it('should show the four default rows from the row endpoints, with no request for the book list', () => {
    open();

    const requests = rowRequests();
    expect(requests.map(r => r.request.urlWithParams.replace(api('/api/v1/catalog/rows/'), ''))).toEqual(ROWS.map(kind => `${kind}?size=20`));
    expect(fixture.nativeElement.querySelectorAll('p-skeleton').length).toBeGreaterThan(0);
    requests.forEach((request, i) => request.flush({books: i === 2 ? [book(1, 'One'), book(2, 'Two')] : i === 3 ? [book(3, 'Three')] : []}));
    fixture.detectChanges();

    expect(renderedIds()).toEqual([0, 0, 2, 1]);
    expect(fixture.nativeElement.querySelectorAll('p-skeleton').length).toBe(0);
    http.expectNone(isListRequest);
  });

  it('should take a deleted book off every row at once and keep the list unloaded', async () => {
    open();
    answerRows({'recently-added': [book(1, 'One'), book(2, 'Two')], 'random': [book(2, 'Two'), book(3, 'Three')]});

    TestBed.inject(BookService).deleteBooks(new Set([2])).subscribe();
    http.expectOne(req => req.method === 'DELETE').flush({failedFileDeletions: []});
    fixture.detectChanges();

    expect(renderedIds()).toEqual([0, 0, 1, 1]);
    expect(TestBed.inject(BookStateService).getCurrentBookState()).toEqual({books: null, loaded: false, error: null});

    // The rows are fetched again after the write, all but the random one
    await new Promise(resolve => setTimeout(resolve, 600));
    expect(rowRequests().map(r => r.request.url.split('/').pop())).toEqual(['continue-listening', 'continue-reading', 'recently-added']);
    answerRows({'recently-added': [book(1, 'One')]});
    http.expectNone(isListRequest);
  });

  it('should not fetch the rows again when only a title changes', () => {
    open();
    answerRows({});

    const configService = TestBed.inject(DashboardConfigService);
    configService.saveConfig({scrollers: DEFAULT_DASHBOARD_CONFIG.scrollers.map(s => ({...s, title: `${s.title} (renamed)`}))});
    fixture.detectChanges();

    expect(rowRequests()).toEqual([]);
  });

  it('should not fetch a disabled row, nor the rows of the default config for a user who replaced it', () => {
    const own = {scrollers: DEFAULT_DASHBOARD_CONFIG.scrollers.map(s => ({...s, enabled: s.type !== ScrollerType.LAST_READ}))};
    fixture = TestBed.createComponent(MainDashboardComponent);
    fixture.detectChanges();
    http.expectOne(api('/api/v1/libraries')).flush([{id: 1, name: 'Books', watch: false, paths: []}]);
    http.expectOne(api('/api/magic-shelves')).flush([]);
    expect(rowRequests()).toEqual([]);

    userState$.next({user: {...user, userSettings: {...user.userSettings, dashboardConfig: own}}, loaded: true, error: null});
    fixture.detectChanges();

    expect(rowRequests().map(r => r.request.url.split('/').pop())).toEqual(['continue-listening', 'recently-added', 'random']);
    answerRows({});
    http.expectNone(isListRequest);
  });

  it('should show the welcome screen once the libraries are known to be empty', () => {
    fixture = TestBed.createComponent(MainDashboardComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('p-progressspinner')).toBeTruthy();

    http.expectOne(api('/api/v1/libraries')).flush([]);
    http.expectOne(api('/api/magic-shelves')).flush([]);
    userState$.next({user, loaded: true, error: null});
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.dashboard-no-library')).toBeTruthy();
    expect(rowRequests()).toEqual([]);
    http.expectNone(isListRequest);
  });
});
