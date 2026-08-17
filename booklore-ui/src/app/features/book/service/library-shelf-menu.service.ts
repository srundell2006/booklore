import {inject, Injectable} from '@angular/core';
import {ConfirmationService, MenuItem, MessageService} from 'primeng/api';
import {Router} from '@angular/router';
import {LibraryService} from './library.service';
import {ShelfService} from './shelf.service';
import {Library} from '../model/library.model';
import {Shelf} from '../model/shelf.model';
import {MetadataRefreshType} from '../../metadata/model/request/metadata-refresh-type.enum';
import {MagicShelf, MagicShelfService} from '../../magic-shelf/service/magic-shelf.service';
import {ComicDetectionRequest, EpubTextIdentifyRequest, FilenameAuthorExtractRequest, IsbnScanRequest, OrganizeLibraryRequest} from '../../settings/task-management/task.service';
import {TaskHelperService} from '../../settings/task-management/task-helper.service';
import {UserService} from "../../../features/settings/user-management/user.service";
import {LoadingService} from '../../../core/services/loading.service';
import {finalize} from 'rxjs';
import {DialogLauncherService} from '../../../shared/services/dialog-launcher.service';
import {BookDialogHelperService} from '../components/book-browser/book-dialog-helper.service';
import {TranslocoService} from '@jsverse/transloco';
import {BookSelectionService} from '../components/book-browser/book-selection.service';
import {BookMetadataService} from './book-metadata.service';

@Injectable({
  providedIn: 'root',
})
export class LibraryShelfMenuService {

  private confirmationService = inject(ConfirmationService);
  private messageService = inject(MessageService);
  private libraryService = inject(LibraryService);
  private shelfService = inject(ShelfService);
  private taskHelperService = inject(TaskHelperService);
  private router = inject(Router);
  private dialogLauncherService = inject(DialogLauncherService);
  private magicShelfService = inject(MagicShelfService);
  private userService = inject(UserService);
  private loadingService = inject(LoadingService);
  private bookDialogHelperService = inject(BookDialogHelperService);
  private readonly t = inject(TranslocoService);
  private bookSelectionService = inject(BookSelectionService);
  private bookMetadataService = inject(BookMetadataService);

  initializeLibraryMenuItems(entity: Library | Shelf | MagicShelf | null): MenuItem[] {
    return [
      {
        label: this.t.translate('book.shelfMenuService.library.optionsLabel'),
        items: [
          {
            label: this.t.translate('book.shelfMenuService.library.addPhysicalBook'),
            icon: 'pi pi-book',
            command: () => {
              this.bookDialogHelperService.openAddPhysicalBookDialog(entity?.id as number);
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.bulkIsbnImport'),
            icon: 'pi pi-barcode',
            command: () => {
              this.bookDialogHelperService.openBulkIsbnImportDialog(entity?.id as number);
            }
          },
          {
            separator: true
          },
          {
            label: this.t.translate('book.shelfMenuService.library.editLibrary'),
            icon: 'pi pi-pen-to-square',
            command: () => {
              this.dialogLauncherService.openLibraryEditDialog((entity?.id as number));
            }
          },
          {
            label: (entity as Library)?.watch
              ? this.t.translate('book.shelfMenuService.library.disableWatcher')
              : this.t.translate('book.shelfMenuService.library.enableWatcher'),
            icon: (entity as Library)?.watch ? 'pi pi-eye-slash' : 'pi pi-eye',
            command: () => {
              const currentWatch = (entity as Library)?.watch ?? false;
              this.libraryService.setWatchStatus(entity?.id as number, !currentWatch).subscribe({
                next: () => {
                  this.messageService.add({
                    severity: 'success',
                    summary: this.t.translate('common.success'),
                    detail: !currentWatch
                      ? this.t.translate('book.shelfMenuService.toast.watcherEnabledDetail')
                      : this.t.translate('book.shelfMenuService.toast.watcherDisabledDetail')
                  });
                },
                error: () => {
                  this.messageService.add({
                    severity: 'error',
                    summary: this.t.translate('book.shelfMenuService.toast.failedSummary'),
                    detail: this.t.translate('book.shelfMenuService.toast.watcherToggleFailedDetail'),
                  });
                }
              });
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.rescanLibrary'),
            icon: 'pi pi-refresh',
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.shelfMenuService.confirm.rescanLibraryMessage', {name: entity?.name}),
                header: this.t.translate('book.shelfMenuService.confirm.header'),
                icon: undefined,
                acceptLabel: this.t.translate('book.shelfMenuService.confirm.rescanLabel'),
                rejectLabel: this.t.translate('common.cancel'),
                acceptIcon: undefined,
                rejectIcon: undefined,
                acceptButtonStyleClass: undefined,
                rejectButtonStyleClass: undefined,
                rejectButtonProps: {
                  label: this.t.translate('common.cancel'),
                  severity: 'secondary',
                },
                acceptButtonProps: {
                  label: this.t.translate('book.shelfMenuService.confirm.rescanLabel'),
                  severity: 'success',
                },
                accept: () => {
                  this.libraryService.refreshLibrary(entity?.id!).subscribe({
                    complete: () => {
                      this.messageService.add({severity: 'info', summary: this.t.translate('common.success'), detail: this.t.translate('book.shelfMenuService.toast.libraryRefreshSuccessDetail')});
                    },
                    error: () => {
                      this.messageService.add({
                        severity: 'error',
                        summary: this.t.translate('book.shelfMenuService.toast.failedSummary'),
                        detail: this.t.translate('book.shelfMenuService.toast.libraryRefreshFailedDetail'),
                      });
                    }
                  });
                }
              });
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.customFetchMetadata'),
            icon: 'pi pi-sync',
            command: () => {
              this.dialogLauncherService.openLibraryMetadataFetchDialog((entity?.id as number));
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.autoFetchMetadata'),
            icon: 'pi pi-bolt',
            command: () => {
              this.taskHelperService.refreshMetadataTask({
                refreshType: MetadataRefreshType.LIBRARY,
                libraryId: entity?.id ?? undefined
              }).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.scanEpubsForIsbn', {default: 'Scan EPUBs for ISBN'}),
            icon: 'pi pi-barcode',
            command: () => {
              this.taskHelperService.scanEpubIsbnTask({
                refreshType: 'LIBRARY',
                libraryId: entity?.id ?? undefined
              } as IsbnScanRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.identifyEpubsFromText', {default: 'Identify EPUBs from Text'}),
            icon: 'pi pi-sparkles',
            command: () => {
              this.taskHelperService.identifyEpubTextTask({
                refreshType: 'LIBRARY',
                libraryId: entity?.id ?? undefined
              } as EpubTextIdentifyRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.extractAuthorFromFilename', {default: 'Extract Author from Filename'}),
            icon: 'pi pi-file-edit',
            command: () => {
              this.taskHelperService.filenameAuthorExtractTask({
                refreshType: 'LIBRARY',
                libraryId: entity?.id ?? undefined
              } as FilenameAuthorExtractRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.detectComics', {default: 'Detect Comics'}),
            icon: 'pi pi-images',
            command: () => {
              this.taskHelperService.detectComicsTask({
                refreshType: 'LIBRARY',
                libraryId: entity?.id ?? undefined
              } as ComicDetectionRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.findDuplicates'),
            icon: 'pi pi-copy',
            command: () => {
              this.bookDialogHelperService.openDuplicateMergerDialog(entity?.id as number);
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.library.organizeLibrary', {default: 'Organize Library'}),
            icon: 'pi pi-sort-alpha-down',
            command: () => {
              this.taskHelperService.organizeLibraryTask({
                libraryId: entity?.id ?? undefined
              } as OrganizeLibraryRequest).subscribe();
            }
          },
          {
            separator: true
          },
          {
            label: this.t.translate('book.shelfMenuService.library.deleteLibrary'),
            icon: 'pi pi-trash',
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.shelfMenuService.confirm.deleteLibraryMessage', {name: entity?.name}),
                header: this.t.translate('book.shelfMenuService.confirm.header'),
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.cancel'),
                rejectButtonProps: {
                  label: this.t.translate('common.cancel'),
                  severity: 'secondary',
                },
                acceptButtonProps: {
                  label: this.t.translate('common.yes'),
                  severity: 'danger',
                },
                accept: () => {
                  const loader = this.loadingService.show(this.t.translate('book.shelfMenuService.loading.deletingLibrary', {name: entity?.name}));

                  this.libraryService.deleteLibrary(entity?.id!)
                    .pipe(finalize(() => this.loadingService.hide(loader)))
                    .subscribe({
                      complete: () => {
                        this.router.navigate(['/']);
                        this.messageService.add({severity: 'info', summary: this.t.translate('common.success'), detail: this.t.translate('book.shelfMenuService.toast.libraryDeletedDetail')});
                      },
                      error: () => {
                        this.messageService.add({
                          severity: 'error',
                          summary: this.t.translate('book.shelfMenuService.toast.failedSummary'),
                          detail: this.t.translate('book.shelfMenuService.toast.libraryDeleteFailedDetail'),
                        });
                      }
                    });
                }
              });
            }
          }
        ]
      }
    ];
  }

  initializeShelfMenuItems(entity: any): MenuItem[] {
    const user = this.userService.getCurrentUser();
    const isOwner = entity?.userId === user?.id;
    const isPublicShelf = entity?.publicShelf ?? false;
    const disableOptions = !isOwner;

    return [
      {
        label: (isPublicShelf ? this.t.translate('book.shelfMenuService.shelf.publicShelfPrefix') : '') + (disableOptions ? this.t.translate('book.shelfMenuService.shelf.readOnly') : this.t.translate('book.shelfMenuService.shelf.optionsLabel')),
        items: [
          {
            label: this.t.translate('book.shelfMenuService.shelf.editShelf'),
            icon: 'pi pi-pen-to-square',
            disabled: disableOptions,
            command: () => {
              this.dialogLauncherService.openShelfEditDialog((entity?.id as number));
            }
          },
          {
            separator: true
          },
          {
            label: this.t.translate('book.shelfMenuService.shelf.deleteShelf'),
            icon: 'pi pi-trash',
            disabled: disableOptions,
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.shelfMenuService.confirm.deleteShelfMessage', {name: entity?.name}),
                header: this.t.translate('book.shelfMenuService.confirm.header'),
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.cancel'),
                acceptButtonProps: {
                  label: this.t.translate('common.yes'),
                  severity: 'danger'
                },
                rejectButtonProps: {
                  label: this.t.translate('common.cancel'),
                  severity: 'secondary'
                },
                accept: () => {
                  this.shelfService.deleteShelf(entity?.id!).subscribe({
                    complete: () => {
                      this.router.navigate(['/']);
                      this.messageService.add({severity: 'info', summary: this.t.translate('common.success'), detail: this.t.translate('book.shelfMenuService.toast.shelfDeletedDetail')});
                    },
                    error: () => {
                      this.messageService.add({
                        severity: 'error',
                        summary: this.t.translate('book.shelfMenuService.toast.failedSummary'),
                        detail: this.t.translate('book.shelfMenuService.toast.shelfDeleteFailedDetail'),
                      });
                    }
                  });
                }
              });
            }
          }
        ]
      }
    ];
  }

  initializeMagicShelfMenuItems(entity: MagicShelf | null): MenuItem[] {
    const isAdmin = this.userService.getCurrentUser()?.permissions.admin ?? false;
    const isPublicShelf = entity?.isPublic ?? false;
    const disableOptions = isPublicShelf && !isAdmin;

    return [
      {
        label: this.t.translate('book.shelfMenuService.magicShelf.optionsLabel'),
        items: [
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.editMagicShelf'),
            icon: 'pi pi-pen-to-square',
            disabled: disableOptions,
            command: () => {
              this.dialogLauncherService.openMagicShelfEditDialog((entity?.id as number));
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.exportJson'),
            icon: 'pi pi-copy',
            command: () => {
              if (entity?.filterJson) {
                navigator.clipboard.writeText(entity.filterJson).then(() => {
                  this.messageService.add({severity: 'success', summary: this.t.translate('common.success'), detail: this.t.translate('book.shelfMenuService.toast.magicShelfJsonCopiedDetail')});
                });
              }
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.scanEpubsForIsbn', {default: 'Scan EPUBs for ISBN'}),
            icon: 'pi pi-barcode',
            command: () => {
              this.taskHelperService.scanEpubIsbnTask({
                refreshType: 'MAGIC_SHELF',
                magicShelfId: entity?.id ?? undefined
              } as IsbnScanRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.identifyEpubsFromText', {default: 'Scan Text for Metadata'}),
            icon: 'pi pi-sparkles',
            command: () => {
              this.taskHelperService.identifyEpubTextTask({
                refreshType: 'MAGIC_SHELF',
                magicShelfId: entity?.id ?? undefined
              } as EpubTextIdentifyRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.extractAuthorFromFilename', {default: 'Extract Author from Filename'}),
            icon: 'pi pi-file-edit',
            command: () => {
              this.taskHelperService.filenameAuthorExtractTask({
                refreshType: 'MAGIC_SHELF',
                magicShelfId: entity?.id ?? undefined
              } as FilenameAuthorExtractRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.detectComics', {default: 'Detect Comics'}),
            icon: 'pi pi-images',
            command: () => {
              this.taskHelperService.detectComicsTask({
                refreshType: 'MAGIC_SHELF',
                magicShelfId: entity?.id ?? undefined
              } as ComicDetectionRequest).subscribe();
            }
          },
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.clearTitleChangedFlag'),
            icon: 'pi pi-flag',
            command: () => {
              const books = this.bookSelectionService.getCurrentBooks();
              const bookIds = books.map(b => b.id);
              if (bookIds.length === 0) {
                this.messageService.add({severity: 'warn', summary: this.t.translate('common.warning'), detail: this.t.translate('book.shelfMenuService.toast.noBooksToClearFlag'), life: 2000});
                return;
              }
              this.confirmationService.confirm({
                message: this.t.translate('book.shelfMenuService.confirm.clearTitleChangedFlagMessage', {count: bookIds.length}),
                header: this.t.translate('book.shelfMenuService.confirm.clearTitleChangedFlagHeader'),
                icon: 'pi pi-exclamation-triangle',
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.no'),
                accept: () => {
                  const loader = this.loadingService.show(this.t.translate('book.menuService.loading.clearingTitleChangedFlag'));
                  this.bookMetadataService.clearTitleChangedFlag(bookIds)
                    .pipe(finalize(() => this.loadingService.hide(loader)))
                    .subscribe({
                      next: () => {
                        this.messageService.add({
                          severity: 'success',
                          summary: this.t.translate('common.success'),
                          detail: this.t.translate('book.menuService.toast.titleChangedFlagClearedDetail', {count: bookIds.length}),
                          life: 2000
                        });
                      },
                      error: () => {
                        this.messageService.add({
                          severity: 'error',
                          summary: this.t.translate('book.menuService.toast.failedSummary'),
                          detail: this.t.translate('book.menuService.toast.clearTitleChangedFlagFailedDetail'),
                          life: 3000
                        });
                      }
                    });
                }
              });
            }
          },
          {
            separator: true
          },
          {
            label: this.t.translate('book.shelfMenuService.magicShelf.deleteMagicShelf'),
            icon: 'pi pi-trash',
            disabled: disableOptions,
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.shelfMenuService.confirm.deleteMagicShelfMessage', {name: entity?.name}),
                header: this.t.translate('book.shelfMenuService.confirm.header'),
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.cancel'),
                acceptButtonProps: {
                  label: this.t.translate('common.yes'),
                  severity: 'danger'
                },
                rejectButtonProps: {
                  label: this.t.translate('common.cancel'),
                  severity: 'secondary'
                },
                accept: () => {
                  this.magicShelfService.deleteShelf(entity?.id!).subscribe({
                    complete: () => {
                      this.router.navigate(['/']);
                      this.messageService.add({severity: 'info', summary: this.t.translate('common.success'), detail: this.t.translate('book.shelfMenuService.toast.magicShelfDeletedDetail')});
                    },
                    error: () => {
                      this.messageService.add({
                        severity: 'error',
                        summary: this.t.translate('book.shelfMenuService.toast.failedSummary'),
                        detail: this.t.translate('book.shelfMenuService.toast.magicShelfDeleteFailedDetail'),
                      });
                    }
                  });
                }
              });
            }
          }
        ]
      }
    ];
  }
}
