import {beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {firstValueFrom, NEVER, of} from 'rxjs';
import {MainDashboardComponent} from './main-dashboard.component';
import {BookService} from '../../../book/service/book.service';
import {LibraryService} from '../../../book/service/library.service';
import {DialogLauncherService} from '../../../../shared/services/dialog-launcher.service';
import {UserService} from '../../../settings/user-management/user.service';
import {DashboardConfigService} from '../../services/dashboard-config.service';
import {MagicShelfService} from '../../../magic-shelf/service/magic-shelf.service';
import {BookRuleEvaluatorService} from '../../../magic-shelf/service/book-rule-evaluator.service';
import {PageTitleService} from '../../../../shared/service/page-title.service';
import {ScrollerConfig, ScrollerType} from '../../models/dashboard-config.model';
import {Book} from '../../../book/model/book.model';

function book(id: number, title: string): Book {
  return {id, libraryId: 1, libraryName: 'Books', metadata: {bookId: id, title}} as Book;
}

describe('MainDashboardComponent magic shelf row', () => {
  let component: MainDashboardComponent;

  // In shelf order the first two are the last two by title
  const books = [book(1, 'Zebra'), book(2, 'Yak'), book(3, 'Apple'), book(4, 'Banana'), book(5, 'Cherry')];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TranslocoTestingModule.forRoot({langs: {}})],
      providers: [
        {provide: BookService, useValue: {bookState$: of({books, loaded: true, error: null})}},
        {provide: LibraryService, useValue: {libraryState$: of({libraries: [], loaded: true, error: null})}},
        {provide: DialogLauncherService, useValue: {}},
        {provide: UserService, useValue: {}},
        {provide: DashboardConfigService, useValue: {config$: NEVER}},
        {provide: MagicShelfService, useValue: {getShelf: vi.fn(() => of({id: 7, name: 'All', filterJson: '{}'})), shelvesState$: NEVER}},
        {provide: BookRuleEvaluatorService, useValue: {evaluateGroup: vi.fn(() => true)}},
        {provide: PageTitleService, useValue: {setPageTitle: vi.fn()}},
      ]
    });
    component = TestBed.runInInjectionContext(() => new MainDashboardComponent());
  });

  function scroller(overrides: Partial<ScrollerConfig>): ScrollerConfig {
    return {id: 'magic', type: ScrollerType.MAGIC_SHELF, title: 'Magic', enabled: true, order: 1, maxItems: 2, magicShelfId: 7, ...overrides};
  }

  it('should sort the whole shelf before taking the first books', async () => {
    const row = await firstValueFrom(component.getBooksForScroller(scroller({sortField: 'title', sortDirection: 'asc'})));

    expect(row.map(b => b.metadata?.title)).toEqual(['Apple', 'Banana']);
  });

  it('should sort descending over the whole shelf', async () => {
    const row = await firstValueFrom(component.getBooksForScroller(scroller({sortField: 'title', sortDirection: 'desc'})));

    expect(row.map(b => b.metadata?.title)).toEqual(['Zebra', 'Yak']);
  });

  it('should keep the shelf order when no sort is set', async () => {
    const row = await firstValueFrom(component.getBooksForScroller(scroller({})));

    expect(row.map(b => b.id)).toEqual([1, 2]);
  });
});
