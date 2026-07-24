import {Component, inject, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {TableModule} from 'primeng/table';
import {Button} from 'primeng/button';
import {Dialog} from 'primeng/dialog';
import {InputText} from 'primeng/inputtext';
import {Checkbox} from 'primeng/checkbox';
import {InputNumber} from 'primeng/inputnumber';
import {Tooltip} from 'primeng/tooltip';
import {Tag} from 'primeng/tag';
import {MessageService} from 'primeng/api';
import {WantedBookService} from './wanted-book.service';
import {BookAcquisitionSettings, ConnectionTestResult, ProwlarrRelease, WantedBook} from './wanted-book.model';
import {AppSettingsService} from '../../shared/service/app-settings.service';
import {filter, take} from 'rxjs/operators';
import {Router} from '@angular/router';

@Component({
  selector: 'app-wanted-books',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, Button, Dialog, InputText, Checkbox, InputNumber, Tooltip, Tag],
  templateUrl: './wanted-books.component.html',
  styleUrl: './wanted-books.component.scss'
})
export class WantedBooksComponent implements OnInit {
  private wantedBookService = inject(WantedBookService);
  private appSettingsService = inject(AppSettingsService);
  private messageService = inject(MessageService);
  private router = inject(Router);

  books: WantedBook[] = [];
  loading = false;

  // Add dialog
  addDialogVisible = false;
  newBook: Partial<WantedBook> = {};

  // Release dialog
  releaseDialogVisible = false;
  releases: ProwlarrRelease[] = [];
  releasesLoading = false;
  activeBook?: WantedBook;

  // Settings
  settingsVisible = false;
  settings: BookAcquisitionSettings = this.defaultSettings();
  testResult?: ConnectionTestResult;
  testing = false;
  savingSettings = false;

  ngOnInit(): void {
    this.load();
    this.appSettingsService.appSettings$.pipe(
      filter(s => s != null),
      take(1)
    ).subscribe(s => {
      const loaded = (s as any)?.bookAcquisitionSettings;
      if (loaded) {
        this.settings = {...this.defaultSettings(), ...loaded};
      }
    });
  }

  load(): void {
    this.loading = true;
    this.wantedBookService.list().subscribe({
      next: books => {
        this.books = books;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.toast('error', 'Failed to load wanted books');
      }
    });
  }

  goToSearch(): void {
    this.router.navigate(['/add-book']);
  }

  goToQueue(): void {
    this.router.navigate(['/download-queue']);
  }

  openAdd(): void {
    this.newBook = {};
    this.addDialogVisible = true;
  }

  saveNew(): void {
    if (!this.newBook.title?.trim()) {
      this.toast('warn', 'Title is required');
      return;
    }
    this.wantedBookService.add(this.newBook).subscribe({
      next: () => {
        this.addDialogVisible = false;
        this.toast('success', 'Book added to wanted list');
        this.load();
      },
      error: () => this.toast('error', 'Failed to add book')
    });
  }

  remove(book: WantedBook): void {
    if (!confirm(`Remove "${book.title}" from the wanted list?`)) return;
    this.wantedBookService.remove(book.id).subscribe({
      next: () => this.load(),
      error: () => this.toast('error', 'Failed to remove book')
    });
  }

  reset(book: WantedBook): void {
    this.wantedBookService.reset(book.id).subscribe({
      next: () => this.load(),
      error: () => this.toast('error', 'Failed to reset book')
    });
  }

  openReleases(book: WantedBook): void {
    this.activeBook = book;
    this.releases = [];
    this.releaseDialogVisible = true;
    this.releasesLoading = true;
    this.wantedBookService.search(book.id).subscribe({
      next: releases => {
        this.releases = releases;
        this.releasesLoading = false;
      },
      error: err => {
        this.releasesLoading = false;
        this.toast('error', err?.error?.message || 'Search failed — check acquisition settings');
      }
    });
  }

  grab(release: ProwlarrRelease): void {
    if (!this.activeBook) return;
    this.wantedBookService.grab(this.activeBook.id, release).subscribe({
      next: () => {
        this.releaseDialogVisible = false;
        this.toast('success', `Sent to ${release.protocol === 'usenet' ? 'SABnzbd' : 'qBittorrent'}`);
        this.load();
      },
      error: err => this.toast('error', err?.error?.message || 'Grab failed')
    });
  }

  searchAll(): void {
    this.wantedBookService.searchAll().subscribe({
      next: () => this.toast('success', 'Automatic search started in the background'),
      error: () => this.toast('error', 'Failed to start automatic search')
    });
  }

  testConnections(): void {
    this.testing = true;
    this.testResult = undefined;
    this.wantedBookService.testConnections(this.settings).subscribe({
      next: result => {
        this.testResult = result;
        this.testing = false;
      },
      error: () => {
        this.testing = false;
        this.toast('error', 'Connection test failed');
      }
    });
  }

  saveSettings(): void {
    this.savingSettings = true;
    this.appSettingsService.saveSettings([
      {key: 'BOOK_ACQUISITION_SETTINGS', newValue: this.settings}
    ]).subscribe({
      next: () => {
        this.savingSettings = false;
        this.toast('success', 'Acquisition settings saved');
      },
      error: () => {
        this.savingSettings = false;
        this.toast('error', 'Failed to save settings');
      }
    });
  }

  statusSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'IMPORTED': return 'success';
      case 'GRABBED': return 'info';
      case 'FAILED': return 'danger';
      default: return 'warn';
    }
  }

  formatSize(bytes?: number): string {
    if (!bytes) return '—';
    const mb = bytes / (1024 * 1024);
    return mb >= 1024 ? (mb / 1024).toFixed(1) + ' GB' : mb.toFixed(1) + ' MB';
  }

  private defaultSettings(): BookAcquisitionSettings {
    return {
      enabled: false,
      searchCategories: '7000,7020',
      sabnzbdCategory: 'books',
      qbittorrentCategory: 'books',
      autoGrab: true,
      autoGrabMinScore: 60,
      formatPriority: 'epub,azw3,mobi,pdf',
      preferUsenet: true,
      maxSizeMb: 200
    };
  }

  private toast(severity: string, detail: string): void {
    this.messageService.add({severity, summary: severity === 'error' ? 'Error' : 'Wanted Books', detail, life: 4000});
  }
}
