import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpParameterCodec, HttpParams} from '@angular/common/http';
import {asyncScheduler, merge, Observable, Subscription, throwError, timer} from 'rxjs';
import {catchError, debounceTime, filter, map, throttleTime} from 'rxjs/operators';
import {API_CONFIG} from '../../../core/config/api-config';
import {LocalWriteService} from '../../../core/services/catalog-write.interceptor';
import {BookService} from './book.service';
import {BookStateService} from './book-state.service';
import {isAppErrorBody} from './catalog-counts.service';
import {Book} from '../model/book.model';
import {SeriesBooks} from '../model/catalog.model';

const LOCAL_WRITE_DEBOUNCE_MS = 500;
/** Shorter than for the counts and the dashboard rows: the user is looking at these books. */
const SERVER_CHANGE_THROTTLE_MS = 10_000;
/** After the last delay, retries go on at that pace. */
const RETRY_DELAYS_MS = [5_000, 15_000, 60_000];

/**
 * 'loading' before the first answer; the books shown stay until the next answer replaces them. 'error' when the
 * first fetch failed with an error the server meant, which is not retried; other errors are retried meanwhile.
 */
export interface SeriesBooksState {
  status: 'loading' | 'ready' | 'error';
  seriesName: string | null;
  total: number;
  books: Book[];
}

/** Encodes every reserved character: Angular's default codec leaves '+' as it is, which the server reads as a space. */
const QUERY_CODEC: HttpParameterCodec = {
  encodeKey: key => encodeURIComponent(key),
  encodeValue: value => encodeURIComponent(value),
  decodeKey: key => decodeURIComponent(key),
  decodeValue: value => decodeURIComponent(value),
};

function isOlderServer(error: HttpErrorResponse): boolean {
  return error.status === 404 && isAppErrorBody(error.error);
}

function isTransient(error: HttpErrorResponse): boolean {
  return error.status === 0 || error.status >= 500 || (error.status === 404 && !isAppErrorBody(error.error));
}

/** What the server matches a series on: its name trimmed as JavaScript trims, in lower case. */
function seriesKey(name: string): string {
  return name.trim().toLowerCase();
}

/**
 * The series computed from the book list, as the series page did before the endpoint: the books whose trimmed series
 * name equals the name ignoring case, by number with the books without one last. The sort is stable, so ties keep
 * the list's order.
 */
export function seriesFromList(books: Book[], name: string, limit?: number): SeriesBooks {
  const key = seriesKey(name);
  const inSeries = key === '' ? [] : books
    .filter(book => book.metadata?.seriesName != null && seriesKey(book.metadata.seriesName) === key)
    .sort((a, b) => (a.metadata?.seriesNumber ?? Number.MAX_SAFE_INTEGER) - (b.metadata?.seriesNumber ?? Number.MAX_SAFE_INTEGER));
  return {
    seriesName: inSeries[0]?.metadata?.seriesName ?? null,
    total: inSeries.length,
    books: limit != null ? inSeries.slice(0, limit) : inSeries,
  };
}

/** The books of one series, from the server, so the series page and the book page do not download the book list. */
@Injectable({
  providedIn: 'root',
})
export class SeriesBooksService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/catalog/series/books`;

  private http = inject(HttpClient);
  private bookService = inject(BookService);
  private bookStateService = inject(BookStateService);
  private localWriteService = inject(LocalWriteService);

  /**
   * The first books of the series named (the server's default when no limit is given), in series order; total
   * counts them all. On a server without the endpoint (404 with the app's error body), the series is computed from
   * the book list, which downloads it, and follows the list's changes.
   */
  getSeriesBooks(name: string, limit?: number): Observable<SeriesBooks> {
    return this.fetch(name, limit).pipe(
      catchError((error: HttpErrorResponse) => isOlderServer(error) ? this.fromBookList(name, limit) : throwError(() => error))
    );
  }

  /**
   * The books of a series, kept current while subscribed: fetched again after a local write and after the server
   * reports changed books, keeping the books shown meanwhile. A removed book leaves at once. On a server without the
   * endpoint the series is computed from the book list, as before the endpoint.
   */
  series$(name: string, limit?: number): Observable<SeriesBooksState> {
    const refresh$ = merge(
      this.localWriteService.writes$.pipe(filter(write => write.changesCatalog), debounceTime(LOCAL_WRITE_DEBOUNCE_MS)),
      this.bookService.bookChanges$.pipe(throttleTime(SERVER_CHANGE_THROTTLE_MS, asyncScheduler, {leading: true, trailing: true}))
    );

    return new Observable<SeriesBooksState>(subscriber => {
      let series: SeriesBooks | null = null;
      let legacyMode = false;
      let failures = 0;
      let request: Subscription | null = null;
      let retry: Subscription | null = null;
      // Removed books, kept out of the answers of the fetches started up to then, which may have read them
      const removedIds = new Set<number>();
      const subscriptions = new Subscription();

      const emit = (status: SeriesBooksState['status']) => subscriber.next({
        status,
        seriesName: series?.seriesName ?? null,
        total: series?.total ?? 0,
        books: series?.books ?? [],
      });

      const startLegacy = () => {
        legacyMode = true;
        subscriptions.add(this.fromBookList(name, limit).subscribe(fromList => {
          series = fromList;
          emit('ready');
        }));
      };

      const fetch = () => {
        if (legacyMode) return;
        retry?.unsubscribe();
        retry = null;
        request?.unsubscribe();
        const removedBefore = [...removedIds];
        request = this.fetch(name, limit).subscribe({
          next: response => {
            failures = 0;
            series = withoutBooks(response, removedIds);
            // Once a fetch started after a removal has answered, later ones read the server after it
            removedBefore.forEach(id => removedIds.delete(id));
            emit('ready');
          },
          error: (error: HttpErrorResponse) => {
            if (isOlderServer(error)) {
              startLegacy();
              return;
            }
            if (isTransient(error)) {
              // The books shown stay, or the page keeps loading, until a retry answers
              const delay = RETRY_DELAYS_MS[Math.min(failures, RETRY_DELAYS_MS.length - 1)];
              failures++;
              retry = timer(delay).subscribe(() => fetch());
            } else if (!series) {
              emit('error');
            }
          }
        });
      };

      emit('loading');
      fetch();
      subscriptions.add(refresh$.subscribe(() => fetch()));
      subscriptions.add(this.bookStateService.removed$.subscribe(ids => {
        if (!legacyMode) ids.forEach(id => removedIds.add(id));
        if (!series) return;
        const fewer = withoutBooks(series, new Set(ids));
        if (fewer === series) return;
        series = fewer;
        emit('ready');
      }));

      return () => {
        subscriptions.unsubscribe();
        request?.unsubscribe();
        retry?.unsubscribe();
      };
    });
  }

  private fetch(name: string, limit?: number): Observable<SeriesBooks> {
    let params = new HttpParams({encoder: QUERY_CODEC}).set('name', name);
    if (limit != null) params = params.set('limit', limit);
    return this.http.get<SeriesBooks | null>(this.url, {params}).pipe(
      map(response => ({
        seriesName: response?.seriesName ?? null,
        total: response?.total ?? response?.books?.length ?? 0,
        books: response?.books ?? [],
      }))
    );
  }

  private fromBookList(name: string, limit?: number): Observable<SeriesBooks> {
    return this.bookService.bookState$.pipe(
      filter(state => state.loaded && !!state.books),
      map(state => seriesFromList(state.books!, name, limit))
    );
  }
}

/** The series without the given books, counted out of the total too; the same object when it held none of them. */
function withoutBooks(series: SeriesBooks, ids: Set<number>): SeriesBooks {
  if (ids.size === 0 || !series.books.some(book => ids.has(book.id))) return series;
  const books = series.books.filter(book => !ids.has(book.id));
  return {...series, total: Math.max(series.total - (series.books.length - books.length), books.length), books};
}
