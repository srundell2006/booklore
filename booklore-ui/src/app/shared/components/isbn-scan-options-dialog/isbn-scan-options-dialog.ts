import {Component, inject} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Button} from 'primeng/button';
import {Checkbox} from 'primeng/checkbox';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';

export interface IsbnScanDialogData {
  /** 0 = copyright page only; >0 = wide scan to that many spine documents. */
  depth: number;
  /** Human-readable scope, e.g. a library or shelf name. */
  scopeName?: string;
}

export interface IsbnScanDialogResult {
  overwriteExisting: boolean;
  isbnOnly: boolean;
}

@Component({
  selector: 'app-isbn-scan-options-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, Button, Checkbox],
  templateUrl: './isbn-scan-options-dialog.html',
  styleUrls: ['./isbn-scan-options-dialog.scss']
})
export class IsbnScanOptionsDialog {
  private dialogRef = inject(DynamicDialogRef);
  private config = inject(DynamicDialogConfig);

  readonly data: IsbnScanDialogData = this.config.data ?? {depth: 0};

  overwriteExisting = false;
  isbnOnly = false;

  get isWideScan(): boolean {
    return this.data.depth > 0;
  }

  start(): void {
    this.dialogRef.close({
      overwriteExisting: this.overwriteExisting,
      isbnOnly: this.isbnOnly
    } as IsbnScanDialogResult);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
