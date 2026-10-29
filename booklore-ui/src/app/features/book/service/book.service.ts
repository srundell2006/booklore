import {inject, Injectable} from '@angular/core';
import {first, forkJoin, Observable, of, Subject, throwError} from 'rxjs';
import {HttpClient, HttpParams} from '@angular/common/http';
import {catchError, distinctUntilChanged, filter, finalize, map, shareReplay, tap} from 'rxjs/operators';
import {AdditionalFile, Book, BookDeletionResponse, BookRecommendation, BookSetting, BookStatusUpdateResponse, BookType, CreatePhysicalBookRequest, PersonalRatingUpdateResponse, ReadStatus} from '../model/book.model';
import {BookState} from '../model/state/book-state.model';
import {Library} from '../model/library.model';
import {API_CONFIG} from '../../../core/config/api-config';
import {MessageService} from 'primeng/api';
import {ResetProgressType} from '../../../shared/constants/reset-progress-type';
import {AuthService} from '../../../shared/service/auth.service';
import {Router} from '@angular/router';
import {BookStateService} from './book-state.service';
import {BookChange, BookSocketService} from './book-socket.service';
import {BookPatchService} from './book-patch.service';
import {TranslocoService} from '@jsverse/transloco';

@Injectable({
  providedIn: 'root',
})
export class BookService {

  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books`;

  private http = inject(HttpClient);
  private messageService = inject(MessageService);
  private authService = inject(AuthService);
  private router = inject(Router);
  private bookStateService = inject(BookStateService);
  private bookSocketService = inject(BookSocketService);
  private bookPatchService = inject(BookPatchService);
  private readonly t = inject(TranslocoService);

  private loading$: Observable<Book[]> | null = null;

  private readonly bookChangesSubject = new Subject<BookChange[]>();
  /** Every batch of websocket changes to books, whether or not the list is loaded. */
  readonly bookChanges$: Observable<BookChange[]> = this.bookChangesSubject.asObservable();

  constructor() {
    this.authService.token$.pipe(
      distinctUntilChanged()
    ).subscribe(token => {
      if (token === null) {
        this.bookStateService.reset();
        this.loading$ = null;
      } else {
        const current = this.bookStateService.getCurrentBookState();
        if (current.loaded && !current.books) {
          this.bookStateService.markUnloaded();
          this.loading$ = null;
        }
      }
    });
  }

  /*------------------ State Management ------------------*/

  /**
   * The full book list. The first subscriber starts its download, which is large, so the pages shown before
   * a grid (the dashboard, the sidebar, the detail page) read the counts and rows from the server instead.
   */
  bookState$ = this.bookStateService.bookState$.pipe(
    tap(state => {
      if (!state.loaded && !state.error && !this.loading$) {
        this.bookStateService.markFetching();
        this.loading$ = this.fetchBooks().pipe(
          shareReplay(1),
          finalize(() => (this.loading$ = null))
        );
        this.loading$.subscribe();
      }
    })
  );

  getCurrentBookState(): BookState {
    return this.bookStateService.getCurrentBookState();
  }

  private fetchBooks(): Observable<Book[]> {
    return this.http.get<Book[]>(this.url).pipe(
      map(bookList => {
        this.setDownloaded(bookList);
        return bookList;
      }),
      catchError(error => {
        this.bookStateService.setError(error.message);
        throw error;
      })
    );
  }

  /**
   * Downloads the list again, when one is held. While a download runs, the first or a refresh, one more follows
   * it, so that changes made meanwhile are kept and two downloads never land out of order; with no list there
   * is nothing to bring up to date, since the next page that needs the list downloads it fresh.
   */
  refreshBooks(): void {
    if (!this.bookStateService.isLoaded() || this.bookStateService.isFetching()) {
      this.bookStateService.requestRefresh();
      return;
    }

    this.bookStateService.markFetching();
    this.http.get<Book[]>(this.url).pipe(
      tap(bookList => this.setDownloaded(bookList)),
      catchError(error => {
        this.bookStateService.setError(error.message);
        return of(null);
      })
    ).subscribe();
  }

  private setDownloaded(bookList: Book[]): void {
    // A change made while the list downloaded may be missing from it
    const refresh = this.bookStateService.takeRefreshRequest();
    this.bookStateService.setLoaded(bookList);
    if (refresh) this.refreshBooks();
  }

  removeBooksByLibraryId(libraryId: number): void {
    this.bookStateService.patchBooks(books => books.filter(book => book.libraryId !== libraryId));
  }

  /**
   * Brings the books of an edited library up to date without downloading every book again. Two book fields
   * come from the library: its name, and which file is primary (the first file, by id, of the first type in
   * the library's format priority). The server reports the other effects of an edit itself: the books under
   * a removed path arrive as books-remove, and the scan of an added path sends book-add for each new book.
   */
  applyLibraryUpdate(previous: Library | undefined, updated: Library): void {
    const nameChanged = previous?.name !== updated.name;
    const priorityChanged = (previous?.formatPriority ?? []).join() !== (updated.formatPriority ?? []).join();
    if (!nameChanged && !priorityChanged) return;

    this.bookStateService.patchBooks(books => books.map(book => {
      if (book.libraryId !== updated.id) return book;
      const patched = nameChanged ? {...book, libraryName: updated.name} : book;
      return priorityChanged ? withPrimaryFileByPriority(patched, updated.formatPriority ?? []) : patched;
    }));
  }

  removeBooksFromShelf(shelfId: number): void {
    this.bookStateService.patchBooks(books => books.map(book => ({
      ...book,
      shelves: book.shelves?.filter(shelf => shelf.id !== shelfId),
    })));
  }

  /*------------------ Book Retrieval ------------------*/

  getBookByIdFromState(bookId: number): Book | undefined {
    const currentState = this.bookStateService.getCurrentBookState();
    return currentState.books?.find(book => +book.id === +bookId);
  }

  getBooksByIdsFromState(bookIds: number[]): Book[] {
    const currentState = this.bookStateService.getCurrentBookState();
    if (!currentState.books || bookIds.length === 0) return [];

    const idSet = new Set(bookIds.map(id => +id));
    return currentState.books.filter(book => idSet.has(+book.id));
  }

  /**
   * The books from the list, or from the server one by one when the list does not hold them all: a card on the
   * dashboard and the detail page open dialogs before the list is loaded. A book the server cannot find is left out.
   */
  getBooksByIds(bookIds: number[]): Observable<Book[]> {
    const held = this.getBooksByIdsFromState(bookIds);
    const heldIds = new Set(held.map(book => +book.id));
    const missing = [...new Set(bookIds.map(id => +id))].filter(id => !heldIds.has(id));
    if (missing.length === 0) return of(held);

    return forkJoin(missing.map(id => this.getBookByIdFromAPI(id, false).pipe(
      catchError(() => of(null))
    ))).pipe(
      map(fetched => [...held, ...fetched.filter((book): book is Book => !!book)])
    );
  }

  getBookByIdFromAPI(bookId: number, withDescription: boolean): Observable<Book> {
    return this.http.get<Book>(`${this.url}/${bookId}`, {
      params: {
        withDescription: withDescription.toString()
      }
    });
  }

  getBooksInSeries(bookId: number): Observable<Book[]> {
    return this.bookStateService.bookState$.pipe(
      filter(state => state.loaded),
      first(),
      map(state => {
        const allBooks = state.books || [];
        const currentBook = allBooks.find(b => b.id === bookId);

        if (!currentBook || !currentBook.metadata?.seriesName) {
          return [];
        }

        const seriesName = currentBook.metadata.seriesName.toLowerCase();
        return allBooks.filter(b => b.metadata?.seriesName?.toLowerCase() === seriesName);
      })
    );
  }

  getBookRecommendations(bookId: number, limit: number = 20): Observable<BookRecommendation[]> {
    return this.http.get<BookRecommendation[]>(`${this.url}/${bookId}/recommendations`, {
      params: {limit: limit.toString()}
    });
  }

  /*------------------ Book Operations ------------------*/

  deleteBooks(ids: Set<number>): Observable<BookDeletionResponse> {
    const idList = Array.from(ids);
    const params = new HttpParams().set('ids', idList.join(','));

    return this.http.delete<BookDeletionResponse>(this.url, {params}).pipe(
      tap(response => {
        // Removed from a loaded list only; with no list loaded, this leaves the state unloaded
        this.bookStateService.removeBooks(ids);

        if (response.failedFileDeletions?.length > 0) {
          this.messageService.add({
            severity: 'warn',
            summary: this.t.translate('book.bookService.toast.someFilesNotDeletedSummary'),
            detail: this.t.translate('book.bookService.toast.someFilesNotDeletedDetail', {fileNames: response.failedFileDeletions.join(', ')}),
          });
        } else {
          this.messageService.add({
            severity: 'success',
            summary: this.t.translate('book.bookService.toast.booksDeletedSummary'),
            detail: this.t.translate('book.bookService.toast.booksDeletedDetail', {count: idList.length}),
          });
        }
      }),
      catchError(error => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('book.bookService.toast.deleteFailedSummary'),
          detail: error?.error?.message || error?.message || this.t.translate('book.bookService.toast.deleteFailedDetail'),
        });
        return throwError(() => error);
      })
    );
  }

  updateBookShelves(bookIds: Set<number | undefined>, shelvesToAssign: Set<number | null | undefined>, shelvesToUnassign: Set<number | null | undefined>): Observable<Book[]> {
    return this.bookPatchService.updateBookShelves(bookIds, shelvesToAssign, shelvesToUnassign).pipe(
      catchError(error => {
        this.bookStateService.setChangeError(error.message);
        throw error;
      })
    );
  }

  createPhysicalBook(request: CreatePhysicalBookRequest): Observable<Book> {
    return this.http.post<Book>(`${this.url}/physical`, request).pipe(
      tap(newBook => {
        this.bookStateService.patchBooks(books => [...books, newBook]);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('book.bookService.toast.physicalBookCreatedSummary'),
          detail: this.t.translate('book.bookService.toast.physicalBookCreatedDetail', {title: newBook.metadata?.title || 'Book'})
        });
      }),
      catchError(error => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('book.bookService.toast.creationFailedSummary'),
          detail: error?.error?.message || error?.message || this.t.translate('book.bookService.toast.creationFailedDetail')
        });
        return throwError(() => error);
      })
    );
  }

  togglePhysicalFlag(bookId: number, physical: boolean): Observable<Book> {
    return this.http.patch<Book>(`${this.url}/${bookId}/physical`, null, {params: {physical}}).pipe(
      tap(() => {
        this.bookStateService.patchBooks(books => books.map(b => b.id === bookId ? {...b, isPhysical: physical} : b));
      })
    );
  }

  toggleComicFlag(bookId: number, comic: boolean): Observable<Book> {
    return this.http.patch<Book>(`${this.url}/${bookId}/comic`, null, {params: {comic}}).pipe(
      tap(() => {
        this.bookStateService.patchBooks(books => books.map(b => b.id === bookId ? {...b, isComic: comic} : b));
      })
    );
  }

  applyVerificationMetadata(bookId: number): Observable<Book> {
    return this.http.post<Book>(`${this.url}/${bookId}/apply-verification-metadata`, {});
  }

  clearVerificationStatus(bookId: number): Observable<Book> {
    return this.http.post<Book>(`${this.url}/${bookId}/clear-verification-status`, {});
  }

  /*------------------ Reading & Viewer Settings ------------------*/

  /**
   * Opens the reader for a book. Pass the book when it is at hand: the list may not be loaded, and then a book
   * given by id is fetched first.
   */
  readBook(bookOrId: Book | number, reader?: 'epub-streaming', explicitBookType?: BookType): void {
    if (typeof bookOrId !== 'number') {
      this.openReader(bookOrId, reader, explicitBookType);
      return;
    }
    const fromState = this.getBookByIdFromState(bookOrId);
    if (fromState) {
      this.openReader(fromState, reader, explicitBookType);
      return;
    }
    this.getBookByIdFromAPI(bookOrId, false).subscribe({
      next: book => this.openReader(book, reader, explicitBookType),
      error: () => console.error('Book not found')
    });
  }

  private openReader(book: Book, reader?: 'epub-streaming', explicitBookType?: BookType): void {
    const bookType: BookType | undefined = explicitBookType ?? book.primaryFile?.bookType;
    const isAlternativeFormat = explicitBookType && explicitBookType !== book.primaryFile?.bookType;

    let baseUrl: string | null = null;
    let queryParams: Record<string, any> = {};

    switch (bookType) {
      case 'PDF':
        baseUrl = 'pdf-reader';
        break;

      case 'EPUB':
        baseUrl = 'ebook-reader';
        if (reader === 'epub-streaming') {
          queryParams['streaming'] = true;
        }
        break;

      case 'FB2':
      case 'MOBI':
      case 'AZW3':
        baseUrl = 'ebook-reader';
        break;

      case 'CBX':
        baseUrl = 'cbx-reader';
        break;

      case 'AUDIOBOOK':
        baseUrl = 'audiobook-player';
        break;
    }

    if (!baseUrl) {
      console.error('Unsupported book type:', bookType);
      return;
    }

    if (isAlternativeFormat) {
      queryParams['bookType'] = bookType;
    }

    const hasQueryParams = Object.keys(queryParams).length > 0;
    this.router.navigate([`/${baseUrl}/book/${book.id}`], hasQueryParams ? {queryParams} : undefined);

    this.updateLastReadTime(book.id);
  }

  getBookSetting(bookId: number, bookFileId: number): Observable<BookSetting> {
    return this.http.get<BookSetting>(`${this.url}/${bookId}/viewer-setting?bookFileId=${bookFileId}`);
  }

  updateViewerSetting(bookSetting: BookSetting, bookId: number): Observable<void> {
    return this.http.put<void>(`${this.url}/${bookId}/viewer-setting`, bookSetting);
  }

  /*------------------ Progress & Status Tracking ------------------*/

  updateLastReadTime(bookId: number): void {
    this.bookPatchService.updateLastReadTime(bookId);
  }

  savePdfProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    return this.bookPatchService.savePdfProgress(bookId, page, percentage, bookFileId);
  }

  saveCbxProgress(bookId: number, page: number, percentage: number, bookFileId?: number): Observable<void> {
    return this.bookPatchService.saveCbxProgress(bookId, page, percentage, bookFileId);
  }

  updateDateFinished(bookId: number, dateFinished: string | null): Observable<void> {
    return this.bookPatchService.updateDateFinished(bookId, dateFinished);
  }

  resetProgress(bookIds: number | number[], type: ResetProgressType): Observable<BookStatusUpdateResponse[]> {
    return this.bookPatchService.resetProgress(bookIds, type);
  }

  updateBookReadStatus(bookIds: number | number[], status: ReadStatus): Observable<BookStatusUpdateResponse[]> {
    return this.bookPatchService.updateBookReadStatus(bookIds, status);
  }

  /*------------------ Personal Rating ------------------*/

  resetPersonalRating(bookIds: number | number[]): Observable<PersonalRatingUpdateResponse[]> {
    return this.bookPatchService.resetPersonalRating(bookIds);
  }

  updatePersonalRating(bookIds: number | number[], rating: number): Observable<PersonalRatingUpdateResponse[]> {
    return this.bookPatchService.updatePersonalRating(bookIds, rating);
  }

  /*------------------ Websocket Handlers ------------------*/

  handleBookChanges(changes: BookChange[]): void {
    this.bookSocketService.applyBookChanges(changes);
    if (changes.length > 0) this.bookChangesSubject.next(changes);
  }

  handleNewlyCreatedBook(book: Book): void {
    this.bookSocketService.handleNewlyCreatedBook(book);
  }

  handleRemovedBookIds(removedBookIds: number[]): void {
    this.bookSocketService.handleRemovedBookIds(removedBookIds);
  }

  handleBookUpdate(updatedBook: Book): void {
    this.bookSocketService.handleBookUpdate(updatedBook);
  }

  handleMultipleBookUpdates(updatedBooks: Book[]): void {
    this.bookSocketService.handleMultipleBookUpdates(updatedBooks);
  }

  handleMultipleBookCoverPatches(patches: { id: number; coverUpdatedOn: string }[]): void {
    this.bookSocketService.handleMultipleBookCoverPatches(patches);
  }
}

/** The book with its primary file chosen as the server chooses it (BookMapperV2.getPrimaryBookFile). */
export function withPrimaryFileByPriority(book: Book, formatPriority: BookType[]): Book {
  const files: AdditionalFile[] = [...(book.primaryFile ? [book.primaryFile] : []), ...(book.alternativeFormats ?? [])]
    .sort((a, b) => a.id - b.id);
  if (files.length < 2) return book;

  const preferredType = formatPriority.find(type => files.some(file => file.bookType === type));
  const primary = files.find(file => file.bookType === preferredType) ?? files[0];
  if (primary.id === book.primaryFile?.id) return book;
  return {...book, primaryFile: primary, alternativeFormats: files.filter(file => file !== primary)};
}
