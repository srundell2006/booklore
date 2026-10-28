import {beforeEach, describe, expect, it} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';
import {of} from 'rxjs';
import {BookService, withPrimaryFileByPriority} from './book.service';
import {BookStateService} from './book-state.service';
import {BookPatchService} from './book-patch.service';
import {AuthService} from '../../../shared/service/auth.service';
import {AdditionalFile, Book, BookType} from '../model/book.model';
import {Library} from '../model/library.model';

function file(id: number, bookType: BookType): AdditionalFile {
  return {id, bookId: 1, bookType, fileName: `file-${id}.${bookType.toLowerCase()}`};
}

function book(id: number, libraryId: number, files: AdditionalFile[]): Book {
  const [primaryFile, ...alternativeFormats] = files;
  return {id, libraryId, libraryName: `Library ${libraryId}`, primaryFile, alternativeFormats} as Book;
}

function library(id: number, name: string, formatPriority: BookType[] = []): Library {
  return {id, name, formatPriority, watch: false, paths: []};
}

describe('withPrimaryFileByPriority', () => {

  it('should make the first file of the first type in the priority primary', () => {
    const result = withPrimaryFileByPriority(book(1, 1, [file(10, 'EPUB'), file(11, 'PDF'), file(12, 'PDF')]), ['PDF', 'EPUB']);

    expect(result.primaryFile?.id).toBe(11);
    expect(result.alternativeFormats?.map(f => f.id)).toEqual([10, 12]);
  });

  it('should fall back to the file with the lowest id when no type in the priority matches', () => {
    const result = withPrimaryFileByPriority(book(1, 1, [file(12, 'PDF'), file(10, 'EPUB')]), ['CBX']);

    expect(result.primaryFile?.id).toBe(10);
    expect(result.alternativeFormats?.map(f => f.id)).toEqual([12]);
  });

  it('should return the same book when its primary file does not change', () => {
    const original = book(1, 1, [file(10, 'EPUB'), file(11, 'PDF')]);

    expect(withPrimaryFileByPriority(original, ['EPUB'])).toBe(original);
    const single = book(2, 1, [file(20, 'PDF')]);
    expect(withPrimaryFileByPriority(single, ['EPUB'])).toBe(single);
  });
});

describe('BookService.applyLibraryUpdate', () => {
  let service: BookService;
  let state: BookStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        BookStateService,
        {provide: AuthService, useValue: {token$: of('token')}},
        {provide: BookPatchService, useValue: {}},
        {provide: MessageService, useValue: {}},
        {provide: Router, useValue: {}},
        {provide: TranslocoService, useValue: {}},
      ]
    });
    service = TestBed.inject(BookService);
    state = TestBed.inject(BookStateService);
    state.updateBookState({
      books: [
        book(1, 1, [file(10, 'EPUB'), file(11, 'PDF')]),
        book(2, 2, [file(20, 'EPUB'), file(21, 'PDF')]),
      ],
      loaded: true,
      error: null,
    });
  });

  const books = () => state.getCurrentBookState().books!;

  it('should rename the library on its books only', () => {
    service.applyLibraryUpdate(library(1, 'Library 1'), library(1, 'Novels'));

    expect(books().map(b => b.libraryName)).toEqual(['Novels', 'Library 2']);
  });

  it('should choose the primary files again when the format priority changes', () => {
    service.applyLibraryUpdate(library(1, 'Library 1', ['EPUB']), library(1, 'Library 1', ['PDF']));

    expect(books().map(b => b.primaryFile?.id)).toEqual([11, 20]);
  });

  it('should leave the book list alone when nothing the books show changed', () => {
    const before = state.getCurrentBookState();

    service.applyLibraryUpdate(library(1, 'Library 1', ['EPUB']), library(1, 'Library 1', ['EPUB']));

    expect(state.getCurrentBookState()).toBe(before);
  });
});
