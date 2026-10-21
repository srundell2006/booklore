import {describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {MessageService} from 'primeng/api';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {of} from 'rxjs';
import {FileMoverComponent} from './file-mover-component';
import {BookService} from '../../../features/book/service/book.service';
import {LibraryService} from '../../../features/book/service/library.service';
import {FileOperationsService} from '../../service/file-operations.service';
import {AppSettingsService} from '../../service/app-settings.service';
import {Book, BookFile, BookMetadata} from '../../../features/book/model/book.model';
import {Library} from '../../../features/book/model/library.model';

function previewFor(pattern: string, primaryFile: Partial<BookFile>, metadata: Partial<BookMetadata>) {
  const library: Library = {id: 1, name: 'Books', watch: false, fileNamingPattern: pattern, paths: [{id: 10, path: '/books'}]};
  const book = {
    id: 7,
    libraryId: 1,
    libraryName: 'Books',
    primaryFile: {id: 70, bookId: 7, ...primaryFile},
    metadata: {bookId: 7, ...metadata}
  } as Book;

  TestBed.configureTestingModule({
    providers: [
      {provide: DynamicDialogConfig, useValue: {data: {bookIds: [book.id]}}},
      {provide: DynamicDialogRef, useValue: {close: vi.fn()}},
      {provide: BookService, useValue: {getBooksByIdsFromState: vi.fn(() => [book])}},
      {provide: LibraryService, useValue: {libraryState$: of({loaded: true, libraries: [library]}), getLibrariesFromState: vi.fn(() => [library])}},
      {provide: FileOperationsService, useValue: {}},
      {provide: MessageService, useValue: {add: vi.fn()}},
      {provide: AppSettingsService, useValue: {appSettings$: of({uploadPattern: '{currentFilename}'})}},
    ]
  });

  return TestBed.createComponent(FileMoverComponent).componentInstance.filePreviews[0];
}

describe('FileMoverComponent', () => {

  it('should preview the primary file of the book', () => {
    const preview = previewFor('{authors}/{title}',
      {fileName: 'the_hobbit.epub', fileSubPath: 'Tolkien', folderBased: false},
      {title: 'The Hobbit', authors: ['J.R.R. Tolkien']});

    expect(preview.relativeOriginalPath).toBe('Tolkien/the_hobbit.epub');
    expect(preview.relativeNewPath).toBe('J.R.R. Tolkien/The Hobbit.epub');
    expect(preview.newPath).toBe('/books/J.R.R. Tolkien/The Hobbit.epub');
  });

  it('should not give a folder audiobook an extension from the dot in its name', () => {
    const preview = previewFor('{seriesIndex}. {title} - {authors} ({year})',
      {fileName: '01. The Assassin\'s Blade', fileSubPath: 'Throne of Glass', folderBased: true},
      {title: 'The Assassin\'s Blade', authors: ['Sarah J. Maas'], seriesNumber: 1, publishedDate: '2015-03-03'});

    expect(preview.relativeNewPath).toBe('01. The Assassin\'s Blade - Sarah J. Maas (2015)');
  });

  it('should cut a long title to the bytes the server allows', () => {
    const preview = previewFor('{title}', {fileName: 'book.epub'}, {title: 'ب'.repeat(120), authors: ['نجيب محفوظ']});

    expect(preview.relativeNewPath).toBe(`${'ب'.repeat(100)}.epub`);
  });

  it('should cut a long author list after the last author that fits', () => {
    const authors = Array.from({length: 10}, (_, i) => `${'某'.repeat(6)} ${i}`);
    const preview = previewFor('{authors}/{title}', {fileName: 'book.epub'}, {title: '三体', authors});

    // A folder name loses its trailing dot on the server
    expect(preview.relativeNewPath).toBe(`${authors.slice(0, 8).join(', ')} et al/三体.epub`);
  });
});
