import {Component, inject, OnInit} from '@angular/core';
import {AppMenuitemComponent} from './app.menuitem.component';
import {AsyncPipe} from '@angular/common';
import {MenuModule} from 'primeng/menu';
import {LibraryService} from '../../../../features/book/service/library.service';
import {LibraryHealthService} from '../../../../features/book/service/library-health.service';
import {combineLatest, Observable, of} from 'rxjs';
import {filter, map, shareReplay} from 'rxjs/operators';
import {ShelfService} from '../../../../features/book/service/shelf.service';
import {BookService} from '../../../../features/book/service/book.service';
import {LibraryShelfMenuService} from '../../../../features/book/service/library-shelf-menu.service';
import {AppVersion, VersionService} from '../../../service/version.service';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {UserService} from '../../../../features/settings/user-management/user.service';
import {MagicShelfCountView, MagicShelfService, MagicShelfState} from '../../../../features/magic-shelf/service/magic-shelf.service';
import {SeriesDataService} from '../../../../features/series-browser/service/series-data.service';
import {AuthorService} from '../../../../features/author-browser/service/author.service';
import {MenuItem} from 'primeng/api';
import {DialogLauncherService} from '../../../services/dialog-launcher.service';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {Slider} from 'primeng/slider';
import {FormsModule} from '@angular/forms';
import {Popover} from 'primeng/popover';
import {LocalStorageService} from '../../../service/local-storage.service';
import {IconSelection} from '../../../service/icon-picker.service';
import {entityIcon} from '../../../util/entity-icon.util';
import {CatalogCountsService} from '../../../../features/book/service/catalog-counts.service';

@Component({
  selector: 'app-menu',
  standalone: true,
  imports: [AppMenuitemComponent, MenuModule, AsyncPipe, TranslocoDirective, Slider, FormsModule, Popover],
  templateUrl: './app.menu.component.html',
  styleUrl: './app.menu.component.scss',
})
export class AppMenuComponent implements OnInit {
  libraryMenu$: Observable<MenuItem[]> | undefined;
  shelfMenu$: Observable<MenuItem[]> | undefined;
  homeMenu$: Observable<MenuItem[]> | undefined;
  magicShelfMenu$: Observable<MenuItem[]> | undefined;

  versionInfo: AppVersion | null = null;
  dynamicDialogRef: DynamicDialogRef | undefined | null;

  private libraryService = inject(LibraryService);
  private libraryHealthService = inject(LibraryHealthService);
  private shelfService = inject(ShelfService);
  private bookService = inject(BookService);
  private versionService = inject(VersionService);
  private libraryShelfMenuService = inject(LibraryShelfMenuService);
  private dialogLauncherService = inject(DialogLauncherService);
  private userService = inject(UserService);
  private magicShelfService = inject(MagicShelfService);
  private seriesDataService = inject(SeriesDataService);
  private authorService = inject(AuthorService);
  private catalogCountsService = inject(CatalogCountsService);
  private t = inject(TranslocoService);
  private localStorageService = inject(LocalStorageService);

  librarySortField: 'name' | 'id' = 'name';
  librarySortOrder: 'asc' | 'desc' = 'desc';
  shelfSortField: 'name' | 'id' = 'name';
  shelfSortOrder: 'asc' | 'desc' = 'asc';
  magicShelfSortField: 'name' | 'id' = 'name';
  magicShelfSortOrder: 'asc' | 'desc' = 'asc';
  sidebarWidth = 225;


  ngOnInit(): void {
    this.sidebarWidth = this.localStorageService.get<number>('sidebarWidth') ?? 225;

    this.versionService.getVersion().subscribe((data) => {
      this.versionInfo = data;
    });

    this.authorService.getAuthorCount().subscribe();

    this.userService.userState$.pipe(
      filter(userState => !!userState?.user && userState.loaded))
      .subscribe(userState => {
        if (userState.user?.userSettings.sidebarLibrarySorting) {
          this.librarySortField = this.validateSortField(userState.user.userSettings.sidebarLibrarySorting.field);
          this.librarySortOrder = this.validateSortOrder(userState.user.userSettings.sidebarLibrarySorting.order);
        }
        if (userState.user?.userSettings.sidebarShelfSorting) {
          this.shelfSortField = this.validateSortField(userState.user.userSettings.sidebarShelfSorting.field);
          this.shelfSortOrder = this.validateSortOrder(userState.user.userSettings.sidebarShelfSorting.order);
        }
        if (userState.user?.userSettings.sidebarMagicShelfSorting) {
          this.magicShelfSortField = this.validateSortField(userState.user.userSettings.sidebarMagicShelfSorting.field);
          this.magicShelfSortOrder = this.validateSortOrder(userState.user.userSettings.sidebarMagicShelfSorting.order);
        }
        this.initMenus();
      });

    // The counts come from the server, so the menu does not download the book list (except in legacy mode)
    this.homeMenu$ = this.t.langChanges$.pipe(
      map(() => [
        {
          label: this.t.translate('layout.menu.home'),
          items: [
            {
              label: this.t.translate('layout.menu.dashboard'),
              icon: 'pi pi-fw pi-home',
              routerLink: ['/dashboard'],
            },
            {
              label: this.t.translate('layout.menu.allBooks'),
              type: 'All Books',
              icon: 'pi pi-fw pi-book',
              routerLink: ['/all-books'],
              bookCount$: this.catalogCountsService.view$(
                counts => counts.allBooks,
                () => this.bookService.bookState$.pipe(map(bookState => bookState.books ? bookState.books.length : 0))
              ),
            },
            {
              label: this.t.translate('layout.menu.series'),
              type: 'Series',
              icon: 'pi pi-fw pi-objects-column',
              routerLink: ['/series'],
              bookCount$: this.catalogCountsService.view$(
                counts => counts.series,
                () => this.seriesDataService.allSeries$.pipe(map(series => series.length))
              ),
            },
            {
              label: this.t.translate('layout.menu.authors'),
              type: 'Authors',
              icon: 'pi pi-fw pi-users',
              routerLink: ['/authors'],
              bookCount$: this.authorService.authorCount$.pipe(map(count => count ?? 0)),
            },
            {
              label: this.t.translate('layout.menu.notebook'),
              icon: 'pi pi-fw pi-pencil',
              routerLink: ['/notebook'],
            }
          ],
        },
      ])
    );
  }

  onSidebarWidthChange(): void {
    document.documentElement.style.setProperty('--sidebar-width', this.sidebarWidth + 'px');
  }

  saveSidebarWidth(): void {
    this.localStorageService.set('sidebarWidth', this.sidebarWidth);
  }

  private initMenus(): void {
    this.libraryMenu$ = combineLatest([this.libraryService.libraryState$, this.t.langChanges$]).pipe(
      map(([state]) => {
        const libraries = state.libraries ?? [];
        const sortedLibraries = this.sortArray(libraries, this.librarySortField, this.librarySortOrder);
        return [
          {
            label: this.t.translate('layout.menu.libraries'),
            type: 'library',
            hasDropDown: true,
            hasCreate: true,
            items: sortedLibraries.map((library) => ({
              menu: this.libraryShelfMenuService.initializeLibraryMenuItems(library),
              label: library.name,
              type: 'Library',
              ...this.menuIcon(entityIcon(library.icon, library.iconType, 'library')),
              routerLink: [`/library/${library.id}/books`],
              bookCount$: this.libraryService.getBookCount(library.id ?? 0),
              unhealthy$: this.libraryHealthService.isUnhealthy$(library.id ?? 0),
            })),
          },
        ];
      })
    );

    this.magicShelfMenu$ = combineLatest([this.magicShelfService.shelvesState$, this.t.langChanges$]).pipe(
      map(([state]: [MagicShelfState, string]) => {
        const shelves = state.shelves ?? [];
        const sortedShelves = this.sortArray(shelves, this.magicShelfSortField, this.magicShelfSortOrder);
        return [
          {
            label: this.t.translate('layout.menu.magicShelves'),
            type: 'magicShelf',
            hasDropDown: true,
            hasCreate: true,
            items: sortedShelves.map((shelf) => {
              const countView$ = this.magicShelfService.getBookCountView(shelf.id ?? 0).pipe(
                shareReplay({bufferSize: 1, refCount: true})
              );
              return {
                label: shelf.name,
                type: 'magicShelfItem',
                ...this.menuIcon(entityIcon(shelf.icon, shelf.iconType, 'magicShelf')),
                menu: this.libraryShelfMenuService.initializeMagicShelfMenuItems(shelf),
                routerLink: [`/magic-shelf/${shelf.id}/books`],
                bookCount$: countView$.pipe(map(view => view.count)),
                countHint$: countView$.pipe(map(view => this.countHint(view))),
              };
            }),
          },
        ];
      })
    );

    this.shelfMenu$ = combineLatest([this.shelfService.shelfState$, this.t.langChanges$]).pipe(
      map(([state]) => {
        const shelves = state.shelves ?? [];
        const sortedShelves = this.sortArray(shelves, this.shelfSortField, this.shelfSortOrder);

        const koboShelfIndex = sortedShelves.findIndex(shelf => shelf.name === 'Kobo');
        let koboShelf = null;
        if (koboShelfIndex !== -1) {
          koboShelf = sortedShelves.splice(koboShelfIndex, 1)[0];
        }

        const shelfItems = sortedShelves.map((shelf) => ({
          menu: this.libraryShelfMenuService.initializeShelfMenuItems(shelf),
          label: shelf.name,
          type: 'Shelf',
          ...this.menuIcon(entityIcon(shelf.icon, shelf.iconType, 'shelf')),
          routerLink: [`/shelf/${shelf.id}/books`],
          bookCount$: this.shelfService.getBookCount(shelf.id ?? 0),
        }));

        const unshelvedItem = {
          label: this.t.translate('layout.menu.unshelved'),
          type: 'Shelf',
          icon: 'pi pi-inbox',
          iconType: 'PRIME_NG' as 'PRIME_NG' | 'CUSTOM_SVG',
          routerLink: ['/unshelved-books'],
          bookCount$: this.shelfService.getUnshelvedBookCount?.() ?? of(0),
        };

        const items: MenuItem[] = [unshelvedItem];
        if (koboShelf) {
          items.push({
            label: koboShelf.name,
            type: 'Shelf',
            ...this.menuIcon(entityIcon(koboShelf.icon, koboShelf.iconType, 'shelf')),
            routerLink: [`/shelf/${koboShelf.id}/books`],
            bookCount$: this.shelfService.getBookCount(koboShelf.id ?? 0),
          });
        }
        items.push(...shelfItems);

        return [
          {
            type: 'shelf',
            label: this.t.translate('layout.menu.shelves'),
            hasDropDown: true,
            hasCreate: true,
            items,
          },
        ];
      })
    );
  }

  /** Why a magic shelf shows no count: the server could not count it, and the book list is not loaded. */
  private countHint(view: MagicShelfCountView): string | null {
    const unavailable = view.unavailable;
    if (!unavailable) return null;
    if (unavailable.status === 'UNSUPPORTED') {
      const reasons = (unavailable.reasons ?? []).join(', ');
      return this.t.translate('layout.menu.countUnsupported', {reasons});
    }
    return this.t.translate('layout.menu.countError');
  }

  openChangelogDialog() {
    this.dialogLauncherService.openVersionChangelogDialog();
  }

  getVersionUrl(version: string | undefined): string {
    if (!version) return '#';
    return version.startsWith('v')
      ? `https://github.com/booklore-app/booklore/releases/tag/${version}`
      : `https://github.com/booklore-app/booklore/commit/${version}`;
  }

  isSemanticVersion(version: string | undefined): boolean {
    if (!version) return false;
    const semanticVersionPattern = /^v\d+\.\d+\.\d+$/;
    return semanticVersionPattern.test(version);
  }

  private sortArray<T>(array: T[], field: 'name' | 'id', order: 'asc' | 'desc'): T[] {
    return [...array].sort((a, b) => {
      const aVal = (a as Record<string, unknown>)[field] ?? '';
      const bVal = (b as Record<string, unknown>)[field] ?? '';
      let comparison = 0;

      if (typeof aVal === 'string' && typeof bVal === 'string') {
        comparison = aVal.localeCompare(bVal);
      } else if (typeof aVal === 'number' && typeof bVal === 'number') {
        comparison = aVal - bVal;
      }

      return order === 'asc' ? comparison : -comparison;
    });
  }

  private menuIcon(icon: IconSelection): { icon: string; iconType: IconSelection['type'] } {
    return {icon: icon.value, iconType: icon.type};
  }

  private validateSortField(field: string): 'name' | 'id' {
    return field === 'id' ? 'id' : 'name';
  }

  private validateSortOrder(order: string): 'asc' | 'desc' {
    return order === 'desc' ? 'desc' : 'asc';
  }
}
