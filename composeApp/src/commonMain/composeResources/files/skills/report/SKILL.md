---
name: report
description: Build document deliverables the user can keep: PDF, Word (.docx), Excel (.xlsx), or slides (.pptx) reports, memos, and summaries — or a self-contained HTML report when the Linux sandbox is not installed. Use when the user asks for a report, 报告, PDF, Word document, spreadsheet, slide deck, or HTML report as a file.
---

Produce a finished document file from chat context, workspace files, or the web, then hand it over with `open_file`. Text answers stay in chat; this skill is only for file deliverables.

## Steps

1. **Pin the format.** If the user didn't name one, ask exactly one question (PDF / Word / Excel / slides / HTML) and proceed. PDF is the default for read-only reports; Word when they will keep editing; Excel for tabular data with formulas; slides for presentations; HTML for a self-contained, previewable page.
2. **Gather content.** Pull numbers and facts from the conversation and from workspace files first (read them with `read_file`; paths are relative to the workspace home, and `/root/...` prefixes also work). For fresh external facts use `web_search` / `fetch_url` and note sources inside the document — never invent figures, dates, or quotes.
3. **Build the deliverable.**
   - **With the Linux sandbox** use `execute_shell_command` (Debian has `python3`; install libs once per distro with pip):
     - PDF: `pip install reportlab` — headings, tables, page numbers, header/footer.
     - Word: `pip install python-docx` — heading styles, tables, table of contents field, page numbers in footer.
     - Excel: `pip install openpyxl` — one table per sheet, header row freeze, number formats, formulas where asked, no hardcoded totals next to formula cells.
     - Slides: `pip install python-pptx` — title + content layouts, ≤ 6 lines per slide, speaker notes for the narrative.
     - Read/verify inputs with `pypdf` / `pdfplumber` (`pip install pypdf pdfplumber`) instead of eyeballing binaries.
     - Write outputs with relative paths (e.g. `report.pdf`).
   - **Without the sandbox**, build a self-contained HTML report instead: one file with inline CSS, no external assets, numbered sections, tables for data. Write it with `write_file` (e.g. `report.html`). State plainly in your reply that the sandbox-only formats (PDF/Word/Excel/slides) need the Linux sandbox.
4. **Mind CJK output.** For Chinese PDFs check fonts first: `fc-list :lang=zh | wc -l`. Zero means install `fonts-noto-cjk` via apt before building, otherwise CJK glyphs render as boxes. Word/Excel/slides carry no font embedding duty — set the font name (e.g. Noto Sans CJK SC) and move on. HTML reports should name CJK-friendly system font stacks and set a UTF-8 charset.
5. **Verify before handing over.** Re-open what you wrote (page count, table row counts, formula spot-checks; for HTML re-read the file and check it ends with the closing tags) — a truncated or corrupt file must never reach `open_file`.
6. **Deliver.** Call `open_file` with the workspace-relative path; pass `preview=true` for HTML so it opens in Kai's in-app preview, and summarize in one short message what the file contains and where.

## Rules

- One deliverable per run. A second format is a second run, not a silent extra file.
- Keep filenames stable (`report.pdf`, `report.html`) so repeat runs overwrite rather than litter the workspace.
- Never paste private-key or password material into document bodies; secrets stay out of deliverables.
