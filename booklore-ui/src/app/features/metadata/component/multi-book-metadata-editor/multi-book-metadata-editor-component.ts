import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {Tab, TabList, TabPanel, TabPanels, Tabs} from 'primeng/tabs';
import {Book} from '../../../book/model/book.model';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {BookService} from '../../../book/service/book.service';
import {BookStateService} from '../../../book/service/book-state.service';
import {bookIdsOf} from '../../../book/service/book-socket.service';
import {LocalWriteService, writeTouchesBook} from '../../../../core/services/catalog-write.interceptor';
import {BehaviorSubject, EMPTY, merge, Observable, of, Subject} from 'rxjs';
import {UserService} from '../../../settings/user-management/user.service';
import {catchError, debounceTime, distinctUntilChanged, filter, map, pairwise, shareReplay, switchMap, takeUntil} from 'rxjs/operators';
import {MetadataEditorComponent} from '../book-metadata-center/metadata-editor/metadata-editor.component';
import {MetadataSearcherComponent} from '../book-metadata-center/metadata-searcher/metadata-searcher.component';
import {Button} from 'primeng/button';

const REFETCH_DEBOUNCE_MS = 300;

@Component({
  selector: 'app-multi-book-metadata-editor-component',
  imports: [
    MetadataEditorComponent,
    MetadataSearcherComponent,
    Tab,
    TabList,
    TabPanel,
    TabPanels,
    Tabs,
    Button
  ],
  templateUrl: './multi-book-metadata-editor-component.html',
  standalone: true,
  styleUrl: './multi-book-metadata-editor-component.scss'
})
export class MultiBookMetadataEditorComponent implements OnInit, OnDestroy {

  bookIds: number[] = [];
  loading = false;

  currentIndex$ = new BehaviorSubject<number>(0);
  book$!: Observable<Book>;

  canEditMetadata = false;
  admin = false;

  private readonly config = inject(DynamicDialogConfig);
  readonly ref = inject(DynamicDialogRef);
  private readonly bookService = inject(BookService);
  private readonly bookStateService = inject(BookStateService);
  private readonly localWriteService = inject(LocalWriteService);
  private readonly userService = inject(UserService);

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    // Shown in id order
    this.bookIds = [...new Set<number>((this.config.data?.bookIds ?? []).map(Number))].sort((a, b) => a - b);

    this.userService.userState$.pipe(
      filter(userState => !!userState?.user && userState.loaded),
      takeUntil(this.destroy$)
    ).subscribe(userState => {
      const userPermissions = userState.user?.permissions;
      this.canEditMetadata = userPermissions?.canEditMetadata ?? false;
      this.admin = userPermissions?.admin ?? false;
    });

    // The shown book is fetched by id, so the editor opens without the book list (the series page opens it before
    // the list is loaded). It is fetched again after a change to it: a write from this client, a websocket change,
    // or a change to the list's copy of its metadata. The list arriving is no change: a fetch then would reset what
    // the user is typing
    this.book$ = this.currentIndex$.pipe(
      map(index => this.bookIds[index]),
      filter((bookId): bookId is number => bookId != null),
      distinctUntilChanged(),
      switchMap(bookId => merge(
        of(null),
        merge(
          this.localWriteService.writes$.pipe(filter(write => writeTouchesBook(write, bookId))),
          this.bookService.bookChanges$.pipe(filter(changes => bookIdsOf(changes).has(bookId))),
          this.bookStateService.bookState$.pipe(
            map(state => state.books?.find(b => b.id === bookId)?.metadata),
            distinctUntilChanged(),
            pairwise(),
            filter(([previous]) => previous !== undefined)
          )
        ).pipe(debounceTime(REFETCH_DEBOUNCE_MS))
      ).pipe(
        switchMap(() => this.bookService.getBookByIdFromAPI(bookId, true).pipe(
          catchError(() => EMPTY)
        ))
      )),
      takeUntil(this.destroy$),
      shareReplay({bufferSize: 1, refCount: true})
    );
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.currentIndex$.complete();
  }

  handleNextBook() {
    const next = this.currentIndex$.value + 1;
    if (next < this.bookIds.length) {
      this.currentIndex$.next(next);
    }
  }

  handlePreviousBook() {
    const prev = this.currentIndex$.value - 1;
    if (prev >= 0) {
      this.currentIndex$.next(prev);
    }
  }

  handleCloseDialogButton() {
    this.ref.close();
  }

  get disableNext(): boolean {
    return this.currentIndex$.value >= this.bookIds.length - 1;
  }

  get disablePrevious(): boolean {
    return this.currentIndex$.value <= 0;
  }
}
