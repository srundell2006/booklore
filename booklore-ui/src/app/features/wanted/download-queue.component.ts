import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {TableModule} from 'primeng/table';
import {Button} from 'primeng/button';
import {ProgressBar} from 'primeng/progressbar';
import {Tag} from 'primeng/tag';
import {Tooltip} from 'primeng/tooltip';
import {Checkbox} from 'primeng/checkbox';
import {ConfirmationService, MessageService} from 'primeng/api';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {Subject, interval, takeUntil} from 'rxjs';
import {WantedBookService} from './wanted-book.service';
import {DownloadQueueItem} from './wanted-book.model';

@Component({
  selector: 'app-download-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, Button, ProgressBar, Tag, Tooltip, Checkbox, ConfirmDialog],
  providers: [ConfirmationService],
  templateUrl: './download-queue.component.html',
  styleUrl: './download-queue.component.scss'
})
export class DownloadQueueComponent implements OnInit, OnDestroy {
  private wantedBookService = inject(WantedBookService);
  private messageService = inject(MessageService);
  private confirmationService = inject(ConfirmationService);

  items: DownloadQueueItem[] = [];
  loading = false;
  autoRefresh = true;
  lastUpdated?: Date;

  private destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.load();
    interval(5000).pipe(takeUntil(this.destroy$)).subscribe(() => {
      if (this.autoRefresh) {
        this.load(true);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(silent = false): void {
    if (!silent) this.loading = true;
    this.wantedBookService.getQueue().subscribe({
      next: items => {
        this.items = items;
        this.lastUpdated = new Date();
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        if (!silent) {
          this.messageService.add({
            severity: 'error', summary: 'Error',
            detail: 'Failed to load download queue', life: 4000
          });
        }
      }
    });
  }

  remove(item: DownloadQueueItem, deleteFiles: boolean): void {
    const action = deleteFiles ? 'Remove and delete downloaded files for' : 'Remove';
    this.confirmationService.confirm({
      message: `${action} "${item.name}"?`,
      header: 'Confirm removal',
      icon: 'pi pi-exclamation-triangle',
      accept: () => {
        this.wantedBookService.removeFromQueue(item.client, item.id, deleteFiles).subscribe({
          next: () => {
            this.messageService.add({
              severity: 'success', summary: 'Removed',
              detail: `"${item.name}" removed from ${this.clientLabel(item.client)}`, life: 3500
            });
            this.load(true);
          },
          error: () => this.messageService.add({
            severity: 'error', summary: 'Error',
            detail: 'Failed to remove item', life: 4000
          })
        });
      }
    });
  }

  clientLabel(client: string): string {
    return client === 'SABNZBD' ? 'SABnzbd' : 'qBittorrent';
  }

  clientSeverity(client: string): 'info' | 'warn' {
    return client === 'SABNZBD' ? 'info' : 'warn';
  }

  stateSeverity(item: DownloadQueueItem): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    const state = (item.state || '').toLowerCase();
    if (item.completed || state.includes('upload') || state === 'completed') return 'success';
    if (state.includes('stall') || state.includes('error') || state.includes('miss')) return 'danger';
    if (state.includes('pause') || state.includes('queue')) return 'secondary';
    return 'info';
  }

  formatSize(bytes?: number): string {
    if (!bytes || bytes <= 0) return '—';
    const mb = bytes / (1024 * 1024);
    return mb >= 1024 ? (mb / 1024).toFixed(2) + ' GB' : mb.toFixed(1) + ' MB';
  }

  formatSpeed(bytesPerSecond?: number): string {
    if (!bytesPerSecond || bytesPerSecond <= 0) return '—';
    const kb = bytesPerSecond / 1024;
    return kb >= 1024 ? (kb / 1024).toFixed(1) + ' MB/s' : kb.toFixed(0) + ' KB/s';
  }

  formatEta(seconds?: number): string {
    if (!seconds || seconds <= 0) return '—';
    if (seconds < 60) return `${Math.round(seconds)}s`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return `${hours}h ${minutes}m`;
  }

  get totalSpeed(): string {
    const total = this.items.reduce((sum, i) => sum + (i.downloadSpeed || 0), 0);
    return this.formatSpeed(total);
  }
}
