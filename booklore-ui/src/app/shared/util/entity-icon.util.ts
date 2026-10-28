import {IconSelection} from '../service/icon-picker.service';

export type IconOwner = 'library' | 'shelf' | 'magicShelf';

/**
 * Drawn for a library, shelf or magic shelf whose icon was never set, so that it does not show
 * an empty space where its icon belongs.
 */
export const DEFAULT_ENTITY_ICONS: Record<IconOwner, string> = {
  library: 'pi pi-building-columns',
  shelf: 'pi pi-bookmark',
  magicShelf: 'pi pi-sparkles',
};

/**
 * The CSS classes of a primeicons icon stored in any of the forms found in the database: a bare name
 * ("book"), a single class ("pi-book"), the full classes ("pi pi-book"), or the full classes with the
 * prefix written once more by an older edit dialog.
 */
export function primeIconClass(value: string): string {
  const names = value.trim().split(/\s+/)
    .map(token => token.replace(/^(?:pi-)+/, ''))
    .filter(name => name !== '' && name !== 'pi');
  return ['pi', ...names.map(name => `pi-${name}`)].join(' ');
}

/** The icon to draw for a library, shelf or magic shelf, falling back to the default of its kind. */
export function entityIcon(icon: string | null | undefined, iconType: IconSelection['type'] | null | undefined, owner: IconOwner): IconSelection {
  if (!icon?.trim()) {
    return {type: 'PRIME_NG', value: DEFAULT_ENTITY_ICONS[owner]};
  }
  if (iconType === 'CUSTOM_SVG') {
    return {type: 'CUSTOM_SVG', value: icon};
  }
  return {type: 'PRIME_NG', value: primeIconClass(icon)};
}
