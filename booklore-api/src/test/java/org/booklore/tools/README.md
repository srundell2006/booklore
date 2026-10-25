# Rescan dry run

`RescanDryRun` says what a rescan of one library would do with its new files, using the
rescan's own grouping code (`BookGroupingService.groupForRescan` with `FilelessShells`,
`ExistingBookMatcher` and `BookFileGroupingUtils`). It needs no database and none of the
files: it reads three TSV exports and writes a report. It writes nothing else.

- Source: `RescanDryRun.java`, next to this file. It is a test class, so it is not in the
  application's jar. Its header documents how to run it and the report; this file adds the SQL
  that writes the exports.
- Test: `RescanDryRunTest.java`, with cases of wrong merges a rescan once made.

## Run it

From `booklore-api`, with the three exports in one folder:

```bash
./gradlew dryRunRescan --args='--dir /path/to/exports --out /path/to/exports/report.tsv'
```

Options:

| option | meaning |
|---|---|
| `--dir <folder>` | folder holding `shells.tsv`, `existing.tsv` and `newfiles.tsv` |
| `--shells`, `--existing`, `--newfiles <file>` | one export each, instead of `--dir` |
| `--mode AUTO_DETECT\|BOOK_PER_FOLDER\|BOOK_PER_FILE` | the library's organization mode (default AUTO_DETECT) |
| `--out <file>` | where the report goes (default: standard output); the summary also goes to standard error |

The task runs with a 2 GB heap. A synthetic export of 130k existing files, 17k fileless books
and 20k new files took 13 s from the Gradle command to the written report.

## The report

There is one tab-separated line per new file, with these columns:
`decision  target  group_key  reason  library_path_id  file_sub_path  file_name`

| decision | meaning | target |
|---|---|---|
| `attach-shell` | attached to a fileless book | that book's id |
| `attach-same-folder` | attached to a book with files in the same folder | that book's id |
| `ambiguous` | becomes a new book because more than one book qualifies, because a fileless book's claims were rejected (claimed from 2+ folders, or by two different files of one format), because the files it would form one book with matched different books, because the book they matched turned one of them down, or because its weak claim on a fileless book yields to the strong claims of other files (see below) | the candidate ids |
| `new-book` | becomes a new book; no existing book matches | `-` |

- `group_key` is the new book the file belongs to, in the format `<library_path_id>:<sub path>:<key>`.
  Files that are attached show the group they would have formed, so a copy and its original
  show the same key.
- `reason` comes from the matcher. For example, `fileless: book#500 "Survival" has none of its
  authors in the path`, or `folder: 2 books in the folder have this title`.
- An `attach-shell` reason also says when the file went to one of several alike fileless books
  (the same title, with the same year in brackets if any, series number and authors), as in `title and author agree with book#12
  "Survival", one of 2 alike fileless books (book#12, book#40)`, and when a ` (1)` at the end of
  its name was read as a copy, as in `title and author agree with book#7 "Saga" (" (1)" read as a
  copy)`.
- An `ambiguous` reason names apart the fileless books that qualify only if a ` (1)` is a copy,
  not volume 1, as in `fileless: 2 fileless books qualify: book#1 "Saga", and book#2 "Saga" if
  " (1)" is a copy`: book#2 is number 2 of its series, and the name does not say which it is.
- The rule for a lone ` (1)` turns a fileless book down for its place in its series only where
  the ` (1)` may mean volume 1 of that series. Its reason says which: `is number 2 of its series,
  and " (1)" may mean volume 1` when the series has no name (no `series_name`, and no
  `(Series, #N)` suffix on the title), and `is number 2 of a series of the same name, and " (1)"
  may mean volume 1` when the series' name reads as the file's name without the ` (1)`, maybe
  followed by one of `Series`, `Trilogy`, `Duology`, `Quartet`, `Quintet`, `Saga`, `Cycle`,
  `Sequence` and `Chronicles`. A series of another name does not count: `The Testaments (1).epub`
  goes to a fileless `The Testaments`, number 2 of The Handmaid's Tale, while `Saga (1).epub` does
  not go to a `Saga` numbered 2 in a series `Saga`.

The report ends with lines starting with `#`:
- the count of each decision;
- for files that become new books (`new-book` and `ambiguous`), the count of each reason an
  existing book was turned down. Book ids, numbers and quoted names are replaced, so alike
  reasons are counted together: a number, a fraction or a list of numbers (`2`, `2.5`, `2, 3`)
  is one `N`, but the `" (1)" may mean volume 1` of the rule for a lone ` (1)` stays as it is.
  For example: `fileless: is not by '...'`,
  `claim: claimed by files of N folders [...]`,
  `fileless: has a one-word title, which needs its author's given name in the path as the book has it`,
  `fileless: is number N of its series, and " (1)" may mean volume 1`,
  `fileless: is number N of a series of the same name, and " (1)" may mean volume 1`,
  `claim: a weak claim yields to the strong claims of other files`;
- for files whose decision a weak match had a part in, the count of each outcome. The outcomes
  that start with `attached` are the attaches only the newer rules make; the others are weak
  matches that did not change what the file got:

  | outcome | the file |
  |---|---|
  | `attached by its own weak match` | is attached to the book of its weak match, which no strong match of its group contradicts |
  | `attached by a weak match of the files it would be one book with` | has no match of its own, and follows the weak matches of its group |
  | `its weak match agrees with the strong match of the files it would be one book with` | goes to that book, as it would without its weak match |
  | `its weak match yields to the strong match of the files it would be one book with` | goes to the book of its group's strong match instead |
  | `its weak match yields to the strong claims of other files on its book` | becomes a new book; the strong claims get the book |
  | `follows a weak match that yields to the strong claims of other files on its book` | as the last, without a match of its own |
  | `its weak match comes to nothing: ...` | becomes a new book: its group's matches do not agree or a file of it is turned down, or its book's claims were rejected |
  | `a match by its name as it reads goes before books found with " (1)" read as a copy` | matched a book strongly; the books its ` (1)` read as a copy finds yield to it |

Useful cuts:

```bash
grep -v '^#' report.tsv | awk -F'\t' '$1=="attach-shell"' | less        # every attach to a fileless book
grep -v '^#' report.tsv | awk -F'\t' '$1=="new-book" && $4 ~ /is not by/' # files turned down on the author rule
grep -v '^#' report.tsv | awk -F'\t' '$4 ~ /alike fileless books|read as a copy/' # attaches to alike books, or of a " (1)"
grep -v '^#' report.tsv | awk -F'\t' '$4 ~ /weak match yields|weak claim on/'   # weak matches that yield
```

### Weak matches

A match that only the choice among alike fileless books, or a ` (1)` read as a copy, makes is
weak. These two rules only ever add a book to a file that the rules before them gave none; they
never take one away or change it. So:

- A book the name reads as goes before the books only its ` (1)` read as a copy finds, and the
  reason names those: `title and author agree with book#1 "Nico (Book 1)";
  book#2 "Nico" qualifies only if " (1)" is a copy, and yields`.
- The files that would be one new book go to the book of their strong matches. A file whose own
  weak match is another book is attached with them, and its reason says so: `goes with the files
  it would be one book with; its own weak match yields to theirs: title and author agree with
  book#10 "Killing Floor", one of 2 alike fileless books (book#10, book#11)`. Weak matches only
  count in a group that has no strong one, and must then agree.
- When a fileless book's claims do not agree but its strong claims alone do, the book gets the
  files of the strong claims. The files whose claims only weak matches made, their own or those
  of the files they would be one book with, become a new book: decision `ambiguous`, reason `its
  weak claim on fileless book#70 yields to the strong claims of other files, which get it:
  claimed by files of 2 folders [...]: The MEG.epub, MEG (1).epub; the weak claims of MEG
  (1).epub yield to the others`. A file of a group that a strong match gave its book claims it
  as strongly, whatever its own match.

## The exports

These are tab-separated files with one row per line, as `mariadb -B -N` writes them:
- NULL is written as `NULL`;
- tab, newline and backslash inside a value are written as `\t`, `\n` and `\\`.

A first line that names the columns is skipped. Lines starting with `#` are skipped.

| file | columns |
|---|---|
| `shells.tsv` | `id, library_path_id, is_physical, has_cover, title, subtitle, series_number, authors[, series_name]` (authors joined with `; `; series_name optional) |
| `existing.tsv` | `book_id, library_path_id, file_sub_path, file_name, book_type[, authors]` (book formats of books that are not deleted; authors optional) |
| `newfiles.tsv` | `library_path_id, file_sub_path, file_name` (`file_sub_path` is empty for the library root) |

Notes on the columns:
- Without `series_name` in `shells.tsv`, as in an export made before it was added, a book's series
  has no name unless its title ends in a `(Series, #N)` suffix. The rule for a lone ` (1)` then
  turns down books the rescan would attach, such as `The Testaments (1).epub` for a fileless
  `The Testaments` numbered 2. Export it.
- Without authors in `existing.tsv`, same-folder matching cannot drop an author part of a file
  name that only the existing book's authors vouch for. For example, `Title - Author.mobi`
  next to `Title.epub` in a folder that is not the author's. Export them.
- A `newfiles.tsv` name without a book extension (`pdf epub cbz cbr cb7 mobi azw3 azw fb2 m4b
  m4a mp3 opus`) is taken for a folder audiobook, as the library walk reports one.

### SQL (MariaDB, read-only)

Run each statement with `mariadb -B -N <connection options> booklore -e "<statement>" > file.tsv`,
or from a `.sql` file that starts with the `SET` lines. Each `SET` holds only for its session,
so repeat the `SET` lines in each call.

```sql
SET SESSION group_concat_max_len = 1048576;
SET @library := (SELECT id FROM library WHERE name = 'My Library');   -- the library's name
-- Replay: the moment the rescan to replay started, and the end of the window to replay.
-- To export the library as it is now instead, set @since := NOW() + INTERVAL 1 DAY and make
-- newfiles.tsv from disk (see below).
SET @since := '2026-10-01 00:00:00';   -- placeholder: replace with the rescan's start, from the log
SET @until := NOW();
```

To find `@since`, look up that rescan's first "Rescan of '<library>'" log line. If the log is
not available, look at when many files were added:

```sql
SELECT DATE_FORMAT(bf.added_on, '%Y-%m-%d %H:00') AS hour, COUNT(*) AS files
FROM book_file bf JOIN book b ON b.id = bf.book_id
WHERE b.library_id = @library AND bf.added_on > NOW() - INTERVAL 30 DAY
GROUP BY hour ORDER BY hour;
```

**shells.tsv** lists the books that had no book_file row at `@since`. Books created by the
rescan itself are left out by their `added_on`.

```sql
SELECT b.id, b.library_path_id, COALESCE(b.is_physical, 0),
       (b.book_cover_hash IS NOT NULL OR b.audiobook_cover_hash IS NOT NULL),
       m.title, m.subtitle, m.series_number,
       (SELECT GROUP_CONCAT(a.name ORDER BY am.sort_order SEPARATOR '; ')
          FROM book_metadata_author_mapping am JOIN author a ON a.id = am.author_id
         WHERE am.book_id = b.id),
       m.series_name
FROM book b
JOIN book_metadata m ON m.book_id = b.id
WHERE b.library_id = @library
  AND (b.deleted IS NULL OR b.deleted = 0)
  AND (b.added_on IS NULL OR b.added_on < @since)
  AND NOT EXISTS (SELECT 1 FROM book_file bf
                   WHERE bf.book_id = b.id AND (bf.added_on IS NULL OR bf.added_on < @since))
ORDER BY b.id;
```

**existing.tsv** lists the book formats that were stored before `@since`, with their book's
authors.

```sql
SELECT bf.book_id, b.library_path_id, bf.file_sub_path, bf.file_name, bf.book_type,
       (SELECT GROUP_CONCAT(a.name ORDER BY am.sort_order SEPARATOR '; ')
          FROM book_metadata_author_mapping am JOIN author a ON a.id = am.author_id
         WHERE am.book_id = b.id)
FROM book_file bf
JOIN book b ON b.id = bf.book_id
WHERE b.library_id = @library
  AND (b.deleted IS NULL OR b.deleted = 0)
  AND bf.is_book = 1
  AND (bf.added_on IS NULL OR bf.added_on < @since)
ORDER BY bf.id;
```

**newfiles.tsv** lists the files the window's scans imported or attached: everything the
replayed rescan found new.

```sql
SELECT b.library_path_id, bf.file_sub_path, bf.file_name
FROM book_file bf
JOIN book b ON b.id = bf.book_id
WHERE b.library_id = @library
  AND bf.is_book = 1
  AND bf.added_on >= @since AND bf.added_on < @until
ORDER BY bf.id;
```

What a replay cannot restore:
- the metadata of fileless books as it was before the rescan (it exports what they hold now);
- books deleted since.

Fileless books that got files in the replayed rescan are not fileless any more. They still
appear in `shells.tsv`, because their files were added after `@since`, so the replay decides
those attaches again, as well as the new books.

### newfiles.tsv from disk (current state instead of a replay)

Use this for a library mounted at `/books` whose library path has id 1. Set `@since` to
tomorrow and export `shells.tsv` and `existing.tsv` as above. Then list the book files and keep
those not stored yet:

```bash
cd /books
find . -type f \( -iname '*.pdf' -o -iname '*.epub' -o -iname '*.cbz' -o -iname '*.cbr' -o -iname '*.cb7' \
  -o -iname '*.mobi' -o -iname '*.azw3' -o -iname '*.azw' -o -iname '*.fb2' -o -iname '*.m4b' \
  -o -iname '*.m4a' -o -iname '*.mp3' -o -iname '*.opus' \) -printf '%h\t%f\n' \
 | sed -e 's#^\./##' -e 's#^\.\t#\t#' | awk -F'\t' -v OFS='\t' '{print 1, $1, $2}' > /tmp/disk.tsv
awk -F'\t' -v OFS='\t' 'NR==FNR {stored[$2 FS $3 FS $4]=1; next} !(($1 FS $2 FS $3) in stored)' \
  existing.tsv /tmp/disk.tsv > newfiles.tsv
```

Limits of this listing:
- It lists the audio files of folder audiobooks one by one. The walk reports such a folder as
  one entry, so audiobook folders show up as many files here.
- It skips no `.ignore` folders.
- It compares paths exactly, as the walk does. A file whose stored row differs only in case or
  accents is new here, as it is to the rescan.
