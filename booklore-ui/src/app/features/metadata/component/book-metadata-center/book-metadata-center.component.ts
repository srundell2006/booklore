import {AsyncPipe, NgClass} from '@angular/common';
import {Component, inject, OnDestroy, OnInit, Optional} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {UserService} from '../../../settings/user-management/user.service';
import {Book, BookRecommendation} from '../../../book/model/book.model';
import {BehaviorSubject, Observable, Subject} from 'rxjs';
import {distinctUntilChanged, filter, map, shareReplay, switchMap, take, takeUntil, tap,} from 'rxjs/operators';
import {BookService} from '../../../book/service/book.service';
import {AppSettingsService} from '../../../../shared/service/app-settings.service';
import {Tab, TabList, TabPanel, TabPanels, Tabs,} from 'primeng/tabs';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {Button} from 'primeng/button';
import {BookMetadataHostService} from '../../../../shared/service/book-metadata-host.service';
import {TranslocoDirective} from '@jsverse/transloco';
import {MetadataViewerComponent} from './metadata-viewer/metadata-viewer.component';
import {MetadataEditorComponent} from './metadata-editor/metadata-editor.component';
import {MetadataSearcherComponent} from './metadata-searcher/metadata-searcher.component';
import {SidecarViewerComponent} from './sidecar-viewer/sidecar-viewer.component';
import {TaskHelperService} from '../../../settings/task-management/task-helper.service';

@Component({
  selector: 'app-book-metadata-center',
  standalone: true,
  templateUrl: './book-metadata-center.component.html',
  imports: [
    Tabs,
    TabList,
    Tab,
    TabPanels,
    TabPanel,
    MetadataViewerComponent,
    MetadataEditorComponent,
    MetadataSearcherComponent,
    SidecarViewerComponent,
    Button,
    TranslocoDirective,
    AsyncPipe,
    NgClass
  ],
  styleUrls: ['./book-metadata-center.component.scss'],
})
export class BookMetadataCenterComponent implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private bookService = inject(BookService);
  private userService = inject(UserService);
  private appSettingsService = inject(AppSettingsService);
  private metadataHostService = inject(BookMetadataHostService);
  private taskHelperService = inject(TaskHelperService);
  private destroy$ = new Subject<void>();

  book$!: Observable<Book>;
  recommendedBooks: BookRecommendation[] = [];
  private _tab: string = 'view';
  canEditMetadata: boolean = false;
  admin: boolean = false;
  isPhysical: boolean = false;
  isLocalStorage: boolean = true;
  verifyingContent = false;
  applyingDetectedMetadata = false;

  private appSettings$ = this.appSettingsService.appSettings$;
  private currentBookId$ = new BehaviorSubject<number | null>(null);
  private validTabs = ['view', 'edit', 'match', 'sidecar'];

  get tab(): string {
    return this._tab;
  }

  set tab(value: string) {
    this._tab = value;

    if (!this.config) {
      this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab: value },
        queryParamsHandling: 'merge'
      });
    }
  }

  constructor(
    @Optional() public config?: DynamicDialogConfig,
    @Optional() public ref?: DynamicDialogRef
  ) {
  }

  ngOnInit(): void {
    const bookIdFromDialog: number | undefined = this.config?.data?.bookId;
    if (bookIdFromDialog != null) {
      this.currentBookId$.next(bookIdFromDialog);
    } else {
      this.route.paramMap
        .pipe(
          map(params => Number(params.get('bookId'))),
          filter(bookId => !isNaN(bookId)),
          takeUntil(this.destroy$)
        )
        .subscribe(bookId => this.currentBookId$.next(bookId));
    }

    this.metadataHostService.bookSwitches$
      .pipe(
        filter((bookId): bookId is number => !!bookId),
        distinctUntilChanged(),
        takeUntil(this.destroy$)
      )
      .subscribe(bookId => {
        this.currentBookId$.next(bookId);
      });

    this.book$ = this.currentBookId$.pipe(
      filter((bookId): bookId is number => bookId != null),
      distinctUntilChanged(),
      switchMap(bookId =>
        this.bookService.bookState$.pipe(
          map(state => state.books?.find(b => b.id === bookId)),
          filter((book): book is Book => !!book && !!book.metadata),
          distinctUntilChanged(),
          switchMap(book =>
            this.bookService.getBookByIdFromAPI(book.id, true)
          )
        )
      ),
      tap(book => this.isPhysical = book.isPhysical ?? false),
      takeUntil(this.destroy$),
      shareReplay({bufferSize: 1, refCount: true})
    );

    this.currentBookId$
      .pipe(
        filter((id): id is number => id != null),
        takeUntil(this.destroy$)
      )
      .subscribe(bookId => this.fetchBookRecommendationsIfNeeded(bookId));

    this.route.queryParamMap
      .pipe(
        map(params => params.get('tab') ?? 'view'),
        distinctUntilChanged(),
        takeUntil(this.destroy$)
      )
      .subscribe(tabParam => {
        this._tab = this.validTabs.includes(tabParam) ? tabParam : 'view';
      });

    this.userService.userState$
      .pipe(
        filter(userState => !!userState?.user && userState.loaded),
        takeUntil(this.destroy$)
      )
      .subscribe(userState => {
        this.canEditMetadata = userState.user?.permissions?.canEditMetadata ?? false;
        this.admin = userState.user?.permissions?.admin ?? false;
      });

    this.appSettings$
      .pipe(
        filter(settings => !!settings),
        take(1),
        takeUntil(this.destroy$)
      )
      .subscribe(settings => {
        this.isLocalStorage = settings!.diskType === 'LOCAL';
      });
  }

  verifyContent(bookId: number): void {
    this.verifyingContent = true;
    this.taskHelperService.verifyAudiobookTask({
      scanType: 'BOOKS',
      bookIds: [bookId]
    }).pipe(take(1)).subscribe({
      next: () => { this.verifyingContent = false; },
      error: () => { this.verifyingContent = false; }
    });
  }

  applyDetectedMetadata(bookId: number): void {
    this.applyingDetectedMetadata = true;
    this.bookService.applyVerificationMetadata(bookId).pipe(take(1)).subscribe({
      next: () => { this.applyingDetectedMetadata = false; },
      error: () => { this.applyingDetectedMetadata = false; }
    });
  }

  getVerificationClass(status?: string | null): Record<string, boolean> {
    return {
      'verification-verified': status === 'VERIFIED',
      'verification-mismatch': status === 'MISMATCH',
      'verification-error': status === 'ERROR',
      'verification-skipped': status === 'SKIPPED',
      'verification-unverified': !status
    };
  }

  getVerificationIcon(status?: string | null): string {
    switch (status) {
      case 'VERIFIED': return 'pi pi-check-circle';
      case 'MISMATCH': return 'pi pi-exclamation-triangle';
      case 'ERROR': return 'pi pi-times-circle';
      case 'SKIPPED': return 'pi pi-minus-circle';
      default: return 'pi pi-question-circle';
    }
  }

  getVerificationLabel(status?: string | null): string {
    switch (status) {
      case 'VERIFIED': return 'Content verified';
      case 'MISMATCH': return 'Content mismatch detected';
      case 'ERROR': return 'Verification error';
      case 'SKIPPED': return 'Verification skipped';
      default: return 'Not yet verified';
    }
  }

  private fetchBookRecommendationsIfNeeded(bookId: number): void {
    this.appSettings$.pipe(
      filter(settings => settings != null),
      take(1),
      filter(settings => settings!.similarBookRecommendation ?? false),
      switchMap(() => this.bookService.getBookRecommendations(bookId)),
      takeUntil(this.destroy$)
    ).subscribe(recommendations => {
      this.recommendedBooks = recommendations.sort(
        (a, b) => (b.similarityScore ?? 0) - (a.similarityScore ?? 0)
      );
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
