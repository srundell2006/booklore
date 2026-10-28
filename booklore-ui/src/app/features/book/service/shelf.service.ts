import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {BehaviorSubject, combineLatest, Observable, of} from 'rxjs';
import {tap, catchError, map, shareReplay, finalize, switchMap} from 'rxjs/operators';

import {Shelf} from '../model/shelf.model';
import {ShelfState} from '../model/state/shelf-state.model';
import {BookService} from './book.service';
import {API_CONFIG} from '../../../core/config/api-config';
import {Book} from '../model/book.model';
import {UserService} from '../../settings/user-management/user.service';
import {CatalogCountsService} from './catalog-counts.service';

@Injectable({providedIn: 'root'})
export class ShelfService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/shelves`;
  private http = inject(HttpClient);
  private bookService = inject(BookService);
  private userService = inject(UserService);
  private catalogCountsService = inject(CatalogCountsService);

  private shelfStateSubject = new BehaviorSubject<ShelfState>({
    shelves: null,
    loaded: false,
    error: null,
  });

  private loading$: Observable<Shelf[]> | null = null;

  shelfState$ = this.shelfStateSubject.asObservable().pipe(
    tap(state => {
      if (!state.loaded && !state.error && !this.loading$) {
        this.loading$ = this.fetchShelves().pipe(
          shareReplay(1),
          finalize(() => (this.loading$ = null))
        );
        this.loading$.subscribe();
      }
    })
  );

  private fetchShelves(): Observable<Shelf[]> {
    return this.http.get<Shelf[]>(this.url).pipe(
      tap(shelves => this.shelfStateSubject.next({shelves, loaded: true, error: null})),
      catchError(err => {
        const curr = this.shelfStateSubject.value;
        this.shelfStateSubject.next({shelves: curr.shelves, loaded: true, error: err.message});
        throw err;
      })
    );
  }

  public reloadShelves(): void {
    this.fetchShelves().subscribe({
      next: () => {
      },
      error: () => {
      }
    });
  }

  createShelf(shelf: Shelf): Observable<Shelf> {
    return this.http.post<Shelf>(this.url, shelf).pipe(
      map(newShelf => {
        const curr = this.shelfStateSubject.value;
        const updated = curr.shelves ? [...curr.shelves, newShelf] : [newShelf];
        this.shelfStateSubject.next({...curr, shelves: updated});
        return newShelf;
      })
    );
  }

  updateShelf(shelf: Shelf, id?: number): Observable<Shelf> {
    return this.http.put<Shelf>(`${this.url}/${id}`, shelf).pipe(
      map(updated => {
        const curr = this.shelfStateSubject.value;
        const list = curr.shelves?.map(s => (s.id === updated.id ? updated : s)) || [updated];
        this.shelfStateSubject.next({...curr, shelves: list});
        return updated;
      })
    );
  }

  deleteShelf(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`).pipe(
      tap(() => {
        this.bookService.removeBooksFromShelf(id);
        const curr = this.shelfStateSubject.value;
        const filtered = curr.shelves?.filter(s => s.id !== id) || [];
        this.shelfStateSubject.next({...curr, shelves: filtered});
      }),
      catchError(err => {
        const curr = this.shelfStateSubject.value;
        this.shelfStateSubject.next({...curr, error: err.message});
        return of();
      })
    );
  }

  getShelfById(id: number): Shelf | undefined {
    return this.shelfStateSubject.value.shelves?.find(s => s.id === id);
  }

  getShelvesFromState(): Shelf[] {
    return this.shelfStateSubject.value.shelves ?? [];
  }

  /**
   * The server counts the viewer's own shelves; another user's public shelf keeps the count GET /shelves
   * gives it. Null while the count loads.
   */
  getBookCount(shelfId: number): Observable<number | null> {
    return this.catalogCountsService.mode$.pipe(
      switchMap(mode => mode === 'legacy'
        ? this.getBookCountFromList(shelfId)
        : combineLatest([this.shelfState$, this.catalogCountsService.counts$]).pipe(
          map(([shelfState, counts]) => {
            const shelf = shelfState.shelves?.find(s => s.id === shelfId);
            if (!shelf) return 0;
            if (!counts) return null;
            return shelfId in counts.shelves ? counts.shelves[shelfId] : (shelf.bookCount || 0);
          })
        )
      )
    );
  }

  private getBookCountFromList(shelfId: number): Observable<number> {
    return combineLatest([
      this.shelfState$,
      this.userService.userState$,
      this.bookService.bookState$
    ]).pipe(
      map(([shelfState, userState, bookState]) => {
        const shelf = shelfState.shelves?.find(s => s.id === shelfId);
        if (!shelf) return 0;

        const isOwner = userState.user?.id === shelf.userId;

        if (isOwner) {
          return (bookState.books || []).filter(b => b.shelves?.some(s => s.id === shelfId)).length;
        } else {
          return shelf.bookCount || 0;
        }
      })
    );
  }

  getBooksOnShelf(shelfId: number): Observable<Book[]> {
    return this.http.get<Book[]>(`${this.url}/${shelfId}/books`);
  }

  /** Null while the count loads. */
  getUnshelvedBookCount(): Observable<number | null> {
    return this.catalogCountsService.view$(
      counts => counts.unshelved,
      () => this.bookService.bookState$.pipe(
        map(state =>
          (state.books || []).filter(b => !b.shelves || b.shelves.length === 0).length
        )
      )
    );
  }
}
