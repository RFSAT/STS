# User Guide — sources

`node guide.js` writes `STS-User-Guide_v<version>.docx` from `part1.js` …
`part4.js`. `build.js` holds the paragraph, table and callout helpers; the
text lives in the parts.

Requires `npm install` once (the `docx` package).

## The baseline rule

**The shooter's own copy is the baseline, not this generator.**

The guide is proof-read in Word and comes back edited — an article dropped, a
parenthesis removed, a link written out in full. Those edits are the author's
wording and they must not be quietly undone by the next regeneration, which is
exactly what happens if the generator is treated as the source of truth and
the returned document as an output.

So, before regenerating:

1. Diff the returned `.docx` against the last generated one — by TEXT, not by
   XML: paragraph text and table cell text, whitespace-normalised. Word
   rewrites styles and blank paragraphs on every save, and reading that as a
   change buries the two sentences that really did change.
2. Fold every difference into the parts, one edit at a time.
3. Regenerate and diff again. The only remaining difference must be the
   version on the title page.

`baseline_v<version>.docx` is the last returned copy, kept so step 1 has
something to diff against in a session that starts cold.

## Publishing

`STS-User-Guide.pdf` carries NO version in its filename: it is uploaded to
https://www.rfsat.com/download/STS-User-Guide.pdf and that address must keep
working across reissues. The version is on the title page, where a reader can
see it.
