import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {DOCUMENT} from '@angular/common';
import {asyncScheduler, BehaviorSubject, defer, fromEvent, Observable, Subscription, timer} from 'rxjs';
import {debounceTime, distinctUntilChanged, filter, finalize, map, skip, switchMap, throttleTime} from 'rxjs/operators';
import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';
import {RxStompService} from '../../../shared/websocket/rx-stomp.service';
import {LocalWriteService} from '../../../core/services/catalog-write.interceptor';
import {BookService} from './book.service';
import {CatalogCounts, CatalogMode, MagicCounts} from '../model/catalog.model';

const LOCAL_WRITE_DEBOUNCE_MS = 500;
const SERVER_CHANGE_THROTTLE_MS = 30_000;
const HIDDEN_REFETCH_AFTER_MS = 60_000;
/** After the last delay, retries go on at that pace. */
const RETRY_DELAYS_MS = [5_000, 15_000, 60_000];

/** The error body the API sends: an older server without the catalog endpoints answers 404 with it. */
export function isAppErrorBody(body: unknown): boolean {
  const fields = body as Record<string, unknown> | null;
  return !!fields && typeof fields === 'object' && typeof fields['status'] === 'number' && typeof fields['message'] === 'string';
}

/**
 * One server value. It is fetched only while something subscribes: on the first subscription, then on each
 * invalidation. A failed fetch keeps the last value and is retried with backoff.
 */
class CatalogResource<T> {
  private readonly state = new BehaviorSubject<T | null>(null);
  private consumers = 0;
  private request: Subscription | null = null;
  private fetchAgain = false;
  private retry: Subscription | null = null;
  private failures = 0;

  constructor(
    private readonly load: () => Observable<T>,
    /** True when the error is definitive and must not be retried. */
    private readonly handleError: (error: HttpErrorResponse) => boolean,
    private readonly enabled: () => boolean,
  ) {
  }

  readonly value$: Observable<T | null> = defer(() => {
    if (++this.consumers === 1) this.fetch();
    return this.state;
  }).pipe(
    finalize(() => {
      if (--this.consumers === 0) this.stopRetry();
    })
  );

  invalidate(): void {
    if (this.consumers > 0) this.fetch();
  }

  clear(): void {
    this.fetchAgain = false;
    this.request?.unsubscribe();
    this.request = null;
    this.failures = 0;
    this.stopRetry();
    this.state.next(null);
  }

  private fetch(): void {
    if (!this.enabled()) return;
    if (this.request) {
      this.fetchAgain = true;
      return;
    }
    this.stopRetry();

    let done = false;
    const request = this.load().pipe(
      finalize(() => {
        done = true;
        this.request = null;
        if (this.fetchAgain) {
          this.fetchAgain = false;
          this.invalidate();
        }
      })
    ).subscribe({
      next: value => {
        this.failures = 0;
        this.state.next(value);
      },
      error: (error: HttpErrorResponse) => {
        if (!this.handleError(error)) this.scheduleRetry();
      }
    });
    if (!done) this.request = request;
  }

  private scheduleRetry(): void {
    if (this.consumers === 0 || !this.enabled()) return;
    const delay = RETRY_DELAYS_MS[Math.min(this.failures, RETRY_DELAYS_MS.length - 1)];
    this.failures++;
    this.retry = timer(delay).subscribe(() => {
      this.retry = null;
      this.invalidate();
    });
  }

  private stopRetry(): void {
    this.retry?.unsubscribe();
    this.retry = null;
  }
}

/**
 * The sidebar counts, computed by the server so that showing them does not download the whole book list.
 * Content-restricted users, and an older server without these endpoints, switch the session to 'legacy',
 * where every count is computed from the list as before.
 */
@Injectable({
  providedIn: 'root',
})
export class CatalogCountsService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/catalog`;

  private http = inject(HttpClient);
  private authService = inject(AuthService);
  private bookService = inject(BookService);
  private rxStompService = inject(RxStompService);
  private localWriteService = inject(LocalWriteService);
  private document = inject(DOCUMENT);

  private readonly modeSubject = new BehaviorSubject<CatalogMode>('server');
  readonly mode$: Observable<CatalogMode> = this.modeSubject.pipe(distinctUntilChanged());

  private readonly counts = new CatalogResource<CatalogCounts>(
    () => this.http.get<CatalogCounts>(`${this.url}/counts`).pipe(
      filter(counts => {
        if (!counts.clientSide) return true;
        this.switchToLegacy();
        return false;
      })
    ),
    error => this.handleError(error),
    () => this.modeSubject.value === 'server'
  );

  private readonly magicCounts = new CatalogResource<MagicCounts>(
    () => this.http.get<MagicCounts>(`${this.url}/magic-counts`),
    error => this.handleError(error),
    () => this.modeSubject.value === 'server'
  );

  /** Null until the first answer. Fetched only while subscribed, and only in 'server' mode. */
  readonly counts$: Observable<CatalogCounts | null> = this.counts.value$;
  readonly magicCounts$: Observable<MagicCounts | null> = this.magicCounts.value$;

  private hiddenAt: number | null = null;

  constructor() {
    this.localWriteService.writes$.pipe(
      filter(write => write.changesCatalog),
      debounceTime(LOCAL_WRITE_DEBOUNCE_MS)
    ).subscribe(() => this.invalidate());

    this.bookService.bookChanges$.pipe(
      throttleTime(SERVER_CHANGE_THROTTLE_MS, asyncScheduler, {leading: true, trailing: true})
    ).subscribe(() => this.invalidate());

    // The first connection is not a reconnect; the counts are fetched by their first subscriber
    this.rxStompService.connected$.pipe(skip(1)).subscribe(() => this.invalidate());

    fromEvent(this.document, 'visibilitychange').subscribe(() => this.onVisibilityChange());

    this.authService.token$.pipe(
      distinctUntilChanged(),
      filter(token => token === null)
    ).subscribe(() => this.clear());
  }

  /**
   * A count from the server, or null while it loads. In 'legacy' mode, the given computation from the book
   * list instead, which downloads the list.
   */
  view$<T>(select: (counts: CatalogCounts) => T, legacy: () => Observable<T>): Observable<T | null> {
    return this.mode$.pipe(
      switchMap(mode => mode === 'legacy'
        ? legacy()
        : this.counts$.pipe(map(counts => counts ? select(counts) : null))
      )
    );
  }

  /** Fetches every count again that something is showing. */
  invalidate(): void {
    this.counts.invalidate();
    this.magicCounts.invalidate();
  }

  private handleError(error: HttpErrorResponse): boolean {
    if (error.status === 404 && isAppErrorBody(error.error)) {
      this.switchToLegacy();
      return true;
    }
    return false;
  }

  private switchToLegacy(): void {
    this.modeSubject.next('legacy');
  }

  private onVisibilityChange(): void {
    if (this.document.visibilityState === 'hidden') {
      this.hiddenAt = Date.now();
      return;
    }
    const hiddenFor = this.hiddenAt === null ? 0 : Date.now() - this.hiddenAt;
    this.hiddenAt = null;
    if (hiddenFor > HIDDEN_REFETCH_AFTER_MS) this.invalidate();
  }

  private clear(): void {
    this.counts.clear();
    this.magicCounts.clear();
    this.hiddenAt = null;
    this.modeSubject.next('server');
  }
}
