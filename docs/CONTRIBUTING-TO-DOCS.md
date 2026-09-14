---
title: Writing documentation and journal entries
---

# Writing documentation and journal entries

The documentation site is built with Jekyll from this `docs/` directory. Existing
guides remain ordinary Markdown files; Jekyll supplies the shared navigation and
page frame when it publishes them.

## Add or update a guide

Write a normal Markdown file in `docs/`. Add it to the `_data/navigation.yml`
source file when it belongs in the permanent library. Prefer a guide when the
reader needs instructions or a stable reference.

## Write a development note

Create a file in `_posts/` named `YYYY-MM-DD-short-title.md`. Start it with:

```yaml
---
title: A clear, human title
description: One sentence used on the journal index.
---
```

Write what changed, why the decision was made, and any limitation worth carrying
forward. A journal entry is a dated account of the work; move settled instructions
and reference material into a guide instead.

Write a journal entry in the language in which it is naturally written. Do not
create a second translation just to keep the journal mirrored. Add `locale:
pt-BR` to a Portuguese entry when you want it to use the Portuguese site chrome;
the shared journal intentionally lists entries in their original language.

## Preview locally

With Ruby and Bundler available:

```bash
bundle install
bundle exec jekyll serve --source docs --baseurl /drive_assist
```

The GitHub Pages workflow builds the same source on pushes to `master`. It does
not build or deploy the Android application.
