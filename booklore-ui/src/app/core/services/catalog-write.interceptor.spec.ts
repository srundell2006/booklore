import {afterEach, beforeEach, describe, expect, it} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {CatalogWriteInterceptor, LocalWrite, LocalWriteService, writeTouchesBook} from './catalog-write.interceptor';
import {API_CONFIG} from '../config/api-config';

describe('CatalogWriteInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let writes: LocalWrite[];
  const url = (path: string) => `${API_CONFIG.BASE_URL}${path}`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([CatalogWriteInterceptor])),
        provideHttpClientTesting(),
      ]
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    writes = [];
    TestBed.inject(LocalWriteService).writes$.subscribe(write => writes.push(write));
  });

  afterEach(() => http.verify());

  it('should report each accepted write to books, shelves, magic shelves and libraries', () => {
    client.post(url('/api/v1/books/status'), {bookIds: [1]}).subscribe();
    http.expectOne(url('/api/v1/books/status')).flush([]);
    client.delete(url('/api/v1/books'), {params: {ids: '1,2'}}).subscribe();
    http.expectOne(url('/api/v1/books?ids=1,2')).flush({});
    client.put(url('/api/v1/shelves/4'), {}).subscribe();
    http.expectOne(url('/api/v1/shelves/4')).flush({});
    client.post(url('/api/magic-shelves'), {}).subscribe();
    http.expectOne(url('/api/magic-shelves')).flush({});
    client.patch(url('/api/v1/libraries/1/watch'), {}).subscribe();
    http.expectOne(url('/api/v1/libraries/1/watch')).flush({});

    expect(writes.map(w => [w.method, w.url.replace(API_CONFIG.BASE_URL, ''), w.changesCatalog])).toEqual([
      ['POST', '/api/v1/books/status', true],
      ['DELETE', '/api/v1/books?ids=1,2', true],
      ['PUT', '/api/v1/shelves/4', true],
      ['POST', '/api/magic-shelves', true],
      ['PATCH', '/api/v1/libraries/1/watch', true],
    ]);
  });

  it('should mark reading progress and viewer settings as changing no count', () => {
    client.post(url('/api/v1/books/progress'), {bookId: 3}).subscribe();
    http.expectOne(url('/api/v1/books/progress')).flush(null);
    client.put(url('/api/v1/books/3/viewer-setting'), {}).subscribe();
    http.expectOne(url('/api/v1/books/3/viewer-setting')).flush(null);
    client.post(url('/api/v1/books/reset-progress'), [3]).subscribe();
    http.expectOne(url('/api/v1/books/reset-progress')).flush([]);

    expect(writes.map(w => w.changesCatalog)).toEqual([false, false, true]);
  });

  it('should ignore reads, failed writes and other endpoints', () => {
    client.get(url('/api/v1/books')).subscribe();
    http.expectOne(url('/api/v1/books')).flush([]);
    client.post(url('/api/v1/books/status'), {}).subscribe({error: () => undefined});
    http.expectOne(url('/api/v1/books/status')).flush(null, {status: 500, statusText: 'Server Error'});
    client.post(url('/api/v1/settings'), {}).subscribe();
    http.expectOne(url('/api/v1/settings')).flush(null);

    expect(writes).toEqual([]);
  });

  it('should tell which writes name a book', () => {
    const write = (path: string, body: unknown = null): LocalWrite => ({method: 'POST', url: url(path), body, changesCatalog: true});

    expect(writeTouchesBook(write('/api/v1/books/7/metadata'), 7)).toBe(true);
    expect(writeTouchesBook(write('/api/v1/books/7'), 7)).toBe(true);
    expect(writeTouchesBook(write('/api/v1/books/77/metadata'), 7)).toBe(false);
    expect(writeTouchesBook(write('/api/v1/books?ids=3,7'), 7)).toBe(true);
    expect(writeTouchesBook(write('/api/v1/books/status', {bookIds: [7], status: 'READ'}), 7)).toBe(true);
    expect(writeTouchesBook(write('/api/v1/books/personal-rating', {ids: [8], rating: 3}), 7)).toBe(false);
    expect(writeTouchesBook(write('/api/v1/books/reset-personal-rating', [7]), 7)).toBe(true);
    expect(writeTouchesBook(write('/api/v1/books/progress', {bookId: 7, dateFinished: null}), 7)).toBe(true);
    expect(writeTouchesBook(write('/api/v1/books/shelves', {bookIds: [1], shelvesToAssign: [7]}), 7)).toBe(false);
  });
});
