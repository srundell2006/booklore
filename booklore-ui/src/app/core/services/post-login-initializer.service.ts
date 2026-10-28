import {inject, Injectable} from '@angular/core';
import {IconService} from '../../shared/services/icon.service';
import {forkJoin, Observable, of, timer} from 'rxjs';
import {catchError, filter, map, retry, switchMap, take} from 'rxjs/operators';
import {AuthInitializationService} from '../security/auth-initialization-service';

const ICON_PRELOAD_RETRIES = 5;
const ICON_PRELOAD_FIRST_DELAY_MS = 2000;
const ICON_PRELOAD_MAX_DELAY_MS = 60000;

@Injectable({
  providedIn: 'root'
})
export class PostLoginInitializerService {

  private iconService = inject(IconService);
  private authInit = inject(AuthInitializationService);

  initialize(): Observable<void> {
    // Called at startup with the stored token, which may have expired: wait until auth has settled
    return this.authInit.initialized$.pipe(
      filter(ready => ready),
      take(1),
      switchMap(() => forkJoin([
        this.preloadIcons(),
      ])),
      map(() => undefined)
    );
  }

  private preloadIcons(): Observable<void> {
    return this.iconService.preloadAllIcons().pipe(
      retry({
        count: ICON_PRELOAD_RETRIES,
        delay: (_err, retryCount) => timer(Math.min(ICON_PRELOAD_FIRST_DELAY_MS * 2 ** (retryCount - 1), ICON_PRELOAD_MAX_DELAY_MS))
      }),
      catchError((err) => {
        console.error('Failed to preload icons:', err);
        return of(void 0);
      })
    );
  }
}
