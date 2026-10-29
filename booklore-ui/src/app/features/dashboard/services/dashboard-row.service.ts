import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {asyncScheduler, EMPTY, merge, Observable, Subscription, timer} from 'rxjs';
import {debounceTime, filter, throttleTime} from 'rxjs/operators';
import {API_CONFIG} from '../../../core/config/api-config';
import {LocalWriteService} from '../../../core/services/catalog-write.interceptor';
import {BookService} from '../../book/service/book.service';
import {BookStateService} from '../../book/service/book-state.service';
import {isAppErrorBody} from '../../book/service/catalog-counts.service';
import {Book} from '../../book/model/book.model';
import {CatalogRowKind, CatalogRowResponse} from '../../book/model/catalog.model';
import {ScrollerType} from '../models/dashboard-config.model';

const LOCAL_WRITE_DEBOUNCE_MS = 500;
const SERVER_CHANGE_THROTTLE_MS = 30_000;
/** After the last delay, retries go on at that pace. */
const RETRY_DELAYS_MS = [5_000, 15_000, 60_000];

/** 'loading' before the first answer; the books shown stay until the next answer replaces them. */
export interface DashboardRowState {
  status: 'loading' | 'ready' | 'error';
  books: Book[];
}

const ROW_KINDS: Partial<Record<ScrollerType, CatalogRowKind>> = {
  [ScrollerType.LAST_READ]: 'continue-reading',
  [ScrollerType.LAST_LISTENED]: 'continue-listening',
  [ScrollerType.LATEST_ADDED]: 'recently-added',
  [ScrollerType.RANDOM]: 'random',
};

/** The server row for a fixed row type; a magic shelf row has none and is computed from the book list. */
export function catalogRowKind(type: ScrollerType): CatalogRowKind | null {
  return ROW_KINDS[type] ?? null;
}

function isTransient(error: HttpErrorResponse): boolean {
  return error.status === 0 || error.status >= 500 || (error.status === 404 && !isAppErrorBody(error.error));
}

/** The fixed dashboard rows, from the server, so the dashboard does not download the book list. */
@Injectable()
export class DashboardRowService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/catalog/rows`;

  private http = inject(HttpClient);
  private localWriteService = inject(LocalWriteService);
  private bookService = inject(BookService);
  private bookStateService = inject(BookStateService);

  /**
   * Fetched when subscribed, then again after a local write and after the server reports changed books;
   * the random row only once, so it does not reshuffle under the user. A removed book leaves the row at once.
   * On a server without the row endpoints (404 with the app's error body) the row is the given computation
   * from the book list.
   */
  row$(kind: CatalogRowKind, size: number, legacy: () => Observable<Book[]>): Observable<DashboardRowState> {
    const refresh$ = kind === 'random' ? EMPTY : merge(
      this.localWriteService.writes$.pipe(filter(write => write.changesCatalog), debounceTime(LOCAL_WRITE_DEBOUNCE_MS)),
      this.bookService.bookChanges$.pipe(throttleTime(SERVER_CHANGE_THROTTLE_MS, asyncScheduler, {leading: true, trailing: true}))
    );

    return new Observable<DashboardRowState>(subscriber => {
      let books: Book[] | null = null;
      let legacyMode = false;
      let failures = 0;
      let request: Subscription | null = null;
      let retry: Subscription | null = null;
      // Removed books, kept out of the answers of the fetches started up to then, which may have read them
      const removedIds = new Set<number>();
      const subscriptions = new Subscription();

      const emit = (status: DashboardRowState['status']) => subscriber.next({status, books: books ?? []});

      const startLegacy = () => {
        legacyMode = true;
        subscriptions.add(legacy().subscribe(listBooks => {
          books = listBooks;
          emit(this.bookStateService.getCurrentBookState().loaded ? 'ready' : 'loading');
        }));
      };

      const fetch = () => {
        if (legacyMode) return;
        retry?.unsubscribe();
        retry = null;
        request?.unsubscribe();
        const removedBefore = [...removedIds];
        request = this.http.get<CatalogRowResponse>(`${this.url}/${kind}`, {params: {size}}).subscribe({
          next: response => {
            failures = 0;
            books = (response.books ?? []).filter(book => !removedIds.has(book.id));
            // Once a fetch started after a removal has answered, later ones read the server after it
            removedBefore.forEach(id => removedIds.delete(id));
            emit('ready');
          },
          error: (error: HttpErrorResponse) => {
            if (error.status === 404 && isAppErrorBody(error.error)) {
              startLegacy();
              return;
            }
            // The books already shown stay
            if (!books) emit('error');
            if (isTransient(error)) {
              const delay = RETRY_DELAYS_MS[Math.min(failures, RETRY_DELAYS_MS.length - 1)];
              failures++;
              retry = timer(delay).subscribe(() => fetch());
            }
          }
        });
      };

      emit('loading');
      fetch();
      subscriptions.add(refresh$.subscribe(() => fetch()));
      subscriptions.add(this.bookStateService.removed$.subscribe(ids => {
        // The random row fetches only once, so only while that fetch runs
        if (!legacyMode && (kind !== 'random' || request?.closed === false)) ids.forEach(id => removedIds.add(id));
        const removed = new Set(ids);
        if (!books || !books.some(book => removed.has(book.id))) return;
        books = books.filter(book => !removed.has(book.id));
        emit('ready');
      }));

      return () => {
        subscriptions.unsubscribe();
        request?.unsubscribe();
        retry?.unsubscribe();
      };
    });
  }
}
