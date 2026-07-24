import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../core/config/api-config';
import {BookAcquisitionSettings, ConnectionTestResult, ProwlarrRelease, WantedBook} from './wanted-book.model';

@Injectable({providedIn: 'root'})
export class WantedBookService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/wanted-books`;
  private http = inject(HttpClient);

  list(): Observable<WantedBook[]> {
    return this.http.get<WantedBook[]>(this.url);
  }

  add(book: Partial<WantedBook>): Observable<WantedBook> {
    return this.http.post<WantedBook>(this.url, book);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }

  reset(id: number): Observable<WantedBook> {
    return this.http.patch<WantedBook>(`${this.url}/${id}/reset`, {});
  }

  search(id: number): Observable<ProwlarrRelease[]> {
    return this.http.post<ProwlarrRelease[]>(`${this.url}/${id}/search`, {});
  }

  grab(id: number, release: ProwlarrRelease): Observable<WantedBook> {
    return this.http.post<WantedBook>(`${this.url}/${id}/grab`, release);
  }

  searchAll(): Observable<void> {
    return this.http.post<void>(`${this.url}/search-all`, {});
  }

  testConnections(settings: BookAcquisitionSettings): Observable<ConnectionTestResult> {
    return this.http.post<ConnectionTestResult>(`${this.url}/test-connections`, settings);
  }
}
