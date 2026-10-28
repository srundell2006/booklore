import {HttpHandlerFn, HttpInterceptorFn, HttpResponse} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable, Subject} from 'rxjs';
import {tap} from 'rxjs/operators';

/** A write this client made to books, shelves, magic shelves or libraries, once the server accepted it. */
export interface LocalWrite {
  method: string;
  url: string;
  body: unknown;
  /** False for reading progress and viewer settings, which change no sidebar count and no dashboard row. */
  changesCatalog: boolean;
}

/** The writes the interceptor saw. It has no dependencies, so the interceptor can inject it for any request. */
@Injectable({providedIn: 'root'})
export class LocalWriteService {
  private readonly writesSubject = new Subject<LocalWrite>();
  readonly writes$: Observable<LocalWrite> = this.writesSubject.asObservable();

  notify(write: LocalWrite): void {
    this.writesSubject.next(write);
  }
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
// Magic shelves are served without the version segment
const CATALOG_WRITE_URL = /\/api\/(v1\/)?(books|shelves|magic-shelves|libraries)(\/|\?|$)/;
const NON_CATALOG_WRITE_URL = /\/(progress|viewer-setting)(\/|\?|$)/;

/**
 * Tells the catalog counts, the dashboard rows and the detail page about each accepted write, so they fetch
 * again without the book list. Registered innermost, so a request retried after a token refresh counts once.
 */
export const CatalogWriteInterceptor: HttpInterceptorFn = (req, next: HttpHandlerFn) => {
  if (!WRITE_METHODS.has(req.method) || !CATALOG_WRITE_URL.test(req.url)) {
    return next(req);
  }
  const writes = inject(LocalWriteService);
  return next(req).pipe(
    tap(event => {
      if (event instanceof HttpResponse) {
        writes.notify({
          method: req.method,
          url: req.urlWithParams,
          body: req.body,
          changesCatalog: !NON_CATALOG_WRITE_URL.test(req.url),
        });
      }
    })
  );
};

/** Whether a write names the book: in its path, or among the ids of its body. */
export function writeTouchesBook(write: LocalWrite, bookId: number): boolean {
  if (new RegExp(`/books/${bookId}(/|\\?|$)`).test(write.url)) return true;
  // A delete names its books in the query
  const idsParam = /[?&]ids=([^&]*)/.exec(write.url);
  if (idsParam && decodeURIComponent(idsParam[1]).split(',').some(id => +id === bookId)) return true;

  const body = write.body;
  if (Array.isArray(body)) return body.some(id => +id === bookId);
  if (body && typeof body === 'object' && !(body instanceof FormData)) {
    const fields = body as Record<string, unknown>;
    if (fields['bookId'] != null && +(fields['bookId'] as number) === bookId) return true;
    return ['bookIds', 'ids'].some(key => Array.isArray(fields[key]) && (fields[key] as unknown[]).some(id => id != null && +(id as number) === bookId));
  }
  return false;
}
