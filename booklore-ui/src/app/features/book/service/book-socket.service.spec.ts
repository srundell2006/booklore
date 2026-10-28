import {beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {BookSocketService} from './book-socket.service';
import {BookStateService} from './book-state.service';
import {Book} from '../model/book.model';

function book(id: number, title: string, coverUpdatedOn?: string): Book {
  return {id, libraryId: 1, libraryName: 'Books', metadata: {bookId: id, title, coverUpdatedOn}} as Book;
}

describe('BookSocketService.applyBookChanges', () => {
  let service: BookSocketService;
  let state: BookStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({providers: [BookSocketService, BookStateService]});
    service = TestBed.inject(BookSocketService);
    state = TestBed.inject(BookStateService);
    state.updateBookState({books: [book(1, 'One'), book(2, 'Two')], loaded: true, error: null});
  });

  const titles = () => state.getCurrentBookState().books?.map(b => `${b.id}:${b.metadata?.title}`);

  it('should apply a whole batch with one state update', () => {
    const updates = vi.spyOn(state, 'updateBookState');

    service.applyBookChanges([
      {kind: 'created', book: book(3, 'Three')},
      {kind: 'created', book: book(4, 'Four')},
      {kind: 'updated', books: [book(1, 'One, revised')]},
    ]);

    expect(updates).toHaveBeenCalledTimes(1);
    expect(titles()).toEqual(['1:One, revised', '2:Two', '3:Three', '4:Four']);
  });

  it('should keep the last version of a book changed twice', () => {
    service.applyBookChanges([
      {kind: 'created', book: book(3, 'Three')},
      {kind: 'updated', books: [book(3, 'Three, with metadata')]},
      {kind: 'created', book: book(2, 'Two, rescanned')},
    ]);

    expect(titles()).toEqual(['1:One', '2:Two, rescanned', '3:Three, with metadata']);
  });

  it('should ignore an update for a book the list does not hold', () => {
    service.applyBookChanges([{kind: 'updated', books: [book(9, 'Unknown')]}]);

    expect(titles()).toEqual(['1:One', '2:Two']);
  });

  it('should apply removals in order with the other changes', () => {
    service.applyBookChanges([
      {kind: 'created', book: book(3, 'Three')},
      {kind: 'removed', ids: [3, 1]},
      {kind: 'updated', books: [book(3, 'Three, too late')]},
    ]);

    expect(titles()).toEqual(['2:Two']);
  });

  it('should patch covers without touching the stored book', () => {
    const before = state.getCurrentBookState().books![0];

    service.applyBookChanges([{kind: 'coverUpdated', patches: [{id: 1, coverUpdatedOn: '2026-10-28T12:00:00Z'}, {id: 9, coverUpdatedOn: 'x'}]}]);

    const after = state.getCurrentBookState().books![0];
    expect(after.metadata?.coverUpdatedOn).toBe('2026-10-28T12:00:00Z');
    expect(before.metadata?.coverUpdatedOn).toBeUndefined();
  });

  it('should leave the state alone for an empty batch', () => {
    const updates = vi.spyOn(state, 'updateBookState');

    service.applyBookChanges([]);

    expect(updates).not.toHaveBeenCalled();
  });
});
