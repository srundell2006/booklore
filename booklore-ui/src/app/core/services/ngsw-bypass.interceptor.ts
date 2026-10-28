import {HttpHandlerFn, HttpInterceptorFn} from '@angular/common/http';

/**
 * Keeps the service worker out of every call to the API. Left to it, the worker copies each response it
 * passes on, the full book list included, and holds the request open in its own thread meanwhile.
 * Only same-origin requests get the header: a cross-origin one (the dev server calls the API on another
 * port, and runs no worker) would need it allowed by CORS.
 */
export const NgswBypassInterceptor: HttpInterceptorFn = (req, next: HttpHandlerFn) => {
  const isApiRequest = req.url.startsWith('/api/') || req.url.startsWith(`${window.location.origin}/api/`);
  return next(isApiRequest ? req.clone({setHeaders: {'ngsw-bypass': 'true'}}) : req);
};
