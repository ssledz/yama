---
name: markdown-summary
description: Output-format rules for writing a summary, notes or a report as a markdown file. Use whenever you are about to write a summary or any other markdown document into the workspace.
license: MIT
metadata:
  author: yama
  version: "1.0"
---

# Writing a markdown summary

Follow these rules for every markdown file you write:

1. Start with a single `#` title that names the subject, not the task ("Art of Scala 2026", not "Summary").
2. Follow with one short paragraph (2-3 sentences) that says what the source is and why it matters.
3. Then short `##` sections, one idea each. Prefer 2-4 sections over many tiny ones.
4. End with a `## Key points` section: a bullet list of 3-7 facts, each one line.
5. If the content came from the web, add a `## Sources` section with the URLs you used.
6. Keep it faithful to the source: no invented facts, numbers or quotes. If something is unclear, say so.
7. Plain markdown only: no HTML, no emojis, no front matter.
