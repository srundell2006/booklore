import {Injectable} from '@angular/core';
import {BehaviorSubject, Observable, Subject} from 'rxjs';
import {BookState} from '../model/state/book-state.model';
import {Book} from '../model/book.model';

/** A change to books by id, made on a Map that keeps each book at its place in the list and appends new ones. */
export type BookChangeById = (booksById: Map<number, Book>) => void;

function applyById(books: Book[], changes: BookChangeById[]): Book[] {
  const booksById = new Map(books.map(book => [book.id, book]));
  changes.forEach(change => change(booksById));
  return Array.from(booksById.values());
}

/**
 * Holds the full book list, which is downloaded only when a page needs it. Cards on the dashboard and the
 * detail page work without it, so any change can arrive while the list is missing. A change is applied only
 * to a loaded list: applied to no list, it would mark a partial list as loaded and empty every grid for the
 * rest of the session. While the list is downloading, or downloading again, a change is kept for the downloaded
 * list too (see each method).
 */
@Injectable({
  providedIn: 'root',
})
export class BookStateService {
  private bookStateSubject = new BehaviorSubject<BookState>({
    books: null,
    loaded: false,
    error: null,
  });

  public readonly bookState$ = this.bookStateSubject.asObservable();

  private readonly removedSubject = new Subject<number[]>();
  /** Every book removed, by a local delete or by the server, whether or not the list is loaded. */
  public readonly removed$: Observable<number[]> = this.removedSubject.asObservable();

  private fetching = false;
  private refreshRequested = false;
  /** In the order they came in, removals with the server's changes, so a book created then deleted stays out. */
  private changesWhileFetching: BookChangeById[] = [];

  getCurrentBookState(): BookState {
    return this.bookStateSubject.value;
  }

  /** The list is downloaded and current; only then do changes apply to it. */
  isLoaded(): boolean {
    const state = this.bookStateSubject.value;
    return state.loaded && !!state.books;
  }

  /*------------------ The download of the list (BookService only) ------------------*/

  markFetching(): void {
    this.fetching = true;
  }

  /** A download of the list, the first or a refresh, is running. */
  isFetching(): boolean {
    return this.fetching;
  }

  /**
   * The downloaded list, with what was removed or arrived from the server while it downloaded. The changes are
   * applied in one pass over the list, however many batches a scan sent meanwhile.
   */
  setLoaded(books: Book[]): void {
    const current = this.changesWhileFetching.length > 0 ? applyById(books, this.changesWhileFetching) : books;
    this.clearPending();
    this.updateBookState({books: current, loaded: true, error: null});
  }

  /** A failed download or refresh keeps the books already held. */
  setError(error: string): void {
    this.clearPending();
    const current = this.bookStateSubject.value;
    this.updateBookState({books: current.books, loaded: true, error});
  }

  /** Asks for one more download after the one running; does nothing when none runs. */
  requestRefresh(): void {
    if (this.fetching) this.refreshRequested = true;
  }

  /**
   * Whether a local change came in while the list was downloading. The list may have been read before the
   * change was saved, so the caller downloads it once more.
   */
  takeRefreshRequest(): boolean {
    const requested = this.refreshRequested;
    this.refreshRequested = false;
    return requested;
  }

  /** At logout: no list, and none to fetch until someone signs in. */
  reset(): void {
    this.clearPending();
    this.updateBookState({books: null, loaded: true, error: null});
  }

  /** At sign-in after a logout: the next subscriber of BookService.bookState$ downloads the list. */
  markUnloaded(): void {
    this.clearPending();
    this.updateBookState({books: null, loaded: false, error: null});
  }

  /*------------------ Changes ------------------*/

  /**
   * A change made by this client, applied to a loaded list. While a download runs it also asks for one refresh
   * after it, since the change may be older or newer than what the download read; with no list and no download
   * it does nothing, because the next download reads the change from the server.
   */
  patchBooks(change: (books: Book[]) => Book[]): void {
    const state = this.bookStateSubject.value;
    if (state.loaded && state.books) {
      this.updateBookState({...state, books: change(state.books)});
    }
    this.requestRefresh();
  }

  /**
   * A change that sets books, by id, to what they now are: a websocket change from the server, or the last
   * read time this client sets. One that arrives while a download runs is applied again to the downloaded
   * list, without a refresh, which a scan sending books all along would otherwise repeat for as long as it runs.
   */
  applyChangeById(change: BookChangeById, removedIds: number[] = []): void {
    const state = this.bookStateSubject.value;
    if (state.loaded && state.books) {
      this.updateBookState({...state, books: applyById(state.books, [change])});
    }
    if (this.fetching) this.changesWhileFetching.push(change);
    if (removedIds.length > 0) this.removedSubject.next(removedIds);
  }

  /** Books that no longer exist. Removed from the list, and from the list being downloaded. */
  removeBooks(ids: Iterable<number>): void {
    const idSet = new Set(ids);
    if (idSet.size === 0) return;
    const state = this.bookStateSubject.value;
    if (state.loaded && state.books) {
      this.updateBookState({...state, books: state.books.filter(book => !idSet.has(book.id))});
    }
    if (this.fetching) this.changesWhileFetching.push(booksById => idSet.forEach(id => booksById.delete(id)));
    this.removedSubject.next([...idSet]);
  }

  /** An error from a change to a loaded list; with no list, an error would stop the list from ever loading. */
  setChangeError(error: string): void {
    const state = this.bookStateSubject.value;
    if (state.loaded && state.books) {
      this.updateBookState({...state, error});
    }
  }

  private clearPending(): void {
    this.fetching = false;
    this.refreshRequested = false;
    this.changesWhileFetching = [];
  }

  private updateBookState(state: BookState): void {
    this.bookStateSubject.next(state);
  }
}
