# Writing the user guide

Read this before you add or edit a page in `docs/guide/`. The full writing
rules and the page list are in [README.md](README.md).

## Style

1. **No troubleshooting sections.** No "When something is off", "Common
   problems", "FAQ" or "Gotchas" at the end of a page. When a step can fail,
   say so in that step, in one sentence.
2. **Minimal.** Say what the reader needs to do it, nothing more. Cut
   background, recaps, "Also useful" lists and repeated explanations. Link to
   the page that explains a thing instead of explaining it again.
3. **No marketing talk.** No "powerful", "seamless", "simply", "just",
   "easily", no selling the feature. State what it does.

## Mechanics

- A new page goes in the Pages list of [README.md](README.md), or the site
  does not publish it.
- A new option, flag or rule field gets its row in the matching reference
  page (`configuration.md`, `command-line.md`, `rules-reference.md`,
  `clj-tool-reference.md`, `extensions-reference.md`).
- A tutorial's code must run as written. Tutorials with a test that reads
  the page: `test/xi/ext/user_test.cljs` (tool tutorial) and
  `test/xi/roles_tutorial_test.cljs` (users and roles). Run `bb test` after
  editing their code blocks.
- Preview with the docs site: `bb site:dev`, then
  `http://localhost:4322/docs/<page>/`.
