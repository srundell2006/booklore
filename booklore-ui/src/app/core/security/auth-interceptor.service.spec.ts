import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {HttpClient, provideHttpClient, withInterceptors} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {Router} from '@angular/router';
import {Subject} from 'rxjs';
import {AuthInterceptorService} from './auth-interceptor.service';
import {AuthService} from '../../shared/service/auth.service';
import {API_CONFIG} from '../config/api-config';

describe('AuthInterceptorService token refresh', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let refresh$: Subject<{ accessToken: string; refreshToken: string }>;
  let authService: {
    getInternalAccessToken: ReturnType<typeof vi.fn>;
    internalRefreshToken: ReturnType<typeof vi.fn>;
    saveInternalTokens: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };
  const url = (path: string) => `${API_CONFIG.BASE_URL}/api/v1/${path}`;

  beforeEach(() => {
    refresh$ = new Subject();
    authService = {
      getInternalAccessToken: vi.fn(() => 'expired'),
      internalRefreshToken: vi.fn(() => refresh$),
      saveInternalTokens: vi.fn(),
      logout: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([AuthInterceptorService])),
        provideHttpClientTesting(),
        {provide: AuthService, useValue: authService},
        {provide: Router, useValue: {}},
      ]
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
  });

  function getTwoBooksThatBothGet401() {
    const results: { first?: unknown; second?: unknown; firstError?: unknown; secondError?: unknown } = {};
    http.get(url('books/1')).subscribe({next: v => results.first = v, error: e => results.firstError = e});
    http.get(url('books/2')).subscribe({next: v => results.second = v, error: e => results.secondError = e});
    backend.expectOne(url('books/1')).flush(null, {status: 401, statusText: 'Unauthorized'});
    backend.expectOne(url('books/2')).flush(null, {status: 401, statusText: 'Unauthorized'});
    return results;
  }

  it('should refresh once and retry every waiting request with the new token', () => {
    const results = getTwoBooksThatBothGet401();
    expect(authService.internalRefreshToken).toHaveBeenCalledTimes(1);

    refresh$.next({accessToken: 'fresh', refreshToken: 'r'});
    refresh$.complete();

    const retries = backend.match(req => req.url.startsWith(url('books/')));
    expect(retries.map(r => r.request.headers.get('Authorization'))).toEqual(['Bearer fresh', 'Bearer fresh']);
    retries.forEach(r => r.flush({ok: true}));
    expect(results.first).toEqual({ok: true});
    expect(results.second).toEqual({ok: true});
  });

  it('should fail every waiting request when the refresh fails', () => {
    const results = getTwoBooksThatBothGet401();

    refresh$.error(new Error('refresh rejected'));

    expect(results.firstError).toBeInstanceOf(Error);
    expect(results.secondError).toBeInstanceOf(Error);
    expect(authService.logout).toHaveBeenCalledTimes(1);
  });

  it('should start a new refresh after a finished one', () => {
    getTwoBooksThatBothGet401();
    refresh$.error(new Error('refresh rejected'));

    refresh$ = new Subject();
    http.get(url('books/3')).subscribe({error: () => undefined});
    backend.expectOne(url('books/3')).flush(null, {status: 401, statusText: 'Unauthorized'});

    expect(authService.internalRefreshToken).toHaveBeenCalledTimes(2);
    refresh$.error(new Error('refresh rejected'));
  });
});
