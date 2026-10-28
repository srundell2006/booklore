import {Component, inject, Input, OnInit, OnChanges, SimpleChanges, ChangeDetectionStrategy, ChangeDetectorRef, DestroyRef} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {filter, skip} from 'rxjs/operators';
import {IconSelection} from '../../service/icon-picker.service';
import {NgClass, NgStyle} from '@angular/common';
import {IconCacheService} from '../../services/icon-cache.service';
import {IconService} from '../../services/icon.service';
import {DomSanitizer, SafeHtml} from '@angular/platform-browser';
import {AuthService} from '../../service/auth.service';
import {primeIconClass} from '../../util/entity-icon.util';

const ERROR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="red"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>';

@Component({
  selector: 'app-icon-display',
  standalone: true,
  imports: [NgClass, NgStyle],
  template: `
    @if (icon) {
      @if (icon.type === 'PRIME_NG') {
        <i [class]="getPrimeNgIconClass(icon.value)" [ngClass]="iconClass" [ngStyle]="getPrimeNgStyle()"></i>
      } @else {
        <div
          class="svg-icon-inline"
          [innerHTML]="getSvgContent(icon.value)"
          [ngClass]="iconClass"
          [ngStyle]="getSvgStyle()"
        ></div>
      }
    } @else if (displayEmpty) {
      <div
          [ngStyle]="getEmptyIconStyle()"
      ></div>
    }
  `,
  styles: [`
    :host {
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }

    .svg-icon-inline {
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }

    :host ::ng-deep .svg-icon-inline svg {
      width: 100%;
      height: 100%;
      display: block;
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class IconDisplayComponent implements OnInit, OnChanges {
  @Input() icon: IconSelection | null = null;
  @Input() iconClass: string = 'icon';
  @Input() iconStyle: Record<string, string> = {};
  @Input() size: string = '16px';
  @Input() alt: string = 'Icon';
  @Input() displayEmpty: boolean = false;

  private iconCache = inject(IconCacheService);
  private iconService = inject(IconService);
  private sanitizer = inject(DomSanitizer);
  private cdr = inject(ChangeDetectorRef);
  private authService = inject(AuthService);
  private destroyRef = inject(DestroyRef);
  private lastLoadedIconName: string | null = null;
  // The placeholder is kept here, not in the cache, so that the next component showing the icon fetches it again
  private loadFailed = false;
  private errorSvg: SafeHtml | null = null;

  ngOnInit(): void {
    this.loadIconIfNeeded();

    // A request that failed for an expired session succeeds once the token is renewed
    this.authService.token$.pipe(
      skip(1),
      filter(token => !!token && this.loadFailed),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => {
      this.lastLoadedIconName = null;
      this.loadIconIfNeeded();
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['icon']) {
      const currentIcon = changes['icon'].currentValue;
      const previousIcon = changes['icon'].previousValue;

      if (currentIcon?.type === 'CUSTOM_SVG' &&
        currentIcon?.value !== previousIcon?.value) {
        this.loadIconIfNeeded();
      }
    }
  }

  private loadIconIfNeeded(): void {
    if (this.icon?.type === 'CUSTOM_SVG' && this.icon.value !== this.lastLoadedIconName) {
      const iconName = this.icon.value;
      this.lastLoadedIconName = iconName;
      this.loadFailed = false;

      if (!this.iconCache.getCachedSanitized(iconName)) {
        this.iconService.getSanitizedSvgContent(iconName).subscribe({
          next: () => this.cdr.markForCheck(),
          error: () => {
            if (this.icon?.type === 'CUSTOM_SVG' && this.icon.value === iconName) {
              this.loadFailed = true;
              this.cdr.markForCheck();
            }
          }
        });
      }
    }
  }

  getSvgContent(iconName: string): SafeHtml | null {
    const cached = this.iconCache.getCachedSanitized(iconName);
    if (cached || !this.loadFailed) {
      return cached;
    }
    this.errorSvg ??= this.sanitizer.bypassSecurityTrustHtml(ERROR_SVG);
    return this.errorSvg;
  }

  getPrimeNgIconClass(iconValue: string): string {
    return primeIconClass(iconValue);
  }

  getSvgStyle(): Record<string, string> {
    return {
      width: this.size,
      height: this.size,
      ...this.iconStyle
    };
  }

  getEmptyIconStyle(): Record<string, string> {
    return {
      width: this.size,
      height: this.size,
    };
  }

  getPrimeNgStyle(): Record<string, string> {
    const fontSize = this.size.endsWith('px')
      ? `${parseInt(this.size) * 0.85}px`
      : `calc(${this.size} * 0.85)`;

    return {
      fontSize: fontSize,
      width: this.size,
      height: this.size,
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      ...this.iconStyle
    };
  }
}
