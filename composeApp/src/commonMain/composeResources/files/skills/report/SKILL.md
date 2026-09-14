---
name: report
description: Build document deliverables the user can keep: PDF, Word (.docx), Excel (.xlsx), or slides (.pptx) reports, memos, and summaries. Use when the user asks for a report, 报告, PDF, Word document, spreadsheet, or slide deck as a file.
---

Produce a finished document file from chat context, sandbox files, or the web, then hand it over with `open_file`. Text answers stay in chat; this skill is only for file deliverables.

## Steps

1. **Pin the format.** If the user didn't name one, ask exactly one question (PDF / Word / Excel / slides) and proceed. PDF is the default for read-only reports; Word when they will keep editing; Excel for tabular data with formulas; slides for presentations.
2. **Gather content.** Pull numbers and facts from the conversation and from `/root` files first. For fresh external facts use `web_search` / `fetch_url` and note sources inside the document — never invent figures, dates, or quotes.
3. **Build in the sandbox** with `execute_shell_command` (Debian has `python3`; install libs once per distro with pip):
   - PDF: `pip install reportlab` — headings, tables, page numbers, header/footer.
   - Word: `pip install python-docx` — heading styles, tables, table of contents field, page numbers in footer.
   - Excel: `pip install openpyxl` — one table per sheet, header row freeze, number formats, formulas where asked, no hardcoded totals next to formula cells.
   - Slides: `pip install python-pptx` — title + content layouts, ≤ 6 lines per slide, speaker notes for the narrative.
   - Read/verify inputs with `pypdf` / `pdfplumber` (`pip install pypdf pdfplumber`) instead of eyeballing binaries.
   - Write outputs under `/root` (e.g. `/root/report.pdf`).
4. **Mind CJK output.** For Chinese PDFs check fonts first: `fc-list :lang=zh | wc -l`. Zero means install `fonts-noto-cjk` via apt before building, otherwise CJK glyphs render as boxes. Word/Excel/slides carry no font embedding duty — set the font name (e.g. Noto Sans CJK SC) and move on.
5. **Verify before handing over.** Re-open what you wrote (page count, table row counts, formula spot-checks) — a truncated or corrupt file must never reach `open_file`.
6. **Deliver.** Call `open_file` with the `/root`-relative path and summarize in one short message what the file contains and where.

## Rules

- One deliverable per run. A second format is a second run, not a silent extra file.
- Keep filenames stable (`report.pdf`, `report.docx`) so repeat runs overwrite rather than litter `/root`.
- Never paste private-key or password material into document bodies; secrets stay out of deliverables.
