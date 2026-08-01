import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';
import {AudiobookMergeSettings, AudiobookVerificationSettings, EbookConversionSettings} from './media-tools-settings.model';

@Injectable({providedIn: 'root'})
export class MediaToolsService {
  private http = inject(HttpClient);
  private readonly convertUrl = `${API_CONFIG.BASE_URL}/api/v1/ebook-conversion`;
  private readonly mergeUrl = `${API_CONFIG.BASE_URL}/api/v1/audiobook-merge`;
  private readonly verifyUrl = `${API_CONFIG.BASE_URL}/api/v1/audiobook-verification`;

  testConverter(settings: EbookConversionSettings): Observable<{ ebookConverter: boolean }> {
    return this.http.post<{ ebookConverter: boolean }>(`${this.convertUrl}/test-connection`, settings);
  }

  testMerge(settings: AudiobookMergeSettings): Observable<{ m4bMerge: boolean }> {
    return this.http.post<{ m4bMerge: boolean }>(`${this.mergeUrl}/test-connection`, settings);
  }

  testWhisper(settings: AudiobookVerificationSettings): Observable<{ whisper: boolean }> {
    return this.http.post<{ whisper: boolean }>(`${this.verifyUrl}/test-connection`, settings);
  }

  convertBook(bookId: number, target?: string): Observable<unknown> {
    // Build params explicitly: a conditional object literal widens to
    // { target?: undefined }, which HttpParams' index signature rejects.
    let params = new HttpParams();
    if (target) {
      params = params.set('target', target);
    }
    return this.http.post(`${this.convertUrl}/books/${bookId}`, {}, {params});
  }

  mergeAudiobook(bookId: number): Observable<unknown> {
    return this.http.post(`${this.mergeUrl}/books/${bookId}`, {});
  }

  cancelMerge(bookId: number): Observable<unknown> {
    return this.http.delete(`${this.mergeUrl}/books/${bookId}`);
  }

  runningMerges(): Observable<{ bookIds: number[] }> {
    return this.http.get<{ bookIds: number[] }>(`${this.mergeUrl}/running`);
  }
}
