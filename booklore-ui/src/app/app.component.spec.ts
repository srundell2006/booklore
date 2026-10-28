import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {AppComponent} from './app.component';
import {AuthInitializationService} from './core/security/auth-initialization-service';
import {BehaviorSubject, of, Subject} from 'rxjs';
import {RxStompService} from './shared/websocket/rx-stomp.service';
import {BookService} from './features/book/service/book.service';
import {NotificationEventService} from './shared/websocket/notification-event.service';
import {AppConfigService} from './shared/service/app-config.service';
import {MetadataProgressService} from './shared/service/metadata-progress.service';
import {BookdropFileService} from './features/bookdrop/service/bookdrop-file.service';
import {TaskService} from './features/settings/task-management/task.service';
import {LibraryService} from './features/book/service/library.service';
import {LibraryHealthService} from './features/book/service/library-health.service';
import {LibraryLoadingService} from './features/library-creator/library-loading.service';
import {TranslocoTestingModule} from '@jsverse/transloco';

describe('AppComponent offline detection', () => {
  let fixture: ComponentFixture<AppComponent>;
  let component: AppComponent;
  let authInitSubject: BehaviorSubject<boolean>;

  beforeEach(() => {
    authInitSubject = new BehaviorSubject<boolean>(false);

    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        {provide: AuthInitializationService, useValue: {initialized$: authInitSubject.asObservable()}},
        {provide: RxStompService, useValue: {watch: vi.fn(() => of())}},
        {provide: BookService, useValue: {}},
        {provide: NotificationEventService, useValue: {}},
        {provide: AppConfigService, useValue: {}},
        {provide: MetadataProgressService, useValue: {}},
        {provide: BookdropFileService, useValue: {}},
        {provide: TaskService, useValue: {}},
        {provide: LibraryService, useValue: {largeLibraryLoading$: of({isLoading: false, expectedCount: 0})}},
        {provide: LibraryHealthService, useValue: {initialize: vi.fn()}},
        {provide: LibraryLoadingService, useValue: {hide: vi.fn()}},
      ]
    });

    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should initialize with offline set to false', () => {
    expect(component.offline).toBe(false);
  });

  it('should set offline to false when online event fires', () => {
    component.offline = true;
    window.dispatchEvent(new Event('online'));
    expect(component.offline).toBe(false);
  });

  it('should not show offline when server is reachable despite browser offline event', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, {status: 200}));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(false);
    });
  });

  it('should show offline when server is unreachable on browser offline event', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(true);
    });
  });

  it('should ping server with HEAD method and no-store cache', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, {status: 200}));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(fetchSpy).toHaveBeenCalledWith('/api/public/settings', {method: 'HEAD', cache: 'no-store'});
    });
  });

  it('should treat server errors as reachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, {status: 500}));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(false);
    });
  });

  it('should treat network timeout as unreachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('network timeout'));

    window.dispatchEvent(new Event('offline'));

    await vi.waitFor(() => {
      expect(component.offline).toBe(true);
    });
  });
});

describe('AppComponent book websocket batching', () => {
  let component: AppComponent;
  let topics: Map<string, Subject<{ body: string }>>;
  let bookService: { handleBookChanges: ReturnType<typeof vi.fn> };
  let libraryService: { largeLibraryLoading$: BehaviorSubject<{ isLoading: boolean; expectedCount: number }>; setLargeLibraryLoading: ReturnType<typeof vi.fn> };
  let libraryLoadingService: { showBookLoadingProgress: ReturnType<typeof vi.fn>; hide: ReturnType<typeof vi.fn> };

  const send = (topic: string, payload: unknown) => topics.get(topic)!.next({body: JSON.stringify(payload)});
  const book = (id: number) => ({id, metadata: {title: `Book ${id}`}});

  beforeEach(() => {
    vi.useFakeTimers();
    topics = new Map();
    bookService = {handleBookChanges: vi.fn()};
    libraryService = {
      largeLibraryLoading$: new BehaviorSubject<{ isLoading: boolean; expectedCount: number }>({isLoading: false, expectedCount: 0}),
      setLargeLibraryLoading: vi.fn(),
    };
    libraryLoadingService = {showBookLoadingProgress: vi.fn(), hide: vi.fn()};

    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        {provide: AuthInitializationService, useValue: {initialized$: of(true)}},
        {
          provide: RxStompService, useValue: {
            watch: vi.fn((topic: string) => {
              if (!topics.has(topic)) topics.set(topic, new Subject());
              return topics.get(topic)!.asObservable();
            })
          }
        },
        {provide: BookService, useValue: bookService},
        {provide: NotificationEventService, useValue: {}},
        {provide: AppConfigService, useValue: {}},
        {provide: MetadataProgressService, useValue: {}},
        {provide: BookdropFileService, useValue: {}},
        {provide: TaskService, useValue: {}},
        {provide: LibraryService, useValue: libraryService},
        {provide: LibraryHealthService, useValue: {initialize: vi.fn()}},
        {provide: LibraryLoadingService, useValue: libraryLoadingService},
      ]
    });

    component = TestBed.createComponent(AppComponent).componentInstance;
    component.ngOnInit();
  });

  afterEach(() => {
    component.ngOnDestroy();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('should apply the books of a scan as one batch after a second', () => {
    for (let id = 1; id <= 50; id++) {
      send('/user/queue/book-add', book(id));
    }
    expect(bookService.handleBookChanges).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1000);

    expect(bookService.handleBookChanges).toHaveBeenCalledTimes(1);
    const changes = bookService.handleBookChanges.mock.calls[0][0];
    expect(changes).toHaveLength(50);
    expect(changes[0]).toEqual({kind: 'created', book: book(1)});
  });

  it('should keep adds, updates, cover patches and removals in arrival order in one batch', () => {
    send('/user/queue/book-add', book(1));
    send('/user/queue/book-metadata-update', book(1));
    send('/user/queue/books-cover-update', [{id: 1, coverUpdatedOn: 'now'}]);
    send('/user/queue/book-update', book(2));
    send('/user/queue/book-metadata-batch-update', [book(3)]);
    send('/user/queue/books-remove', [2]);

    vi.advanceTimersByTime(1000);

    expect(bookService.handleBookChanges).toHaveBeenCalledTimes(1);
    expect(bookService.handleBookChanges.mock.calls[0][0].map((c: { kind: string }) => c.kind))
      .toEqual(['created', 'updated', 'coverUpdated', 'updated', 'updated', 'removed']);
  });

  it('should count the progress of a large import for every book as it arrives', () => {
    libraryService.largeLibraryLoading$.next({isLoading: true, expectedCount: 3});

    send('/user/queue/book-add', book(1));
    send('/user/queue/book-add', book(2));
    expect(libraryLoadingService.showBookLoadingProgress).toHaveBeenLastCalledWith('Book 2', 2, 3);
    expect(libraryService.setLargeLibraryLoading).not.toHaveBeenCalled();

    send('/user/queue/book-add', book(3));
    expect(libraryLoadingService.showBookLoadingProgress).toHaveBeenCalledTimes(3);
    expect(libraryService.setLargeLibraryLoading).toHaveBeenCalledWith(false, 0);
    expect(bookService.handleBookChanges).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1000);
    expect(bookService.handleBookChanges.mock.calls[0][0]).toHaveLength(3);
  });
});
