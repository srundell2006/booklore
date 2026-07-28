import {Component, inject, OnInit} from '@angular/core';
import {MetadataProviderSettingsComponent} from '../global-preferences/metadata-provider-settings/metadata-provider-settings.component';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {MetadataRefreshOptions} from '../../metadata/model/request/metadata-refresh-options.model';
import {AppSettingsService} from '../../../shared/service/app-settings.service';
import {SettingsHelperService} from '../../../shared/service/settings-helper.service';
import {Observable} from 'rxjs';
import {AppSettingKey, AppSettings} from '../../../shared/model/app-settings.model';
import {filter, take} from 'rxjs/operators';
import {ToggleSwitch} from 'primeng/toggleswitch';
import {InputNumber} from 'primeng/inputnumber';
import {Select} from 'primeng/select';
import {MetadataMatchWeightsComponent} from '../global-preferences/metadata-match-weights/metadata-match-weights-component';
import {MetadataPersistenceSettingsComponent} from './metadata-persistence-settings/metadata-persistence-settings-component';
import {PublicReviewsSettingsComponent} from './public-reviews-settings/public-reviews-settings-component';
import {MetadataProviderFieldSelectorComponent} from '../../metadata/component/metadata-provider-field-selector/metadata-provider-field-selector.component';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {LibraryService} from '../../book/service/library.service';
import {Library} from '../../book/model/library.model';

@Component({
  selector: 'app-metadata-settings-component',
  standalone: true,
  imports: [
    MetadataProviderSettingsComponent,
    ReactiveFormsModule,
    FormsModule,
    MetadataMatchWeightsComponent,
    ToggleSwitch,
    InputNumber,
    Select,
    MetadataPersistenceSettingsComponent,
    PublicReviewsSettingsComponent,
    MetadataProviderFieldSelectorComponent,
    TranslocoDirective
  ],
  templateUrl: './metadata-settings-component.html',
  styleUrl: './metadata-settings-component.scss'
})
export class MetadataSettingsComponent implements OnInit {

  currentMetadataOptions!: MetadataRefreshOptions;
  metadataDownloadOnBookdrop = true;
  bookdropAutoImportEnabled = false;
  // number | null: InputNumber's ControlValueAccessor writes number | null to the bound field
  bookdropAutoImportMinScore: number | null = 50;
  bookdropAutoImportLibraryId: number | null = null;
  bookdropAutoImportPathId: number | null = null;
  libraries: Library[] = [];

  private readonly appSettingsService = inject(AppSettingsService);
  private readonly settingsHelper = inject(SettingsHelperService);
  private readonly libraryService = inject(LibraryService);
  private t = inject(TranslocoService);

  readonly appSettings$: Observable<AppSettings | null> = this.appSettingsService.appSettings$;

  get libraryOptions(): {label: string; value: number | null}[] {
    return this.libraries.map(lib => ({label: lib.name, value: lib.id ?? null}));
  }

  get selectedLibraryPaths(): {label: string; value: number | null}[] {
    const lib = this.libraries.find(l => l.id != null && l.id === this.bookdropAutoImportLibraryId);
    return lib?.paths.map(p => ({label: p.path, value: p.id ?? null})) ?? [];
  }

  ngOnInit(): void {
    this.libraryService.libraryState$.subscribe(state => {
      this.libraries = state.libraries ?? [];
    });
    this.loadSettings();
  }

  onMetadataDownloadOnBookdropToggle(checked: boolean): void {
    this.metadataDownloadOnBookdrop = checked;
    this.settingsHelper.saveSetting(AppSettingKey.METADATA_DOWNLOAD_ON_BOOKDROP, checked);
  }

  onBookdropAutoImportEnabledToggle(checked: boolean): void {
    this.bookdropAutoImportEnabled = checked;
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_ENABLED, checked);
  }

  // InputNumber onChange emits InputNumberChangeEvent { value: number | null }
  onBookdropAutoImportMinScoreChange(value: number | null): void {
    this.bookdropAutoImportMinScore = value;
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_MIN_SCORE, value ?? 50);
  }

  onBookdropAutoImportLibraryChange(libraryId: number | null): void {
    this.bookdropAutoImportLibraryId = libraryId;
    this.bookdropAutoImportPathId = null;
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_LIBRARY_ID, libraryId ?? '');
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_PATH_ID, '');
  }

  onBookdropAutoImportPathChange(pathId: number | null): void {
    this.bookdropAutoImportPathId = pathId;
    this.settingsHelper.saveSetting(AppSettingKey.BOOKDROP_AUTO_IMPORT_PATH_ID, pathId ?? '');
  }

  onMetadataSubmit(metadataRefreshOptions: MetadataRefreshOptions): void {
    this.currentMetadataOptions = metadataRefreshOptions;
    this.settingsHelper.saveSetting(AppSettingKey.QUICK_BOOK_MATCH, metadataRefreshOptions);
  }

  private loadSettings(): void {
    this.appSettings$.pipe(
      filter((settings): settings is AppSettings => !!settings),
      take(1)
    ).subscribe({
      next: (settings) => this.initializeSettings(settings),
      error: (error) => {
        console.error('Failed to load settings:', error);
        this.settingsHelper.showMessage('error', this.t.translate('common.error'), this.t.translate('settingsMeta.autoDownload.loadError'));
      }
    });
  }

  private initializeSettings(settings: AppSettings): void {
    if (settings.defaultMetadataRefreshOptions) {
      this.currentMetadataOptions = settings.defaultMetadataRefreshOptions;
    }

    this.metadataDownloadOnBookdrop = settings.metadataDownloadOnBookdrop ?? true;
    this.bookdropAutoImportEnabled = settings.bookdropAutoImportEnabled ?? false;
    this.bookdropAutoImportMinScore = settings.bookdropAutoImportMinScore ?? 50;
    this.bookdropAutoImportLibraryId = settings.bookdropAutoImportLibraryId ?? null;
    this.bookdropAutoImportPathId = settings.bookdropAutoImportPathId ?? null;
  }
}
