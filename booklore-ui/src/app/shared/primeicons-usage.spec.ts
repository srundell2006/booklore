import {describe, expect, it} from 'vitest';
import {readdirSync, readFileSync} from 'node:fs';
import {join, relative, resolve} from 'node:path';

// A "pi-<name>" class that primeicons does not define renders as an empty box, with no error anywhere.
// Only literal names are checked: a name built from data (a library's icon) or ending in a template
// placeholder ("pi-sort-${dir}") cannot be read from the source.

const ROOT = process.cwd();
const APP_DIR = resolve(ROOT, 'src/app');
const PRIMEICONS_CSS = resolve(ROOT, 'node_modules/primeicons/primeicons.css');

/** "pi-" not preceded by a word character or hyphen; a match followed by a hyphen is a name cut short by a placeholder. */
const LITERAL_ICON = /(?<![\w-])pi-([a-z0-9]+(?:-[a-z0-9]+)*)(-?)/g;

function definedIconNames(): Set<string> {
  const css = readFileSync(PRIMEICONS_CSS, 'utf8');
  return new Set([...css.matchAll(/\.pi-([a-z0-9-]+)/g)].map(match => match[1]));
}

function literalIconNames(source: string): string[] {
  return [...source.matchAll(LITERAL_ICON)]
    .filter(match => match[2] === '')
    .map(match => match[1]);
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, {withFileTypes: true}).flatMap(entry => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.(ts|html)$/.test(entry.name) && !entry.name.endsWith('.spec.ts') ? [path] : [];
  });
}

describe('primeicons usage', () => {

  it('should read the names primeicons defines', () => {
    const names = definedIconNames();
    expect(names.size).toBeGreaterThan(300);
    expect(names.has('book')).toBe(true);
    expect(names.has('fw')).toBe(true);
  });

  it('should find literal names and skip names completed at run time', () => {
    expect(literalIconNames(`icon: 'pi pi-highlighter'`)).toEqual(['highlighter']);
    expect(literalIconNames(`<i class="pi pi-fw pi-file-image"></i>`)).toEqual(['fw', 'file-image']);
    expect(literalIconNames('`pi pi-sort-amount-${dir}`')).toEqual([]);
    expect(literalIconNames(`'pi pi-' + library.icon`)).toEqual([]);
    expect(literalIconNames(`api-passthrough, ngsw-bypass`)).toEqual([]);
  });

  it('should use only icon names that primeicons defines', () => {
    const defined = definedIconNames();
    const unknown: string[] = [];

    for (const file of sourceFiles(APP_DIR)) {
      const source = readFileSync(file, 'utf8');
      for (const name of new Set(literalIconNames(source))) {
        if (!defined.has(name)) {
          unknown.push(`${relative(ROOT, file)}: pi-${name}`);
        }
      }
    }

    expect(unknown).toEqual([]);
  });
});
