import {beforeEach, describe, expect, it, vi} from 'vitest';
import {ForwardAuthCheckContext, reSignInIfProxySessionEnded, withNgswBypass} from './forward-auth-session.service';

const TEN_MINUTES = 10 * 60 * 1000;

function responseOfType(type: ResponseType): Response {
  return {type} as Response;
}

describe('reSignInIfProxySessionEnded', () => {
  let stored: Map<string, string>;
  let context: ForwardAuthCheckContext;
  let fetchMock: ReturnType<typeof vi.fn<ForwardAuthCheckContext['fetch']>>;
  let assignMock: ReturnType<typeof vi.fn<(url: string | URL) => void>>;
  let now: number;

  beforeEach(() => {
    stored = new Map();
    now = 1_000_000_000;
    fetchMock = vi.fn<ForwardAuthCheckContext['fetch']>().mockResolvedValue(responseOfType('basic'));
    assignMock = vi.fn<(url: string | URL) => void>();
    context = {
      fetch: fetchMock,
      storage: {
        getItem: key => stored.get(key) ?? null,
        setItem: (key, value) => void stored.set(key, value),
      },
      location: {href: 'https://books.example.org/library/3/books?view=grid', assign: assignMock},
      now: () => now,
    };
  });

  it('should ask for the page past the worker, without following a redirect', async () => {
    await reSignInIfProxySessionEnded(context);

    expect(fetchMock).toHaveBeenCalledWith('/index.html', {
      redirect: 'manual',
      cache: 'no-store',
      headers: {'ngsw-bypass': 'true'},
      credentials: 'same-origin',
    });
  });

  it('should stay when the page loads normally', async () => {
    expect(await reSignInIfProxySessionEnded(context)).toBe(false);
    expect(assignMock).not.toHaveBeenCalled();
  });

  it('should stay when the server answers with an error', async () => {
    fetchMock.mockResolvedValue(responseOfType('error'));

    expect(await reSignInIfProxySessionEnded(context)).toBe(false);
    expect(assignMock).not.toHaveBeenCalled();
  });

  it('should stay when the request fails for a network reason', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    expect(await reSignInIfProxySessionEnded(context)).toBe(false);
    expect(assignMock).not.toHaveBeenCalled();
  });

  it('should reload the current URL past the worker when the proxy redirects', async () => {
    fetchMock.mockResolvedValue(responseOfType('opaqueredirect'));

    expect(await reSignInIfProxySessionEnded(context)).toBe(true);
    expect(assignMock).toHaveBeenCalledWith('https://books.example.org/library/3/books?view=grid&ngsw-bypass=true');
  });

  it('should not redirect again within ten minutes', async () => {
    fetchMock.mockResolvedValue(responseOfType('opaqueredirect'));
    await reSignInIfProxySessionEnded(context);

    now += TEN_MINUTES - 1;
    expect(await reSignInIfProxySessionEnded(context)).toBe(false);
    expect(assignMock).toHaveBeenCalledTimes(1);
  });

  it('should redirect again once ten minutes have passed', async () => {
    fetchMock.mockResolvedValue(responseOfType('opaqueredirect'));
    await reSignInIfProxySessionEnded(context);

    now += TEN_MINUTES;
    expect(await reSignInIfProxySessionEnded(context)).toBe(true);
    expect(assignMock).toHaveBeenCalledTimes(2);
  });

  it('should not redirect when the storage that prevents a loop cannot be used', async () => {
    fetchMock.mockResolvedValue(responseOfType('opaqueredirect'));
    context.storage = {
      getItem: () => {
        throw new DOMException('Blocked', 'SecurityError');
      },
      setItem: () => undefined,
    };

    expect(await reSignInIfProxySessionEnded(context)).toBe(false);
    expect(assignMock).not.toHaveBeenCalled();
  });
});

describe('withNgswBypass', () => {

  it('should add the parameter and keep the path, query and fragment', () => {
    expect(withNgswBypass('https://books.example.org/book/5?tab=view#notes'))
      .toBe('https://books.example.org/book/5?tab=view&ngsw-bypass=true#notes');
  });

  it('should not add the parameter twice', () => {
    expect(withNgswBypass('https://books.example.org/?ngsw-bypass=true')).toBe('https://books.example.org/?ngsw-bypass=true');
  });
});
