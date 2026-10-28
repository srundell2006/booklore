import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {RxStompService} from './shared/websocket/rx-stomp.service';
import {BookService} from './features/book/service/book.service';
import {NotificationEventService} from './shared/websocket/notification-event.service';
import {parseLogNotification} from './shared/websocket/model/log-notification.model';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {Toast} from 'primeng/toast';
import {RouterOutlet} from '@angular/router';
import {TranslocoDirective, TranslocoPipe} from '@jsverse/transloco';
import {AuthInitializationService} from './core/security/auth-initialization-service';
import {AppConfigService} from './shared/service/app-config.service';
import {MetadataBatchProgressNotification} from './shared/model/metadata-batch-progress.model';
import {MetadataProgressService} from './shared/service/metadata-progress.service';
import {BookdropFileNotification, BookdropFileService} from './features/bookdrop/service/bookdrop-file.service';
import {merge, Subscription} from 'rxjs';
import {TaskProgressPayload, TaskService} from './features/settings/task-management/task.service';
import {LibraryService} from './features/book/service/library.service';
import {LibraryHealthService} from './features/book/service/library-health.service';
import {LibraryLoadingService} from './features/library-creator/library-loading.service';
import {map, scan, withLatestFrom} from 'rxjs/operators';
import {AuthService} from './shared/service/auth.service';
import {Book} from './features/book/model/book.model';
import {BookChange} from './features/book/service/book-socket.service';
import {batchArrivals} from './shared/websocket/batch-arrivals';
import {Button} from 'primeng/button';
import {APP_UPDATE_TOAST_KEY} from './core/services/app-update.service';

const BOOK_CHANGE_BATCH_WINDOW_MS = 1000;
const BOOK_CHANGE_BATCH_MAX = 1000;

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  standalone: true,
  imports: [ConfirmDialog, Toast, RouterOutlet, TranslocoDirective, TranslocoPipe, Button]
})
export class AppComponent implements OnInit, OnDestroy {

  loading = true;
  offline = false;
  readonly appUpdateToastKey = APP_UPDATE_TOAST_KEY;
  private subscriptions: Subscription[] = [];
  private subscriptionsInitialized = false;

  private appConfigService = inject(AppConfigService); // DO NOT REMOVE: Used to initialize app config on startup
  private authInit = inject(AuthInitializationService);
  private bookService = inject(BookService);
  private rxStompService = inject(RxStompService);
  private notificationEventService = inject(NotificationEventService);
  private metadataProgressService = inject(MetadataProgressService);
  private bookdropFileService = inject(BookdropFileService);
  private taskService = inject(TaskService);
  private libraryService = inject(LibraryService);
  private libraryHealthService = inject(LibraryHealthService);
  private libraryLoadingService = inject(LibraryLoadingService);
  private authService = inject(AuthService);

  ngOnInit(): void {
    window.addEventListener('online', this.onOnline);
    window.addEventListener('offline', this.onOffline);

    this.authInit.initialized$.subscribe(ready => {
      this.loading = !ready;
      if (ready && !this.subscriptionsInitialized) {
        this.setupWebSocketSubscriptions();
        this.libraryHealthService.initialize();
        this.subscriptionsInitialized = true;
      }
    });
  }

  private onOnline = () => {
    this.offline = false;
  };

  private onOffline = () => {
    this.checkServerReachable().then(reachable => {
      this.offline = !reachable;
    });
  };

  private checkServerReachable(): Promise<boolean> {
    return fetch('/api/public/settings', {method: 'HEAD', cache: 'no-store'})
      .then(() => true)
      .catch(() => false);
  }

  reload(): void {
    window.location.reload();
  }

  private setupWebSocketSubscriptions(): void {
    const added$ = this.rxStompService.watch('/user/queue/book-add').pipe(
      map(msg => JSON.parse(msg.body) as Book),
      withLatestFrom(this.libraryService.largeLibraryLoading$),
      // The progress of a large import still counts every book as it arrives
      scan((acc, [book, loadingState]) => {
        if (loadingState.isLoading) {
          const newCount = acc.count + 1;
          this.libraryLoadingService.showBookLoadingProgress(book.metadata?.title || 'Unknown Book', newCount, loadingState.expectedCount);
          if (newCount >= loadingState.expectedCount) {
            this.libraryService.setLargeLibraryLoading(false, 0);
            return {count: 0, book};
          }
          return {count: newCount, book};
        }
        return {count: 0, book};
      }, {count: 0, book: null as Book | null}),
      map(({book}): BookChange => ({kind: 'created', book: book!}))
    );
    const updated$ = merge(
      this.rxStompService.watch('/user/queue/book-update'),
      this.rxStompService.watch('/user/queue/book-metadata-update')
    ).pipe(
      map((msg): BookChange => ({kind: 'updated', books: [JSON.parse(msg.body) as Book]}))
    );
    const batchUpdated$ = this.rxStompService.watch('/user/queue/book-metadata-batch-update').pipe(
      map((msg): BookChange => ({kind: 'updated', books: JSON.parse(msg.body) as Book[]}))
    );
    const coverUpdated$ = this.rxStompService.watch('/user/queue/books-cover-update').pipe(
      map((msg): BookChange => ({kind: 'coverUpdated', patches: JSON.parse(msg.body)}))
    );
    const removed$ = this.rxStompService.watch('/user/queue/books-remove').pipe(
      map((msg): BookChange => ({kind: 'removed', ids: JSON.parse(msg.body) as number[]}))
    );

    // A scan sends one message per book, and each applied alone would copy the whole list and recompute
    // everything that reads it. All changes to the list share one batch so they still apply in arrival order.
    this.subscriptions.push(
      merge(added$, updated$, batchUpdated$, coverUpdated$, removed$).pipe(
        batchArrivals(BOOK_CHANGE_BATCH_WINDOW_MS, BOOK_CHANGE_BATCH_MAX)
      ).subscribe(changes => this.bookService.handleBookChanges(changes))
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/book-metadata-batch-progress').subscribe(msg =>
        this.metadataProgressService.handleIncomingProgress(JSON.parse(msg.body) as MetadataBatchProgressNotification)
      )
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/log').subscribe(msg => {
        const logNotification = parseLogNotification(msg.body);
        this.notificationEventService.handleNewNotification(logNotification);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/bookdrop-file').subscribe(msg => {
        const notification = JSON.parse(msg.body) as BookdropFileNotification;
        this.bookdropFileService.handleIncomingFile(notification);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/task-progress').subscribe(msg => {
        const progress = JSON.parse(msg.body) as TaskProgressPayload;
        this.taskService.handleTaskProgress(progress);
      })
    );
    this.subscriptions.push(
      this.rxStompService.watch('/user/queue/session-revoked').subscribe(() => {
        this.authService.forceLogout('session_revoked');
      })
    );
  }

  ngOnDestroy(): void {
    window.removeEventListener('online', this.onOnline);
    window.removeEventListener('offline', this.onOffline);
    this.subscriptions.forEach(sub => sub.unsubscribe());
    this.libraryLoadingService.hide();
  }
}
