import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {HttpRequest, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideRouter} from '@angular/router';
import {MessageService} from 'primeng/api';
import {Tooltip} from 'primeng/tooltip';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {NEVER, of} from 'rxjs';
import {AppMenuComponent} from './app.menu.component';
import {API_CONFIG} from '../../../../core/config/api-config';
import {CatalogWriteInterceptor} from '../../../../core/services/catalog-write.interceptor';
import {AuthService} from '../../../service/auth.service';
import {RxStompService} from '../../../websocket/rx-stomp.service';
import {UserService} from '../../../../features/settings/user-management/user.service';
import {VersionService} from '../../../service/version.service';
import {LibraryShelfMenuService} from '../../../../features/book/service/library-shelf-menu.service';
import {DialogLauncherService} from '../../../services/dialog-launcher.service';
import {BookDialogHelperService} from '../../../../features/book/components/book-browser/book-dialog-helper.service';
import {LocalStorageService} from '../../../service/local-storage.service';
import {BookStateService} from '../../../../features/book/service/book-state.service';
import {Book} from '../../../../features/book/model/book.model';
import {CatalogCounts, MagicCounts} from '../../../../features/book/model/catalog.model';

const api = (path: string) => `${API_CONFIG.BASE_URL}${path}`;

const user = {
  id: 1, username: 'reader', name: 'Reader',
  permissions: {admin: false, canManageLibrary: false},
  userSettings: {},
};

const COUNTS: CatalogCounts = {
  clientSide: false, allBooks: 1234, series: 7, unshelved: 3,
  libraries: {1: 1200, 3: 34}, shelves: {4: 2},
};

const MAGIC_COUNTS: MagicCounts = {
  magicShelves: {
    3: {status: 'OK', count: 184},
    2: {status: 'UNSUPPORTED', count: null, reasons: ['fileType:excludes_all']},
    8: {status: 'INVALID', count: 0},
    11: {status: 'ERROR', count: null},
  }
};

const translations = {
  layout: {
    menu: {
      dashboard: 'Dashboard', allBooks: 'All Books', series: 'Series', authors: 'Authors', notebook: 'Notebook', unshelved: 'Unshelved',
      countUnsupported: 'Not counted by the server: {{reasons}}', countError: 'Count failed',
    }
  }
};

describe('AppMenuComponent counts without the book list', () => {
  let fixture: ComponentFixture<AppMenuComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {en: translations}, translocoConfig: {availableLangs: ['en'], defaultLang: 'en'}})],
      providers: [
        provideHttpClient(withInterceptors([CatalogWriteInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations(),
        {provide: AuthService, useValue: {token$: of('token'), getInternalAccessToken: () => 'token'}},
        {provide: RxStompService, useValue: {connected$: NEVER, watch: () => NEVER}},
        {provide: UserService, useValue: {userState$: of({user, loaded: true, error: null}), getCurrentUser: () => user}},
        {provide: VersionService, useValue: {getVersion: () => of(null)}},
        {
          provide: LibraryShelfMenuService, useValue: {
            initializeLibraryMenuItems: () => [], initializeShelfMenuItems: () => [], initializeMagicShelfMenuItems: () => []
          }
        },
        {provide: DialogLauncherService, useValue: {}},
        {provide: BookDialogHelperService, useValue: {}},
        {provide: LocalStorageService, useValue: {get: () => null, set: vi.fn()}},
        MessageService,
      ]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const isListRequest = (req: HttpRequest<unknown>) => req.url.endsWith('/api/v1/books') && req.method === 'GET';

  function open(): void {
    fixture = TestBed.createComponent(AppMenuComponent);
    fixture.detectChanges();
    http.expectOne(api('/api/v1/authors/count')).flush({count: 12});
    http.expectOne(api('/api/v1/libraries')).flush([
      {id: 1, name: 'Books', watch: false, paths: []},
      {id: 3, name: 'Comics', watch: false, paths: []},
    ]);
    http.expectOne(api('/api/v1/shelves')).flush([
      {id: 4, name: 'Mine', userId: 1, bookCount: 99},
      {id: 9, name: 'Theirs', userId: 2, bookCount: 5, publicShelf: true},
    ]);
    http.expectOne(api('/api/magic-shelves')).flush([
      {id: 3, name: 'Counted', filterJson: '{"join":"and","rules":[]}'},
      {id: 2, name: 'Uncountable', filterJson: '{"join":"and","rules":[{"field":"title","operator":"starts_with","value":"A"}]}'},
      {id: 8, name: 'Broken', filterJson: 'not json'},
      {id: 11, name: 'Failed', filterJson: '{"join":"and","rules":[]}'},
    ]);
    fixture.detectChanges();
  }

  function answerCounts(): void {
    http.expectOne(api('/api/v1/catalog/counts')).flush(COUNTS);
    http.expectOne(api('/api/v1/catalog/magic-counts')).flush(MAGIC_COUNTS);
    fixture.detectChanges();
  }

  /** Each menu entry as "label=count", the count as the entry shows it. */
  function shownCounts(): string[] {
    const entries = fixture.nativeElement.querySelectorAll('.menu-item-container') as NodeListOf<HTMLElement>;
    return Array.from(entries)
      .filter(entry => entry.querySelector('.menu-item-text'))
      .map(entry => {
        const label = entry.querySelector('.menu-item-text')!.textContent!.trim();
        const count = entry.querySelector('p.book-count')?.textContent ?? entry.querySelector('p-button')?.textContent ?? '';
        return `${label}=${count.trim()}`;
      });
  }

  it('should show the server counts and never request the book list', () => {
    open();
    answerCounts();

    expect(shownCounts()).toEqual([
      'Dashboard=', 'All Books=1K', 'Series=7', 'Authors=12', 'Notebook=',
      'Comics=34', 'Books=1K',
      'Unshelved=3', 'Mine=2', 'Theirs=5',
      'Broken=0', 'Counted=184', 'Failed=–', 'Uncountable=–',
    ]);
    http.expectNone(isListRequest);
  });

  it('should leave a count blank while it loads', () => {
    open();

    expect(shownCounts()).toEqual([
      'Dashboard=', 'All Books=', 'Series=', 'Authors=12', 'Notebook=',
      'Comics=', 'Books=',
      'Unshelved=', 'Mine=', 'Theirs=',
      'Broken=', 'Counted=', 'Failed=', 'Uncountable=',
    ]);
    answerCounts();
    http.expectNone(isListRequest);
  });

  it('should say why a magic shelf has no count', () => {
    open();
    answerCounts();

    const tooltips = fixture.debugElement.queryAll(By.directive(Tooltip))
      .map(element => element.injector.get(Tooltip).content)
      .filter(content => !!content);
    expect(tooltips).toEqual(['Count failed', 'Not counted by the server: fileType:excludes_all']);
  });

  it('should count an unsupported shelf from a list some page already loaded', () => {
    open();
    answerCounts();

    TestBed.inject(BookStateService).setLoaded([
      {id: 1, libraryId: 1, libraryName: 'Books', metadata: {bookId: 1, title: 'Apple'}} as Book,
      {id: 2, libraryId: 1, libraryName: 'Books', metadata: {bookId: 2, title: 'Banana'}} as Book,
    ]);
    fixture.detectChanges();

    expect(shownCounts().slice(-4)).toEqual(['Broken=0', 'Counted=184', 'Failed=2', 'Uncountable=1']);
    http.expectNone(isListRequest);
  });

  it('should count from the book list when the server asks for it', () => {
    open();
    http.expectOne(api('/api/v1/catalog/counts')).flush({clientSide: true});
    http.expectOne(api('/api/v1/catalog/magic-counts')).flush({
      magicShelves: Object.fromEntries([3, 2, 8, 11].map(id => [id, {status: 'UNSUPPORTED', count: null, reasons: ['restrictions']}]))
    });
    fixture.detectChanges();

    http.expectOne(isListRequest).flush([
      {id: 1, libraryId: 1, libraryName: 'Books', metadata: {bookId: 1, title: 'Apple', seriesName: 'Fruit'}, shelves: [{id: 4, name: 'Mine'}]},
      {id: 2, libraryId: 3, libraryName: 'Comics', metadata: {bookId: 2, title: 'Banana'}},
    ]);
    fixture.detectChanges();

    expect(shownCounts()).toEqual([
      'Dashboard=', 'All Books=2', 'Series=1', 'Authors=12', 'Notebook=',
      'Comics=1', 'Books=1',
      'Unshelved=1', 'Mine=1', 'Theirs=5',
      'Broken=0', 'Counted=2', 'Failed=2', 'Uncountable=1',
    ]);
  });
});
