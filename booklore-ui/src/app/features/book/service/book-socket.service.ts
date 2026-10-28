import {inject, Injectable} from '@angular/core';
import {BookStateService} from './book-state.service';
import {Book, BookMetadata} from '../model/book.model';

/** One websocket message about the book list. A batch of them is applied in the order they arrived. */
export type BookChange =
  | { kind: 'created'; book: Book }
  | { kind: 'updated'; books: Book[] }
  | { kind: 'removed'; ids: number[] }
  | { kind: 'coverUpdated'; patches: { id: number; coverUpdatedOn: string }[] };

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
    const currentState = this.bookStateService.getCurrentBookState();
    // A Map keeps each book at its place in the list, and appends the new ones as before
    const booksById = new Map((currentState.books || []).map(book => [book.id, book]));

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

    this.bookStateService.updateBookState({...currentState, books: Array.from(booksById.values())});
  }

  handleNewlyCreatedBook(book: Book): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const updatedBooks = currentState.books ? [...currentState.books] : [];
    const bookIndex = updatedBooks.findIndex(existingBook => existingBook.id === book.id);
    if (bookIndex > -1) {
      updatedBooks[bookIndex] = book;
    } else {
      updatedBooks.push(book);
    }
    this.bookStateService.updateBookState({...currentState, books: updatedBooks});
  }

  handleRemovedBookIds(removedBookIds: number[]): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const filteredBooks = (currentState.books || []).filter(book => !removedBookIds.includes(book.id));
    this.bookStateService.updateBookState({...currentState, books: filteredBooks});
  }

  handleBookUpdate(updatedBook: Book): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const updatedBooks = (currentState.books || []).map(book =>
      book.id === updatedBook.id ? updatedBook : book
    );
    this.bookStateService.updateBookState({...currentState, books: updatedBooks});
  }

  handleMultipleBookUpdates(updatedBooks: Book[]): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const currentBooks = currentState.books || [];

    const updatedMap = new Map(updatedBooks.map(book => [book.id, book]));

    const mergedBooks = currentBooks.map(book =>
      updatedMap.has(book.id) ? updatedMap.get(book.id)! : book
    );

    this.bookStateService.updateBookState({...currentState, books: mergedBooks});
  }

  handleBookMetadataUpdate(bookId: number, updatedMetadata: BookMetadata): void {
    const currentState = this.bookStateService.getCurrentBookState();
    const updatedBooks = (currentState.books || []).map(book => {
      return book.id === bookId ? {...book, metadata: updatedMetadata} : book;
    });
    this.bookStateService.updateBookState({...currentState, books: updatedBooks});
  }

  handleMultipleBookCoverPatches(patches: { id: number; coverUpdatedOn: string }[]): void {
    if (!patches || patches.length === 0) return;
    const currentState = this.bookStateService.getCurrentBookState();
    const books = currentState.books || [];
    patches.forEach(p => {
      const index = books.findIndex(b => b.id === p.id);
      if (index !== -1 && books[index].metadata) {
        books[index].metadata.coverUpdatedOn = p.coverUpdatedOn;
      }
    });
    this.bookStateService.updateBookState({...currentState, books});
  }
}
