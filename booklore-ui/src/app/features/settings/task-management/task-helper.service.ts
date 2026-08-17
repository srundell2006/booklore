import {inject, Injectable} from '@angular/core';
import {MessageService} from 'primeng/api';
import {MetadataRefreshRequest} from '../../metadata/model/request/metadata-refresh-request.model';
import {catchError, map} from 'rxjs/operators';
import {of} from 'rxjs';
import {AudiobookVerificationRequest, ComicDetectionRequest, EpubTextIdentifyRequest, FilenameAuthorExtractRequest, IsbnScanRequest, OrganizeLibraryRequest, TaskCreateRequest, TaskService, TaskType, CopyrightIsbnScanRequest} from './task.service';
import {TranslocoService} from '@jsverse/transloco';

@Injectable({
  providedIn: 'root'
})
export class TaskHelperService {
  private taskService = inject(TaskService);
  private messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);

  refreshMetadataTask(options: MetadataRefreshRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.REFRESH_METADATA_MANUAL,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('settingsTasks.toast.metadataScheduled'),
          detail: this.t.translate('settingsTasks.toast.metadataScheduledDetail')
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.metadataAlreadyRunningDetail')
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.metadataFailedDetail')
          });
        }
        return of({success: false});
      })
    );
  }

  scanEpubIsbnTask(options: IsbnScanRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.EPUB_ISBN_SCAN,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsTasks.toast.isbnScanScheduled', {default: 'ISBN scan started. Progress will appear in Task Management.'})
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.isbnScanAlreadyRunning', {default: 'An ISBN scan is already running.'})
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.isbnScanFailed', {default: 'Failed to start ISBN scan.'})
          });
        }
        return of({success: false});
      })
    );
  }

  identifyEpubTextTask(options: EpubTextIdentifyRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.EPUB_TEXT_IDENTIFY,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsTasks.toast.epubTextIdentifyScheduled', {default: 'EPUB text identification started. Progress will appear in Task Management.'})
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.epubTextIdentifyAlreadyRunning', {default: 'EPUB text identification is already running.'})
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.epubTextIdentifyFailed', {default: 'Failed to start EPUB text identification.'})
          });
        }
        return of({success: false});
      })
    );
  }

  detectComicsTask(options: ComicDetectionRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.COMIC_DETECTION,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsTasks.toast.comicDetectionScheduled', {default: 'Comic detection started. Confident matches are flagged automatically; borderline ones land in the review queue.'})
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.comicDetectionAlreadyRunning', {default: 'Comic detection is already running.'})
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.comicDetectionFailed', {default: 'Failed to start comic detection.'})
          });
        }
        return of({success: false});
      })
    );
  }

  scanCopyrightIsbnTask(options: CopyrightIsbnScanRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.COPYRIGHT_ISBN_SCAN,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsTasks.toast.copyrightIsbnScanScheduled', {default: 'Copyright-page ISBN scan started. Progress will appear in Task Management.'})
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.copyrightIsbnScanAlreadyRunning', {default: 'A copyright-page ISBN scan is already running.'})
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.copyrightIsbnScanFailed', {default: 'Failed to start the copyright-page ISBN scan.'})
          });
        }
        return of({success: false});
      })
    );
  }

  organizeLibraryTask(options: OrganizeLibraryRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.ORGANIZE_LIBRARY,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsTasks.toast.organizeLibraryScheduled', {default: 'Library organization started. Progress will appear in Task Management.'})
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.organizeLibraryAlreadyRunning', {default: 'Library organization is already running.'})
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.organizeLibraryFailed', {default: 'Failed to start library organization.'})
          });
        }
        return of({success: false});
      })
    );
  }

  verifyAudiobookTask(options: AudiobookVerificationRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.AUDIOBOOK_VERIFICATION,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: 'Audiobook verification started. Progress will appear in Task Management.'
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: 'Audiobook verification is already running.'
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: 'Failed to start audiobook verification.'
          });
        }
        return of({success: false});
      })
    );
  }

  filenameAuthorExtractTask(options: FilenameAuthorExtractRequest) {
    const request: TaskCreateRequest = {
      taskType: TaskType.FILENAME_AUTHOR_EXTRACT,
      triggeredByCron: false,
      options
    };
    return this.taskService.startTask(request).pipe(
      map(() => {
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsTasks.toast.filenameAuthorExtractScheduled', {default: 'Filename author extraction started. Progress will appear in Task Management.'})
        });
        return {success: true};
      }),
      catchError((e) => {
        if (e.status === 409) {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.alreadyRunning'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.filenameAuthorExtractAlreadyRunning', {default: 'Filename author extraction is already running.'})
          });
        } else {
          this.messageService.add({
            severity: 'error',
            summary: this.t.translate('settingsTasks.toast.metadataFailed'),
            life: 5000,
            detail: this.t.translate('settingsTasks.toast.filenameAuthorExtractFailed', {default: 'Failed to start filename author extraction.'})
          });
        }
        return of({success: false});
      })
    );
  }
}
