import {inject, Injectable} from '@angular/core';
import {BookStateService} from './book-state.service';
import {Book, BookMetadata} from '../model/book.model';

/** One websocket message about the book list. A batch of them is applied in the order they arrived. */
export type BookChange =
  | { kind: 'created'; book: Book }
  | { kind: 'updated'; books: Book[] }
  | { kind: 'removed'; ids: number[] }
  | { kind: 'coverUpdated'; patches: { id: number; coverUpdatedOn: string }[] };

/** The ids of the books a batch of changes touches. */
export function bookIdsOf(changes: BookChange[]): Set<number> {
  const ids = new Set<number>();
  for (const change of changes) {
    switch (change.kind) {
      case 'created':
        ids.add(change.book.id);
        break;
      case 'updated':
        change.books.forEach(book => ids.add(book.id));
        break;
      case 'removed':
        change.ids.forEach(id => ids.add(id));
        break;
      case 'coverUpdated':
        change.patches.forEach(patch => ids.add(patch.id));
        break;
    }
  }
  return ids;
}

@Injectable({
  providedIn: 'root',
})
export class BookSocketService {
  private bookStateService = inject(BookStateService);

  /**
   * Applies a batch of changes with one state update, so that a scan sending thousands of books copies the
   * list and wakes its subscribers once per batch, not once per book. A book changed more than once in the
   * batch ends as its last change left it; an update for a book the list does not hold is ignored.
   */
  applyBookChanges(changes: BookChange[]): void {
    if (changes.length === 0) return;
    const removedIds = changes.flatMap(change => change.kind === 'removed' ? change.ids : []);

    this.bookStateService.applyChangeById(books => {
      // A Map keeps each book at its place in the list, and appends the new ones as before
      const booksById = new Map(books.map(book => [book.id, book]));

      for (const change of changes) {
        switch (change.kind) {
          case 'created':
            booksById.set(change.book.id, change.book);
            break;
          case 'updated':
            change.books.forEach(book => {
              if (booksById.has(book.id)) booksById.set(book.id, book);
            });
            break;
          case 'removed':
            change.ids.forEach(id => booksById.delete(id));
            break;
          case 'coverUpdated':
            change.patches.forEach(patch => {
              const book = booksById.get(patch.id);
              if (book?.metadata) {
                booksById.set(patch.id, {...book, metadata: {...book.metadata, coverUpdatedOn: patch.coverUpdatedOn}});
              }
            });
            break;
        }
      }

      return Array.from(booksById.values());
    }, removedIds);
  }

  handleNewlyCreatedBook(book: Book): void {
    this.applyBookChanges([{kind: 'created', book}]);
  }

  handleRemovedBookIds(removedBookIds: number[]): void {
    this.applyBookChanges([{kind: 'removed', ids: removedBookIds}]);
  }

  /** A book this client changed, as the server returned it. */
  handleBookUpdate(updatedBook: Book): void {
    this.bookStateService.patchBooks(books => books.map(book =>
      book.id === updatedBook.id ? updatedBook : book
    ));
  }

  handleMultipleBookUpdates(updatedBooks: Book[]): void {
    const updatedMap = new Map(updatedBooks.map(book => [book.id, book]));
    this.bookStateService.patchBooks(books => books.map(book =>
      updatedMap.has(book.id) ? updatedMap.get(book.id)! : book
    ));
  }

  handleBookMetadataUpdate(bookId: number, updatedMetadata: BookMetadata): void {
    this.bookStateService.patchBooks(books => books.map(book =>
      book.id === bookId ? {...book, metadata: updatedMetadata} : book
    ));
  }

  handleMultipleBookCoverPatches(patches: { id: number; coverUpdatedOn: string }[]): void {
    if (!patches || patches.length === 0) return;
    this.applyBookChanges([{kind: 'coverUpdated', patches}]);
  }
}
