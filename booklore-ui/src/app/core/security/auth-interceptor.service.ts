import {HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest} from '@angular/common/http';
import {inject} from '@angular/core';
import {Router} from '@angular/router';
import {catchError, finalize, map, shareReplay, switchMap, take} from 'rxjs/operators';
import {Observable, throwError} from 'rxjs';
import {AuthService} from '../../shared/service/auth.service';
import {API_CONFIG} from '../config/api-config';

export const AuthInterceptorService: HttpInterceptorFn = (req, next: HttpHandlerFn) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const token = authService.getInternalAccessToken();
  const isApiRequest = req.url.startsWith(`${API_CONFIG.BASE_URL}/api/`);

  const authReq = (token && isApiRequest) ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      // A 401 from the refresh call itself would otherwise wait on its own result
      if (error.status === 401 && !req.url.endsWith('/api/v1/auth/refresh')) {
        return handle401Error(authService, authReq, next, router);
      }
      return throwError(() => error);
    })
  );
};

// The refresh in progress, shared by every request that got a 401 meanwhile. It runs to its end even
// when the request that started it is cancelled, and when it fails each waiting request fails with it.
let refreshInProgress$: Observable<string> | null = null;

function handle401Error(authService: AuthService, request: HttpRequest<unknown>, next: HttpHandlerFn, router: Router): Observable<HttpEvent<unknown>> {
  if (!refreshInProgress$) {
    refreshInProgress$ = authService.internalRefreshToken().pipe(
      map(response => {
        const { accessToken, refreshToken } = response;
        if (!accessToken || !refreshToken) {
          throw new Error('Token refresh returned no tokens');
        }
        authService.saveInternalTokens(accessToken, refreshToken);
        return accessToken;
      }),
      catchError(err => {
        forceLogout(authService, router);
        return throwError(() => err);
      }),
      finalize(() => refreshInProgress$ = null),
      shareReplay({ bufferSize: 1, refCount: false })
    );
  }

  return refreshInProgress$.pipe(
    take(1),
    switchMap(token =>
      next(request.clone({
        setHeaders: { Authorization: `Bearer ${token}` }
      }))
    )
  );
}

function forceLogout(authService: AuthService, router: Router): void {
  authService.logout();
}
