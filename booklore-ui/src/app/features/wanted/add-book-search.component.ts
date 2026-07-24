import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router} from '@angular/router';
import {Button} from 'primeng/button';
import {InputText} from 'primeng/inputtext';
import {ProgressSpinner} from 'primeng/progressspinner';
import {Tag} from 'primeng/tag';
import {Tooltip} from 'primeng/tooltip';
import {MessageService} from 'primeng/api';
import {Subject, debounceTime, distinctUntilChanged, switchMap, takeUntil, catchError, of} from 'rxjs';
import {WantedBookService} from './wanted-book.service';
import {BookLookupResult} from './wanted-book.model';

@Component({
  selector: 'app-add-book-search',
  standalone: true,
  imports: [CommonModule, FormsModule, Button, InputText, ProgressSpinner, Tag, Tooltip],
  templateUrl: './add-book-search.component.html',
  styleUrl: './add-book-search.component.scss'
})
export class AddBookSearchComponent implements OnInit, OnDestroy {
  private wantedBookService = inject(WantedBookService);
  private messageService = inject(MessageService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  query = '';
  results: BookLookupResult[] = [];
  searching = false;
  searched = false;
  addingKeys = new Set<string>();

  private queryChanged = new Subject<string>();
  private destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.queryChanged.pipe(
      debounceTime(450),
      distinctUntilChanged(),
      switchMap(query => {
        if (!query || query.trim().length < 2) {
          this.searching = false;
          this.results = [];
          this.searched = false;
          return of([] as BookLookupResult[]);
        }
        this.searching = true;
        this.searched = true;
        return this.wantedBookService.lookup(query).pipe(
          catchError(() => {
            this.messageService.add({
              severity: 'error', summary: 'Search failed',
              detail: 'Could not reach metadata providers', life: 4000
            });
            return of([] as BookLookupResult[]);
          })
        );
      }),
      takeUntil(this.destroy$)
    ).subscribe(results => {
      this.results = results;
      this.searching = false;
    });

    // Support ?q= so the topbar can hand off a search term
    const initial = this.route.snapshot.queryParamMap.get('q');
    if (initial) {
      this.query = initial;
      this.onQueryChange(initial);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onQueryChange(value: string): void {
    this.queryChanged.next(value);
  }

  key(result: BookLookupResult): string {
    return (result.isbn13 || '') + '|' + result.title;
  }

  add(result: BookLookupResult): void {
    const resultKey = this.key(result);
    this.addingKeys.add(resultKey);
    this.wantedBookService.add({
      title: result.title,
      author: result.authors?.length ? result.authors[0] : undefined,
      isbn13: result.isbn13
    }).subscribe({
      next: () => {
        this.addingKeys.delete(resultKey);
        result.alreadyWanted = true;
        this.messageService.add({
          severity: 'success', summary: 'Added',
          detail: `"${result.title}" added to the wanted list`, life: 3500
        });
      },
      error: () => {
        this.addingKeys.delete(resultKey);
        this.messageService.add({
          severity: 'error', summary: 'Error',
          detail: 'Failed to add book to wanted list', life: 4000
        });
      }
    });
  }

  isAdding(result: BookLookupResult): boolean {
    return this.addingKeys.has(this.key(result));
  }

  openBook(result: BookLookupResult): void {
    if (result.existingBookId) {
      this.router.navigate(['/book', result.existingBookId]);
    }
  }

  goToWanted(): void {
    this.router.navigate(['/wanted-books']);
  }

  authorLine(result: BookLookupResult): string {
    if (!result.authors?.length) return 'Unknown author';
    return result.authors.slice(0, 2).join(', ');
  }
}
