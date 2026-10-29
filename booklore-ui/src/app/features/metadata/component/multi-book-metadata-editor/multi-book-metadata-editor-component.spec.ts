import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {HttpRequest, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideRouter} from '@angular/router';
import {ConfirmationService, MessageService} from 'primeng/api';
import {DialogService, DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {BehaviorSubject, NEVER, of} from 'rxjs';
import {MultiBookMetadataEditorComponent} from './multi-book-metadata-editor-component';
import {MetadataEditorComponent} from '../book-metadata-center/metadata-editor/metadata-editor.component';
import {API_CONFIG} from '../../../../core/config/api-config';
import {CatalogWriteInterceptor} from '../../../../core/services/catalog-write.interceptor';
import {AuthService} from '../../../../shared/service/auth.service';
import {RxStompService} from '../../../../shared/websocket/rx-stomp.service';
import {UserService} from '../../../settings/user-management/user.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {Book} from '../../../book/model/book.model';

const api = (path: string) => `${API_CONFIG.BASE_URL}${path}`;

function apiBook(id: number): Book {
  return {
    id, libraryId: 1, libraryName: 'Books',
    metadata: {bookId: id, title: `Book ${id}`, authors: ['Author']},
    primaryFile: {id: id * 10, bookId: id, bookType: 'EPUB', fileName: `book-${id}.epub`},
    shelves: [],
  } as Book;
}

describe('MultiBookMetadataEditorComponent without the book list', () => {
  let fixture: ComponentFixture<MultiBookMetadataEditorComponent>;
  let http: HttpTestingController;

  const user = {
    id: 1, username: 'editor', name: 'Editor',
    permissions: {admin: false, canEditMetadata: true},
    userSettings: {metadataCenterViewMode: 'route', autoSaveMetadata: false},
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
        // Selected on the series page in this order
        {provide: DynamicDialogConfig, useValue: {data: {bookIds: [9, 5]}}},
        {provide: DynamicDialogRef, useValue: {close: vi.fn()}},
        {provide: AuthService, useValue: {token$: of('token'), getInternalAccessToken: () => 'token'}},
        {provide: RxStompService, useValue: {connected$: NEVER, watch: () => NEVER}},
        {provide: UserService, useValue: {userState$: new BehaviorSubject({user, loaded: true, error: null}), getCurrentUser: () => user}},
        {provide: AppSettingsService, useValue: {appSettings$: of({diskType: 'LOCAL'})}},
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

  const isListRequest = (req: HttpRequest<unknown>) => req.url === api('/api/v1/books') && req.method === 'GET';
  const bookRequests = (id: number) => http.match(req => req.method === 'GET' && req.url === api(`/api/v1/books/${id}`));
  const editor = () => fixture.debugElement.query(By.directive(MetadataEditorComponent)).componentInstance as MetadataEditorComponent;
  const wait = () => new Promise(resolve => setTimeout(resolve, 350));

  async function open(): Promise<void> {
    fixture = TestBed.createComponent(MultiBookMetadataEditorComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function answer(id: number): void {
    const requests = bookRequests(id);
    expect(requests.length).toBe(1);
    expect(requests[0].request.params.get('withDescription')).toBe('true');
    requests[0].flush(apiBook(id));
    fixture.detectChanges();
  }

  it('should show the first book by id while the book list still downloads', async () => {
    await open();
    answer(5);

    expect(fixture.nativeElement.querySelector('.panel-description').textContent).toContain('2 books selected');
    expect(editor().metadataForm.get('title')!.value).toBe('Book 5');
    expect(fixture.componentInstance.disableNext).toBe(false);

    fixture.componentInstance.handleNextBook();
    fixture.detectChanges();
    answer(9);
    expect(editor().metadataForm.get('title')!.value).toBe('Book 9');

    // The editor's suggestions still come from the list, in the background
    http.expectOne(isListRequest).flush([apiBook(5), apiBook(9)]);
  });

  it('should fetch the book again after a save, with no book list loaded', async () => {
    await open();
    answer(5);

    editor().onSave();
    http.expectOne(req => req.method === 'PUT' && req.url === api('/api/v1/books/5/metadata'))
      .flush({...apiBook(5).metadata, title: 'Saved'});
    await wait();
    answer(5);

    // The download of the list, for the suggestions, is left unanswered
    http.match(isListRequest);
  });

  it('should keep what is typed when the book list arrives', async () => {
    await open();
    answer(5);

    const title = editor().metadataForm.get('title')!;
    title.setValue('Typed by user');
    title.markAsDirty();
    http.expectOne(isListRequest).flush([apiBook(5), apiBook(9)]);
    fixture.detectChanges();
    await wait();

    expect(bookRequests(5)).toEqual([]);
    expect(title.value).toBe('Typed by user');
  });
});
