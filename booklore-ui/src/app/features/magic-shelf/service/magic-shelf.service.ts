import {inject, Injectable} from '@angular/core';
import {API_CONFIG} from '../../../core/config/api-config';
import {HttpClient} from '@angular/common/http';
import {BehaviorSubject, combineLatest, Observable, of} from 'rxjs';
import {catchError, distinctUntilChanged, finalize, map, shareReplay, switchMap, tap} from 'rxjs/operators';
import {BookService} from '../../book/service/book.service';
import {BookRuleEvaluatorService} from './book-rule-evaluator.service';
import {AuthService} from '../../../shared/service/auth.service';
import {GroupRule} from '../component/magic-shelf-component';
import {CatalogCountsService} from '../../book/service/catalog-counts.service';
import {BookStateService} from '../../book/service/book-state.service';
import {MagicShelfCount} from '../../book/model/catalog.model';
import {Book} from '../../book/model/book.model';

export interface MagicShelf {
  id?: number | null;
  name: string;
  icon?: string | null;
  iconType?: 'PRIME_NG' | 'CUSTOM_SVG' | null;
  filterJson: string;
  isPublic?: boolean;
}

export interface MagicShelfState {
  shelves: MagicShelf[] | null;
  loaded: boolean;
  error: string | null;
}

/** A magic shelf's count, or null while it loads; with unavailable set, the server could not count it. */
export interface MagicShelfCountView {
  count: number | null;
  unavailable: MagicShelfCount | null;
}

@Injectable({
  providedIn: 'root',
})
export class MagicShelfService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/magic-shelves`;

  private readonly http = inject(HttpClient);
  private readonly bookService = inject(BookService);
  private readonly ruleEvaluatorService = inject(BookRuleEvaluatorService);
  private readonly authService = inject(AuthService);
  private readonly catalogCountsService = inject(CatalogCountsService);
  private readonly bookStateService = inject(BookStateService);

  private readonly shelvesStateSubject = new BehaviorSubject<MagicShelfState>({
    shelves: null,
    loaded: false,
    error: null,
  });

  private loading$: Observable<MagicShelf[]> | null = null;

  constructor() {
    this.authService.token$.pipe(
      distinctUntilChanged()
    ).subscribe(token => {
      if (token === null) {
        this.shelvesStateSubject.next({
          shelves: null,
          loaded: true,
          error: null,
        });
        this.loading$ = null;
      } else {
        const current = this.shelvesStateSubject.value;
        if (current.loaded && !current.shelves) {
          this.shelvesStateSubject.next({
            shelves: null,
            loaded: false,
            error: null,
          });
          this.loading$ = null;
        }
      }
    });
  }

  public readonly shelvesState$ = this.shelvesStateSubject.asObservable().pipe(
    tap(state => {
      if (!state.loaded && !state.error && !this.loading$) {
        this.loading$ = this.fetchMagicShelves().pipe(
          shareReplay(1),
          finalize(() => (this.loading$ = null))
        );
        this.loading$.subscribe();
      }
    })
  );

  private get state(): MagicShelfState {
    return this.shelvesStateSubject.value;
  }

  private fetchMagicShelves(): Observable<MagicShelf[]> {
    return this.http.get<MagicShelf[]>(this.url).pipe(
      tap(shelves => this.shelvesStateSubject.next({shelves, loaded: true, error: null})),
      catchError(err => {
        const curr = this.shelvesStateSubject.value;
        this.shelvesStateSubject.next({shelves: curr.shelves, loaded: true, error: err.message});
        throw err;
      })
    );
  }

  saveShelf(data: { id?: number; name: string | null; icon: string | null; iconType?: 'PRIME_NG' | 'CUSTOM_SVG'; group: unknown, isPublic?: boolean | null }): Observable<MagicShelf> {
    const payload: MagicShelf = {
      id: data.id,
      name: data.name ?? '',
      icon: data.icon,
      iconType: data.iconType,
      filterJson: JSON.stringify(data.group),
      isPublic: data.isPublic ?? false
    };

    return this.http.post<MagicShelf>(this.url, payload).pipe(
      tap((newShelf) => {
        const curr = this.state;
        const shelves = curr.shelves || [];
        const updated = shelves.some((s) => s.id === newShelf.id)
          ? shelves.map((s) => (s.id === newShelf.id ? newShelf : s))
          : [...shelves, newShelf];

        this.shelvesStateSubject.next({...curr, shelves: updated});
      }),
      catchError((error) => {
        this.updateError(error.message);
        throw error;
      })
    );
  }

  getShelf(id: number): Observable<MagicShelf | undefined> {
    return this.shelvesState$.pipe(
      map(state => (state.shelves || []).find(shelf => shelf.id === id))
    );
  }

  deleteShelf(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`).pipe(
      tap(() => {
        const curr = this.state;
        const updated = (curr.shelves || []).filter((s) => s.id !== id);
        this.shelvesStateSubject.next({...curr, shelves: updated});
      }),
      catchError((error) => {
        this.updateError(error.message);
        throw error;
      })
    );
  }

  private updateError(error: string): void {
    this.shelvesStateSubject.next({
      ...this.state,
      error,
      loaded: true,
    });
  }

  getBookCount(shelfId: number): Observable<number | null> {
    return this.getBookCountView(shelfId).pipe(map(view => view.count));
  }

  /**
   * The server's count. A shelf it cannot count is counted here, but only from a list some page already
   * downloaded: the sidebar never starts the download. Until then the count is unavailable.
   */
  getBookCountView(shelfId: number): Observable<MagicShelfCountView> {
    return this.catalogCountsService.mode$.pipe(
      switchMap(mode => mode === 'legacy'
        ? this.getBookCountFromList(shelfId).pipe(map(count => ({count, unavailable: null})))
        : combineLatest([this.getShelf(shelfId), this.catalogCountsService.magicCounts$, this.bookStateService.bookState$]).pipe(
          map(([shelf, magicCounts, state]): MagicShelfCountView => {
            if (!shelf) return {count: 0, unavailable: null};
            const entry = magicCounts?.magicShelves[shelfId];
            if (!entry) return {count: null, unavailable: null};
            if (entry.status === 'OK' || entry.status === 'INVALID') return {count: entry.count ?? 0, unavailable: null};
            if (state.loaded && state.books) return {count: this.countInList(shelf, state.books), unavailable: null};
            return {count: null, unavailable: entry};
          }),
          distinctUntilChanged((a, b) => a.count === b.count && a.unavailable === b.unavailable)
        )
      )
    );
  }

  private getBookCountFromList(shelfId: number): Observable<number> {
    return this.getShelf(shelfId).pipe(
      switchMap((shelf) => {
        if (!shelf) return of(0);
        const group = this.parseGroup(shelf);
        if (!group) return of(0);

        return this.bookService.bookState$.pipe(
          map((state) => this.countMatching(group, state.books ?? []))
        );
      })
    );
  }

  private countInList(shelf: MagicShelf, allBooks: Book[]): number {
    const group = this.parseGroup(shelf);
    return group ? this.countMatching(group, allBooks) : 0;
  }

  private parseGroup(shelf: MagicShelf): GroupRule | null {
    try {
      return JSON.parse(shelf.filterJson);
    } catch (e) {
      console.error('Invalid filter JSON', e);
      return null;
    }
  }

  private countMatching(group: GroupRule, allBooks: Book[]): number {
    return allBooks.filter((book) =>
      this.ruleEvaluatorService.evaluateGroup(book, group, allBooks)
    ).length;
  }
}
