import {Component, inject, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Button} from 'primeng/button';
import {Checkbox} from 'primeng/checkbox';
import {InputText} from 'primeng/inputtext';
import {Tag} from 'primeng/tag';
import {Tooltip} from 'primeng/tooltip';
import {MessageService} from 'primeng/api';
import {filter, take} from 'rxjs/operators';
import {AppSettingsService} from '../../../shared/service/app-settings.service';
import {MediaToolsService} from './media-tools.service';
import {AudiobookMergeSettings, AudiobookVerificationSettings, EbookConversionSettings} from './media-tools-settings.model';

@Component({
  selector: 'app-media-tools-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, Button, Checkbox, InputText, Tag, Tooltip],
  templateUrl: './media-tools-settings.component.html',
  styleUrl: './media-tools-settings.component.scss'
})
export class MediaToolsSettingsComponent implements OnInit {
  private appSettingsService = inject(AppSettingsService);
  private mediaToolsService = inject(MediaToolsService);
  private messageService = inject(MessageService);

  conversion: EbookConversionSettings = this.defaultConversion();
  merge: AudiobookMergeSettings = this.defaultMerge();
  verification: AudiobookVerificationSettings = this.defaultVerification();

  testingConverter = false;
  testingMerge = false;
  testingWhisper = false;
  converterResult?: boolean;
  mergeResult?: boolean;
  whisperResult?: boolean;
  saving = false;

  ngOnInit(): void {
    this.appSettingsService.appSettings$.pipe(
      filter(s => s != null),
      take(1)
    ).subscribe(settings => {
      const loadedConversion = (settings as any)?.ebookConversionSettings;
      const loadedMerge = (settings as any)?.audiobookMergeSettings;
      const loadedVerification = (settings as any)?.audiobookVerificationSettings;
      if (loadedConversion) {
        this.conversion = {...this.defaultConversion(), ...loadedConversion};
      }
      if (loadedMerge) {
        this.merge = {...this.defaultMerge(), ...loadedMerge};
      }
      if (loadedVerification) {
        this.verification = {...this.defaultVerification(), ...loadedVerification};
      }
    });
  }

  testConverter(): void {
    this.testingConverter = true;
    this.converterResult = undefined;
    this.mediaToolsService.testConverter(this.conversion).subscribe({
      next: result => {
        this.converterResult = result.ebookConverter;
        this.testingConverter = false;
      },
      error: () => {
        this.converterResult = false;
        this.testingConverter = false;
      }
    });
  }

  testMerge(): void {
    this.testingMerge = true;
    this.mergeResult = undefined;
    this.mediaToolsService.testMerge(this.merge).subscribe({
      next: result => {
        this.mergeResult = result.m4bMerge;
        this.testingMerge = false;
      },
      error: () => {
        this.mergeResult = false;
        this.testingMerge = false;
      }
    });
  }

  testWhisper(): void {
    this.testingWhisper = true;
    this.whisperResult = undefined;
    this.mediaToolsService.testWhisper(this.verification).subscribe({
      next: result => {
        this.whisperResult = result.whisper;
        this.testingWhisper = false;
      },
      error: () => {
        this.whisperResult = false;
        this.testingWhisper = false;
      }
    });
  }

  save(): void {
    this.saving = true;
    this.appSettingsService.saveSettings([
      {key: 'EBOOK_CONVERSION_SETTINGS', newValue: this.conversion},
      {key: 'AUDIOBOOK_MERGE_SETTINGS', newValue: this.merge},
      {key: 'AUDIOBOOK_VERIFICATION_SETTINGS', newValue: this.verification}
    ]).subscribe({
      next: () => {
        this.saving = false;
        this.messageService.add({
          severity: 'success', summary: 'Saved',
          detail: 'Media tool settings saved', life: 3000
        });
      },
      error: () => {
        this.saving = false;
        this.messageService.add({
          severity: 'error', summary: 'Error',
          detail: 'Failed to save media tool settings', life: 4000
        });
      }
    });
  }

  private defaultConversion(): EbookConversionSettings {
    return {
      enabled: false,
      serviceUrl: 'http://172.30.0.36:8080',
      defaultTargetFormat: 'epub',
      attachAsAlternativeFormat: true,
      timeoutMinutes: 15
    };
  }

  private defaultMerge(): AudiobookMergeSettings {
    return {
      enabled: false,
      serviceUrl: 'http://172.30.0.37:8080',
      stagingPath: '/merge',
      serviceStagingPath: '/merge',
      audioCodec: 'aac',
      audioBitrate: '64k',
      audioSamplerate: '22050',
      audioChannels: '1',
      maxChapterLength: '300,900',
      useFilenamesAsChapters: false,
      preferLossless: true,
      jobs: 2,
      deleteSourcesAfterMerge: false,
      jobTimeoutMinutes: 360
    };
  }

  private defaultVerification(): AudiobookVerificationSettings {
    return {
      enabled: false,
      whisperUrl: 'http://whisper:8000',
      excerptSeconds: 90,
      ollamaModel: 'llama3.1:8b',
      ollamaUrl: 'http://ollama:11434'
    };
  }
}
