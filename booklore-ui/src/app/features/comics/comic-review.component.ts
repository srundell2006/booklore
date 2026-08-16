import {Component, inject, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {TableModule} from 'primeng/table';
import {Button} from 'primeng/button';
import {Tag} from 'primeng/tag';
import {Tooltip} from 'primeng/tooltip';
import {ConfirmationService, MessageService} from 'primeng/api';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {ComicDetectionService} from './comic-detection.service';
import {ComicCandidate} from './comic-candidate.model';

@Component({
  selector: 'app-comic-review',
  standalone: true,
  imports: [CommonModule, FormsModule, TableModule, Button, Tag, Tooltip, ConfirmDialog],
  providers: [ConfirmationService],
  templateUrl: './comic-review.component.html',
  styleUrl: './comic-review.component.scss'
})
export class ComicReviewComponent implements OnInit {
  private comicService = inject(ComicDetectionService);
  private messageService = inject(MessageService);
  private confirmationService = inject(ConfirmationService);

  candidates: ComicCandidate[] = [];
  selected: ComicCandidate[] = [];
  loading = false;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.comicService.getPending().subscribe({
      next: candidates => {
        this.candidates = candidates;
        this.selected = [];
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: 'Could not load the comic review queue.'
        });
      }
    });
  }

  accept(candidate: ComicCandidate): void {
    this.comicService.accept(candidate.id).subscribe({
      next: () => {
        this.removeLocal(candidate.id);
        this.messageService.add({
          severity: 'success',
          summary: 'Marked as comic',
          detail: candidate.title
        });
      },
      error: () => this.fail()
    });
  }

  reject(candidate: ComicCandidate): void {
    this.comicService.reject(candidate.id).subscribe({
      next: () => {
        this.removeLocal(candidate.id);
        this.messageService.add({
          severity: 'info',
          summary: 'Dismissed',
          detail: candidate.title
        });
      },
      error: () => this.fail()
    });
  }

  acceptSelected(): void {
    const ids = this.selected.map(c => c.id);
    if (!ids.length) return;
    this.confirmationService.confirm({
      message: `Mark ${ids.length} book(s) as comics?`,
      header: 'Confirm',
      icon: 'pi pi-exclamation-triangle',
      accept: () => this.comicService.acceptAll(ids).subscribe({
        next: res => {
          this.messageService.add({
            severity: 'success',
            summary: 'Marked as comics',
            detail: `${res.accepted} book(s) updated.`
          });
          this.load();
        },
        error: () => this.fail()
      })
    });
  }

  rejectSelected(): void {
    const ids = this.selected.map(c => c.id);
    if (!ids.length) return;
    this.comicService.rejectAll(ids).subscribe({
      next: res => {
        this.messageService.add({
          severity: 'info',
          summary: 'Dismissed',
          detail: `${res.rejected} candidate(s) dismissed.`
        });
        this.load();
      },
      error: () => this.fail()
    });
  }

  scoreSeverity(score: number): 'success' | 'warn' | 'danger' {
    if (score >= 70) return 'success';
    if (score >= 55) return 'warn';
    return 'danger';
  }

  private removeLocal(id: number): void {
    this.candidates = this.candidates.filter(c => c.id !== id);
    this.selected = this.selected.filter(c => c.id !== id);
  }

  private fail(): void {
    this.messageService.add({
      severity: 'error',
      summary: 'Error',
      detail: 'The action could not be completed.'
    });
  }
}
