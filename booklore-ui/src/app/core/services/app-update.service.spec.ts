import {beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {DOCUMENT} from '@angular/common';
import {SwUpdate, UnrecoverableStateEvent, VersionEvent} from '@angular/service-worker';
import {MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';
import {Subject} from 'rxjs';
import {APP_UPDATE_TOAST_KEY, AppUpdateService} from './app-update.service';

describe('AppUpdateService', () => {
  let versionUpdates: Subject<VersionEvent>;
  let unrecoverable: Subject<UnrecoverableStateEvent>;
  let messageService: {add: ReturnType<typeof vi.fn>};
  let reload: ReturnType<typeof vi.fn>;

  function setUp(isEnabled: boolean): AppUpdateService {
    versionUpdates = new Subject();
    unrecoverable = new Subject();
    messageService = {add: vi.fn()};
    reload = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        AppUpdateService,
        {provide: SwUpdate, useValue: {isEnabled, versionUpdates, unrecoverable}},
        {provide: MessageService, useValue: messageService},
        {provide: TranslocoService, useValue: {translate: (key: string) => key}},
        {provide: DOCUMENT, useValue: {location: {reload}}},
      ]
    });
    return TestBed.inject(AppUpdateService);
  }

  function versionReady(): VersionEvent {
    return {
      type: 'VERSION_READY',
      currentVersion: {hash: 'old'},
      latestVersion: {hash: 'new'},
    };
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('should offer a reload once a new version is ready, without reloading', () => {
    setUp(true).initialize();

    versionUpdates.next({type: 'VERSION_DETECTED', version: {hash: 'new'}});
    expect(messageService.add).not.toHaveBeenCalled();

    versionUpdates.next(versionReady());
    expect(messageService.add).toHaveBeenCalledTimes(1);
    expect(messageService.add).toHaveBeenCalledWith(expect.objectContaining({key: APP_UPDATE_TOAST_KEY, sticky: true}));
    expect(reload).not.toHaveBeenCalled();
  });

  it('should offer the reload only once', () => {
    setUp(true).initialize();

    versionUpdates.next(versionReady());
    versionUpdates.next(versionReady());

    expect(messageService.add).toHaveBeenCalledTimes(1);
  });

  it('should reload when the worker can no longer serve the app', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    setUp(true).initialize();

    unrecoverable.next({type: 'UNRECOVERABLE_STATE', reason: 'Hash mismatch'});

    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('should do nothing without a service worker', () => {
    setUp(false).initialize();

    versionUpdates.next(versionReady());
    unrecoverable.next({type: 'UNRECOVERABLE_STATE', reason: 'Hash mismatch'});

    expect(messageService.add).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });
});
