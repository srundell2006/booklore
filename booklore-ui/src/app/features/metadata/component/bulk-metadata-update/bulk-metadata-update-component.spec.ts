import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {HttpRequest, provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideRouter} from '@angular/router';
import {MessageService} from 'primeng/api';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {NEVER, of} from 'rxjs';
import {BulkMetadataUpdateComponent} from './bulk-metadata-update-component';
import {API_CONFIG} from '../../../../core/config/api-config';
import {AuthService} from '../../../../shared/service/auth.service';
import {RxStompService} from '../../../../shared/websocket/rx-stomp.service';
import {Book} from '../../../book/model/book.model';

const api = (path: string) => `${API_CONFIG.BASE_URL}${path}`;

function apiBook(id: number): Book {
  return {id, libraryId: 1, libraryName: 'Books', metadata: {bookId: id, title: `Book ${id}`, authors: ['Author']}} as Book;
}

describe('BulkMetadataUpdateComponent without the book list', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    window.matchMedia ??= ((query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => undefined, removeListener: () => undefined,
      addEventListener: () => undefined, removeEventListener: () => undefined, dispatchEvent: () => false,
    })) as typeof window.matchMedia;

    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideNoopAnimations(),
        // Selected on the series page, which opens this before the list is loaded
        {provide: DynamicDialogConfig, useValue: {data: {bookIds: [5, 7]}}},
        {provide: DynamicDialogRef, useValue: {close: vi.fn()}},
        {provide: AuthService, useValue: {token$: of('token'), getInternalAccessToken: () => 'token'}},
        {provide: RxStompService, useValue: {connected$: NEVER, watch: () => NEVER}},
        MessageService,
      ]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const isListRequest = (req: HttpRequest<unknown>) => req.url === api('/api/v1/books') && req.method === 'GET';

  it('should count and name the selected books while the book list still downloads', () => {
    const fixture = TestBed.createComponent(BulkMetadataUpdateComponent);
    fixture.detectChanges();

    const text = () => fixture.nativeElement.textContent as string;
    expect(text()).toContain('2 books selected');
    expect(text()).toContain('Editing 2 Books');

    http.expectOne(api('/api/v1/books/5?withDescription=false')).flush(apiBook(5));
    http.expectOne(api('/api/v1/books/7?withDescription=false')).flush(apiBook(7));
    fixture.detectChanges();
    const titles = Array.from(fixture.nativeElement.querySelectorAll('.book-list li') as NodeListOf<HTMLElement>)
      .map(item => item.textContent!.trim());
    expect(titles).toEqual(['Book 5', 'Book 7']);

    // Only the suggestions wait for the list
    http.expectOne(isListRequest).flush([apiBook(5), apiBook(7), {...apiBook(8), metadata: {bookId: 8, title: 'Book 8', authors: ['Other']}}]);
    fixture.detectChanges();
    expect(fixture.componentInstance.allAuthors).toEqual(['Author', 'Other']);
    expect(text()).toContain('2 books selected');
  });
});
