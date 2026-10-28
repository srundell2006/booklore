import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {Subject} from 'rxjs';
import {batchArrivals} from './batch-arrivals';

describe('batchArrivals', () => {
  let source: Subject<number>;
  let batches: number[][];

  beforeEach(() => {
    vi.useFakeTimers();
    source = new Subject<number>();
    batches = [];
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should emit what arrived within the window after its first value', () => {
    source.pipe(batchArrivals(1000, 100)).subscribe(batch => batches.push(batch));

    source.next(1);
    vi.advanceTimersByTime(600);
    source.next(2);
    expect(batches).toEqual([]);

    vi.advanceTimersByTime(400);
    expect(batches).toEqual([[1, 2]]);

    source.next(3);
    vi.advanceTimersByTime(1000);
    expect(batches).toEqual([[1, 2], [3]]);
  });

  it('should emit at once when the batch is full', () => {
    source.pipe(batchArrivals(1000, 3)).subscribe(batch => batches.push(batch));

    [1, 2, 3, 4].forEach(value => source.next(value));
    expect(batches).toEqual([[1, 2, 3]]);

    vi.advanceTimersByTime(1000);
    expect(batches).toEqual([[1, 2, 3], [4]]);
  });

  it('should run no timer and emit nothing while idle', () => {
    source.pipe(batchArrivals(1000, 3)).subscribe(batch => batches.push(batch));

    vi.advanceTimersByTime(5000);
    expect(batches).toEqual([]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('should emit what is left when the source completes', () => {
    let completed = false;
    source.pipe(batchArrivals(1000, 10)).subscribe({next: batch => batches.push(batch), complete: () => completed = true});

    source.next(1);
    source.complete();

    expect(batches).toEqual([[1]]);
    expect(completed).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('should drop its timer when unsubscribed', () => {
    const subscription = source.pipe(batchArrivals(1000, 10)).subscribe(batch => batches.push(batch));

    source.next(1);
    subscription.unsubscribe();
    vi.advanceTimersByTime(1000);

    expect(batches).toEqual([]);
    expect(vi.getTimerCount()).toBe(0);
  });
});
