# User Guide — how it is maintained

`STS-User-Guide_v<version>.docx` in `docs/` is the guide. The PDF beside it is
what goes to https://www.rfsat.com/download/STS-User-Guide.pdf — that filename
carries no version so the address keeps working across reissues, and the
version is on the title page where a reader can see it.

## The document is the source

There is no generator. There was one, and it cost the guide its formatting
twice: the RFSAT logo on the title page, the Word heading styles and the
author's page breaks were all replaced by the script's own, because a script
that emits a document from scratch cannot preserve what it was never told
about.

So the order is the other way round now:

1. The author edits the guide in Word — layout, styles, images, page breaks,
   new sections, anything.
2. That document comes back and becomes `baseline_v<version>.docx` here.
3. Small wording changes between editions are made with `edit_guide.py`, which
   substitutes strings inside the document and touches nothing else.

`edit_guide.py` refuses to save if any of its edits does not match exactly
once. An edit that silently matches nothing is how a guide comes to describe a
setting renamed three releases ago.

## What must never be changed

* the RFSAT logo on the first page
* the heading styles, which come from the document's own template
* the page breaks
* any other formatting the author has set

None of these belongs to this repository's tooling. If a change needs a new
paragraph, a new table or a moved section, it is made in Word and comes back
as a new baseline — not by writing paragraphs from a script.

## Converting to PDF

`soffice --headless --convert-to pdf` is close but is not Word: check the
title page and the tables before publishing, or export the PDF from Word,
which is what the author's copy will match exactly.
