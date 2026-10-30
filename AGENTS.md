<!-- BEGIN llm-wiki -->
## Codebase knowledge base (.llm-wiki/)

This repo has a compiled knowledge base under `.llm-wiki/` — a map of where code lives, how the
system fits together, its conventions, and recipes for common tasks. It is tool-neutral; any coding
agent (Codex, Claude, Copilot, etc.) should use it.

**Before searching the codebase, read `.llm-wiki/index.md`.** It points you straight to the relevant
files and to the right module page, so you can skip broad searches and read only what you need.

**After making a substantive code change** (new/removed/renamed modules or files, changed public
interfaces, new conventions, new dependencies), update the affected `.llm-wiki/` pages and append an
entry to `.llm-wiki/log.md`: `## [YYYY-MM-DD] update | <summary>`. The code is the source of truth;
if a page disagrees with the code, fix the page.

See `.llm-wiki/README.md` for the full contract.
<!-- END llm-wiki -->
