import {inject, Injectable} from '@angular/core';
import {DOCUMENT} from '@angular/common';
import {SwUpdate, VersionReadyEvent} from '@angular/service-worker';
import {MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';
import {filter} from 'rxjs/operators';

/** The toast in AppComponent that offers the reload. */
export const APP_UPDATE_TOAST_KEY = 'app-update';

@Injectable({providedIn: 'root'})
export class AppUpdateService {

  private swUpdate = inject(SwUpdate);
  private messageService = inject(MessageService);
  private t = inject(TranslocoService);
  private document = inject(DOCUMENT);
  private updateAnnounced = false;

  initialize(): void {
    if (!this.swUpdate.isEnabled) return;

    // The worker cannot serve this version any more (its files are gone from the server and from its
    // cache), so the page would keep failing to load what it needs; only a reload gets out of it
    this.swUpdate.unrecoverable.subscribe(event => {
      console.error('[ServiceWorker] Unrecoverable state, reloading:', event.reason);
      this.document.location.reload();
    });

    // A new version only offers the reload: the user may be reading or editing
    this.swUpdate.versionUpdates.pipe(
      filter((event): event is VersionReadyEvent => event.type === 'VERSION_READY')
    ).subscribe(() => {
      if (this.updateAnnounced) return;
      this.updateAnnounced = true;
      this.messageService.add({
        key: APP_UPDATE_TOAST_KEY,
        severity: 'info',
        summary: this.t.translate('app.updateReadySummary'),
        detail: this.t.translate('app.updateReadyDetail'),
        sticky: true,
      });
    });
  }
}
