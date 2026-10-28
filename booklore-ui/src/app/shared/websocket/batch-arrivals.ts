import {Observable, OperatorFunction} from 'rxjs';

/**
 * Collects values into arrays: a batch is emitted `windowMs` after its first value arrived, or as soon as
 * it holds `maxCount` values, and what is left is emitted when the source ends. Unlike bufferTime, no timer
 * runs while nothing arrives, so an idle app is not woken (and change-detected) every window.
 */
export function batchArrivals<T>(windowMs: number, maxCount: number): OperatorFunction<T, T[]> {
  return source => new Observable<T[]>(subscriber => {
    let batch: T[] = [];
    let timer: ReturnType<typeof setTimeout> | null = null;

    const flush = () => {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
      if (batch.length > 0) {
        const values = batch;
        batch = [];
        subscriber.next(values);
      }
    };

    const subscription = source.subscribe({
      next: value => {
        batch.push(value);
        if (batch.length >= maxCount) {
          flush();
        } else if (timer === null) {
          timer = setTimeout(flush, windowMs);
        }
      },
      error: err => {
        flush();
        subscriber.error(err);
      },
      complete: () => {
        flush();
        subscriber.complete();
      }
    });

    return () => {
      if (timer !== null) {
        clearTimeout(timer);
      }
      subscription.unsubscribe();
    };
  });
}
