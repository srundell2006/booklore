import {Component, ElementRef, Input, ViewChild, inject} from '@angular/core';
import {BookCardComponent} from '../../../book/components/book-browser/book-card/book-card.component';
import {InfiniteScrollDirective} from 'ngx-infinite-scroll';
import {NgClass} from '@angular/common';

import {ProgressSpinnerModule} from 'primeng/progressspinner';
import {Book} from '../../../book/model/book.model';
import {ScrollerType} from '../../models/dashboard-config.model';
import { BookCardOverlayPreferenceService } from '../../../book/components/book-browser/book-card-overlay-preference.service';
import {TranslocoDirective, TranslocoPipe} from '@jsverse/transloco';
import {Skeleton} from 'primeng/skeleton';
import {DashboardRowState} from '../../services/dashboard-row.service';

const SKELETON_CARDS = Array.from({length: 8}, (_, i) => i);

@Component({
  selector: 'app-dashboard-scroller',
  templateUrl: './dashboard-scroller.component.html',
  styleUrls: ['./dashboard-scroller.component.scss'],
  imports: [
    InfiniteScrollDirective,
    BookCardComponent,
    ProgressSpinnerModule,
    NgClass,
    TranslocoDirective,
    TranslocoPipe,
    Skeleton
  ],
  standalone: true
})
export class DashboardScrollerComponent {

  @Input() bookListType: ScrollerType | null = null;
  @Input() title!: string;
  @Input() rowState: DashboardRowState | null = null;
  @Input() isMagicShelf: boolean = false;
  @Input() useSquareCovers: boolean = false;

  @ViewChild('scrollContainer') scrollContainer!: ElementRef;
  openMenuBookId: number | null = null;

  public bookCardOverlayPreferenceService = inject(BookCardOverlayPreferenceService);

  readonly skeletonCards = SKELETON_CARDS;

  get books(): Book[] {
    return this.rowState?.books ?? [];
  }

  /** Nothing to show yet: placeholders until the first answer. */
  get isLoading(): boolean {
    return !this.rowState || (this.rowState.status === 'loading' && this.books.length === 0);
  }

  get forceEbookMode(): boolean {
    return this.bookListType === ScrollerType.LAST_READ;
  }

  handleMenuToggle(bookId: number, isOpen: boolean) {
    this.openMenuBookId = isOpen ? bookId : null;
  }
}
