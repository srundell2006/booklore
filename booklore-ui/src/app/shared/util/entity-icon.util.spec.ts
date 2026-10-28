import {describe, expect, it} from 'vitest';
import {DEFAULT_ENTITY_ICONS, entityIcon, primeIconClass} from './entity-icon.util';

describe('entity-icon.util', () => {

  describe('primeIconClass', () => {

    it.each([
      ['book', 'pi pi-book'],
      ['pi-book', 'pi pi-book'],
      ['pi pi-book', 'pi pi-book'],
      ['pi pi-pi pi-tablet', 'pi pi-tablet'],
      ['  pi   pi-book ', 'pi pi-book'],
      ['pi pi-fw pi-home', 'pi pi-fw pi-home'],
    ])('should read %j as %j', (stored, expected) => {
      expect(primeIconClass(stored)).toBe(expected);
    });
  });

  describe('entityIcon', () => {

    it('should fall back to the default of each kind when no icon is set', () => {
      expect(entityIcon(null, null, 'library')).toEqual({type: 'PRIME_NG', value: DEFAULT_ENTITY_ICONS.library});
      expect(entityIcon(undefined, 'CUSTOM_SVG', 'shelf')).toEqual({type: 'PRIME_NG', value: DEFAULT_ENTITY_ICONS.shelf});
      expect(entityIcon('  ', null, 'magicShelf')).toEqual({type: 'PRIME_NG', value: DEFAULT_ENTITY_ICONS.magicShelf});
    });

    it('should keep a custom SVG name as it is', () => {
      expect(entityIcon('rocket', 'CUSTOM_SVG', 'library')).toEqual({type: 'CUSTOM_SVG', value: 'rocket'});
    });

    it('should read an icon without a type as a primeicons icon', () => {
      expect(entityIcon('tablet', null, 'shelf')).toEqual({type: 'PRIME_NG', value: 'pi pi-tablet'});
      expect(entityIcon('pi pi-tablet', 'PRIME_NG', 'shelf')).toEqual({type: 'PRIME_NG', value: 'pi pi-tablet'});
    });
  });
});
