import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {ComicCandidate} from './comic-candidate.model';

@Injectable({providedIn: 'root'})
export class ComicDetectionService {
  private http = inject(HttpClient);
  private readonly base = '/api/v1/comic-detection';

  getPending(): Observable<ComicCandidate[]> {
    return this.http.get<ComicCandidate[]>(`${this.base}/candidates`);
  }

  getPendingCount(): Observable<{ pending: number }> {
    return this.http.get<{ pending: number }>(`${this.base}/candidates/count`);
  }

  accept(id: number): Observable<void> {
    return this.http.post<void>(`${this.base}/candidates/${id}/accept`, {});
  }

  reject(id: number): Observable<void> {
    return this.http.post<void>(`${this.base}/candidates/${id}/reject`, {});
  }

  acceptAll(ids: number[]): Observable<{ accepted: number }> {
    return this.http.post<{ accepted: number }>(`${this.base}/candidates/accept`, ids);
  }

  rejectAll(ids: number[]): Observable<{ rejected: number }> {
    return this.http.post<{ rejected: number }>(`${this.base}/candidates/reject`, ids);
  }

  clearResolved(): Observable<{ deleted: number }> {
    return this.http.delete<{ deleted: number }>(`${this.base}/candidates/resolved`);
  }
}
