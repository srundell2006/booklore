import {inject, Injectable} from '@angular/core';
import {NavigationEnd, Router} from '@angular/router';
import {filter, take} from 'rxjs/operators';

/** The query parameter, and request header, that makes the service worker pass a request straight to the network. */
export const NGSW_BYPASS = 'ngsw-bypass';

const LAST_SIGN_IN_REDIRECT_KEY = 'forwardAuthSignInRedirectAt';
const MIN_SIGN_IN_REDIRECT_INTERVAL_MS = 10 * 60 * 1000;
const OIDC_CALLBACK_PATH = '/oauth2-callback';

export interface ForwardAuthCheckContext {
  fetch: (input: string, init: RequestInit) => Promise<Response>;
  storage: Pick<Storage, 'getItem' | 'setItem'>;
  location: Pick<Location, 'href' | 'assign'>;
  now: () => number;
}

/** The current URL with the parameter that keeps the service worker out of its navigation. */
export function withNgswBypass(href: string): string {
  const url = new URL(href);
  url.searchParams.set(NGSW_BYPASS, 'true');
  return url.toString();
}

/**
 * When a sign-in proxy in front of Booklore (forward auth) answers for the page with a redirect, its own
 * session has ended while the service worker kept serving the app from its cache, and every file the
 * worker has not cached, the icon font or a new version, now fails. Loading the page once past the worker
 * goes through the proxy, which signs the user in again (silently while its main session lasts) and comes
 * back to the same URL. Returns whether it navigated away. The proxy comes back through its own callback
 * on Booklore's origin, a path whose first segment has a dot. The navigationUrls of ngsw-config.json
 * exclude such paths; without that the worker answers the callback with the cached app and the sign-in
 * never completes.
 *
 * A failed fetch (offline, server down) is not a sign-in problem. At most one such navigation is made in
 * ten minutes, so a proxy that keeps redirecting cannot reload the page in a loop.
 */
export async function reSignInIfProxySessionEnded(context: ForwardAuthCheckContext): Promise<boolean> {
  let response: Response;
  try {
    response = await context.fetch('/index.html', {
      redirect: 'manual',
      cache: 'no-store',
      headers: {[NGSW_BYPASS]: 'true'},
      credentials: 'same-origin',
    });
  } catch {
    return false;
  }
  if (response.type !== 'opaqueredirect') {
    return false;
  }

  try {
    const lastRedirect = Number(context.storage.getItem(LAST_SIGN_IN_REDIRECT_KEY));
    const now = context.now();
    if (lastRedirect && now - lastRedirect >= 0 && now - lastRedirect < MIN_SIGN_IN_REDIRECT_INTERVAL_MS) {
      return false;
    }
    context.storage.setItem(LAST_SIGN_IN_REDIRECT_KEY, String(now));
  } catch {
    // Without storage there is no loop protection, so no navigation either
    return false;
  }

  context.location.assign(withNgswBypass(context.location.href));
  return true;
}

@Injectable({providedIn: 'root'})
export class ForwardAuthSessionService {

  private router = inject(Router);

  /** Run once at startup, in production only. */
  initialize(): void {
    if (new URLSearchParams(window.location.search).has(NGSW_BYPASS)) {
      this.removeBypassParamAfterFirstNavigation();
    }

    // Booklore's own sign-in return: its code and PKCE state are single-use, so this page must not be loaded twice
    if (window.location.pathname === OIDC_CALLBACK_PATH) {
      return;
    }

    reSignInIfProxySessionEnded({
      fetch: (input, init) => fetch(input, init),
      // Read on use: merely reading window.sessionStorage throws where site storage is blocked
      storage: {
        getItem: key => window.sessionStorage.getItem(key),
        setItem: (key, value) => window.sessionStorage.setItem(key, value),
      },
      location: window.location,
      now: () => Date.now(),
    }).catch(err => console.error('[ForwardAuth] Session check failed:', err));
  }

  private removeBypassParamAfterFirstNavigation(): void {
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      take(1)
    ).subscribe(() => {
      const urlTree = this.router.parseUrl(this.router.url);
      if (!(NGSW_BYPASS in urlTree.queryParams)) return;
      delete urlTree.queryParams[NGSW_BYPASS];
      this.router.navigateByUrl(urlTree, {replaceUrl: true});
    });
  }
}
