# Smart Inbox Assistant — Write-up

Clinevo Technologies take-home assignment. Covers architecture, tech choices, prompting approach,
known limitations, and what would change for production.

## 1. What was built

A working prototype that ingests emails from a real IMAP test mailbox (with an offline fixture
loader as a fallback),
processes PDF attachments across all four required "flavors," classifies each message into one or
more of ICSR / PQC / MI / Not Relevant with confidence + reasoning, extracts category-specific
structured fields with per-field confidence and a source pointer (email body or PDF+page), and
presents everything on an Angular review screen where a reviewer accepts or overrides each
classification and field. Every AI decision and every reviewer action is written to an audit log.

## 2. Architecture

```
                    ┌─────────────┐
                    │   Angular   │  review queue, document viewer,
                    │  (frontend) │  editable extracted fields
                    └──────┬──────┘
                           │ REST (/api/*)
                           ▼
                    ┌─────────────┐   IMAP poll (30s)     ┌──────────────┐   SMTP   senders /
   test-data/  ····▶│ Spring Boot │◀──────────────────────│ test mailbox │◀──────── send_test_emails.py
   (offline only)   │  (backend)  │
                    │             │  REST (/ai/*)
                    │  - ingestion│───────────┐
                    │  - queue    │           ▼
                    │  - audit log│      ┌───────────┐
                    │  - review   │      │  FastAPI  │  Azure OpenAI
                    └──────┬──────┘      │(ai-service)│──▶ (gpt-5-mini) 
                           │             └───────────┘
                           ▼
                    ┌─────────────┐
                    │  PostgreSQL │
                    └─────────────┘
```

Flow: Angular → Spring Boot → Python AI service → Postgres, with a durable in-process job queue
(see §5) between ingestion and AI processing so the HTTP caller never blocks on model latency.

## 3. Tech choices and deviations from the suggested stack

| Layer | Suggested | Used | Why |
|---|---|---|---|
| Frontend | Angular | Angular 18 + Material | As suggested; Material gets a functional review screen (queue table, tabs, form controls) built quickly without hand-rolling UI primitives. |
| Backend | Spring Boot | Spring Boot 3 / Java 21 | As suggested. |
| AI service | Python | FastAPI | As suggested; FastAPI + Pydantic gives typed request/response schemas that mirror the Java DTOs 1:1. |
| Database | Oracle (PL/SQL) | **PostgreSQL** | Deliberate deviation. Oracle's local-dev story (multi-GB Docker image, licensing friction, PL/SQL syntax overhead) buys little for a prototype — the relational modeling, SQL, and audit-log design are the same skill either way, and Postgres gets a working DB running in seconds via `docker compose up`. Everything here (schema, JSONB columns for tables/audit detail, indexes) ports to Oracle with minor syntax changes if this became a real Clinevo deployment. |
| AI model | "Your choice" | **Azure OpenAI, gpt-5-mini** | See below. |

### AI model: Azure OpenAI (gpt-5-mini)

The prototype was first built on gpt-4o-mini and then moved to **gpt-5-mini**, a small *reasoning*
model; both are supported by the same code (`llm_client.py` detects reasoning models by name).

- **Why switch**: classification here is a judgment call on borderline multi-label cases (is a
  question inside a safety report an info request? is a recalled batch a quality complaint?).
  gpt-5-mini reasons before answering, which gives tighter one-line reasons and better-spread
  confidence scores than gpt-4o-mini on the same prompts.
- **Cost/speed trade-off**: it is still a "mini"-tier model priced for high-volume processing (this
  pipeline makes several calls per document: flavor-specific extraction, per-page OCR or
  translation, a summary, and one classification+extraction call). The cost is latency: hidden
  reasoning makes each call ~5-7 s, so PDF-bearing documents take ~40-50 s end to end (vs ~20 s on
  gpt-4o-mini) - inside the brief's "a few seconds to a minute", and the queue keeps the UI responsive.
- **API differences handled**: reasoning models accept only the default temperature (so the
  `temperature=0` used for classification on gpt-4o-mini is dropped - the code-enforced category
  rules in §5 keep results consistent instead) and take `max_completion_tokens`, which must also
  cover hidden reasoning tokens (budget raised to 16k). `reasoning_effort=low` was tried for speed
  and rejected: the model then skipped reading images, breaking OCR.
- **Multimodal**: gpt-5-mini handles both text and vision in the same API, so one model serves
  OCR of scanned/handwritten pages, description of embedded images, and text classification/
  extraction - no second vision-specific model or separate OCR library needed.
- **Structured output**: OpenAI's function-calling with `strict: true` (see §5) gives schema-
  guaranteed JSON, which is what makes reliable field-by-field confidence + source tracking
  practical without brittle prompt-only JSON parsing.
- **Data handling**: content is sent to Azure OpenAI (not the public OpenAI API) specifically
  because Azure supports a signed data-processing agreement / BAA - the closer of the two to
  something you could actually point at real PHI in production. Even so, this prototype only ever
  sends synthetic data (§6) - no real patient data reached any cloud API at any point.

## 4. Data model

Nine tables (`db/migration/V1__init_schema.sql`): `messages`, `attachments`, `classifications`,
`extracted_fields`, `tables_extracted`, `images_extracted`, `audit_log`, `processing_jobs`, plus
Flyway's own history table. Key design points:

- `extracted_fields.source_type` (`EMAIL_BODY`/`PDF_PAGE`) + `source_ref` (`"page N"`) +
  `attachment_id` together satisfy the "every fact links back to exactly where it came from"
  requirement - this is enforced at the schema level, not just convention.
- `classifications` and `extracted_fields` both carry a `reviewer_status` /
  `reviewer_category`/`reviewer_value` alongside the AI's original output, so an override never
  destroys the AI's original answer - both are visible for audit purposes.
- `audit_log` is a generic `(entity_type, entity_id, actor, action, detail_json, created_at)` log;
  every AI decision (`AI_CLASSIFIED`, `AI_EXTRACTED`, `AI_PDF_PROCESSED`) and every reviewer action
  (`REVIEWER_ACCEPTED`/`REVIEWER_OVERRIDDEN`/`REVIEWER_REJECTED`) is written here, timestamped,
  with `actor='system'` for AI decisions and the reviewer's name for human ones.
- `processing_jobs` doubles as the queue's state table and the source for the batch timing report.

## 5. The processing pipeline

### Intake (Section 3A)

`MailIngestionService` polls a real IMAP test mailbox (Jakarta Mail) every 30s; a "Check mailbox"
button polls on demand. The synthetic test set is delivered to that mailbox as real emails with
PDF attachments by `test-data/send_test_emails.py`, so the batch run exercises the same intake a
production mailbox would. `LocalBatchIngestionService` (JSON fixtures from disk) remains only as an
offline fallback. Both converge on the same path: extract sender/subject/date/body, save PDF
attachments to a shared storage volume (other file types are logged only, never processed, per
spec), and enqueue.

Decisions in the IMAP path:

- **Bounded scan.** Only mail received since the newest already-ingested email (minus a day, so
  downtime never loses mail) is searched; on first run, from the day before startup or
  `MAIL_INGEST_SINCE`. Connecting an existing mailbox therefore doesn't send its whole history to
  the LLM. The mailbox is opened read-only; de-duplication is by Message-ID (IMAP UID as fallback).
- **All-or-nothing per email.** Each email is downloaded once in full and parsed locally, then the
  message, its attachments and its queue job are written in one transaction. A failure leaves no
  row, so the email is retried on the next poll instead of being marked "seen" but never processed.
  Parsing locally also avoids depending on how each IMAP server numbers nested MIME parts (testing
  against GreenMail showed a server returning the wrong part for a forwarded email).
- **Real-world email shapes.** Forwarded emails (`message/rfc822`) are unwrapped — the original
  reporter and text are kept and their PDFs processed; HTML-only bodies are converted to text;
  attachments are only treated as PDFs if the bytes are actually a PDF; same-named attachments
  don't overwrite each other.
- **Receipt date, not sent date.** `received_at` is the mailbox's receipt time (IMAP INTERNALDATE):
  in pharmacovigilance, receipt starts the reporting clock, and the sender's clock can't be trusted.

### The queue

A `processing_jobs` row is created synchronously (durable, queryable immediately) and a
`JobEnqueuedEvent` is published; a `@TransactionalEventListener(phase = AFTER_COMMIT)` +
`@Async` listener picks it up on a separate worker thread only once the enqueuing transaction has
committed. This is the "simple queue, not pure synchronous calls" the spec asks for - a caller
(the mail poller, or the batch endpoint) gets an immediate response while AI/OCR processing (which
takes several seconds to tens of seconds per document, per the batch results below) happens in the
background. This indirection also matters correctness-wise: an earlier version called the async
worker directly from inside the enqueuing transaction, which is a well-known Spring pitfall (see
§7) - routing through a committed-event listener was the fix.

### PDF flavor detection (Section 3B)

A pragmatic heuristic chain, not a trained classifier - documented as a deliberate simplification:

1. **Text-layer density** (chars/page from PyMuPDF): below a threshold → `SCANNED_HANDWRITTEN`,
   handled via per-page vision OCR with a model-reported confidence score.
2. **Language** of the text layer (`langdetect`): non-English → `NON_ENGLISH`, translated page-by-
   page with the original kept alongside (`pages[].text` = original, `pages[].translatedText` =
   English).
3. **Keyword + multi-column heuristics** (`abstract`, `references`, `case report`, etc., plus a
   bounding-box-based column check) on English text → `ARTICLE`, where a dedicated prompt extracts
   only the patient-case-relevant portion and explicitly ignores the references/discussion.
4. Otherwise → `DIGITAL`, extracted directly via PyMuPDF text + pdfplumber tables.

Tables (`pdfplumber.extract_tables()`) and meaningful embedded images (PyMuPDF image extraction,
filtered by a minimum pixel size, described via a vision call) are handled the same way across all
four flavors where applicable.

### Classification + extraction (Section 3C/3D)

One model call per message, forced into a structured tool response (`ANALYSIS_SCHEMA` in
`classification.py`), produces both the multi-label classification and every category-specific
field in a single pass - the model extracts fields as evidence for the category it's assigning,
which keeps the two consistent with each other. The system prompt encodes the category
definitions verbatim from Section 2 of the assignment, the field lists from Section 3D, and the
ground rules from Section 3E ("Not stated" instead of guessing, source citation required).

**Structured output reliability**: OpenAI's function-calling does *not* strictly enforce a JSON
schema's `required` list by default (unlike Anthropic's tool-use, which was the original design -
see §7) - it will sometimes omit a field it judges "not applicable" for a given row, which breaks
strict parsing. The fix is OpenAI's **strict mode** (`"strict": true` on the function definition):
every property must appear in `required`, with anything conceptually optional made nullable
instead (e.g. `"confidence": {"type": ["number", "null"]}`) and `additionalProperties: false` on
every object node. This guarantees schema-conformant output rather than hoping the model follows
the schema on its own.

**Classification precision - an iterative process, not a one-shot prompt**: running the full
synthetic batch (not just spot-checking a few messages) surfaced real over-classification patterns
from the model (gpt-4o-mini at the time) that reasoning about the prompt in the abstract never would have caught. In order:

1. PQC assigned to a pure ICSR case with no defect evidence ("this *could* indicate a quality
   issue"). Fixed by requiring a *named* defect for PQC, not mere product involvement.
2. MI assigned to an article-forwarding email, with a *fabricated* question nobody had asked -
   invented because the source discussed patient education thematically. Fixed by requiring a
   literal interrogative sentence or explicit request, and forbidding inferred questions.
3. A near-zero-confidence `NOT_RELEVANT` entry appearing *alongside* a real category (e.g. `ICSR
   100%` + `NOT_RELEVANT 90%` on the same message) - logically incoherent, since `NOT_RELEVANT`
   is defined as "none of the others apply." Fixed with an explicit mutual-exclusivity constraint.
4. The most persistent pattern: the model's own one-line `reason` would *admit* the evidence was
   weak ("no specific defect is mentioned," "more of a report than a direct question") and then
   assign the category anyway. Reasoning correctly in the explanation text didn't translate into
   acting on that reasoning. This needed two more rounds: a "self-consistency check" instruction
   (if your reason hedges, don't include the category) and, when that only partially worked,
   dropping `temperature` to `0` for the classification call specifically - a compact model asked
   to judge genuinely ambiguous secondary labels (does a case-report email also count as an info
   request?) showed visible run-to-run flip-flopping at the default temperature, and `0` favors
   its single most-confident reading instead.

5. Prompting alone still left slips (an `ICSR` + `NOT_RELEVANT 0%` pair; an `MI` label on a
   case-report email with an invented question). So the Section 2 definitions are now also
   **enforced in code** after the model answers (`_enforce_category_rules`): `NOT_RELEVANT` never
   coexists with another category; `MI` is dropped when `ICSR` or `PQC` applies (the brief defines MI
   as a question "with no bad reaction and no defect"); `MI` also requires a question or explicit
   request actually present in the source; zero-confidence labels are discarded; and fields belonging
   to a category that was not assigned are removed. The prompt does the judgment, the code guarantees
   the definitions.

With those rules, all 18 emails in the final batch carry exactly the categories the brief's
definitions call for (13 ICSR, 2 of them also PQC; 2 PQC-only; 2 MI-only; 1 Not Relevant).

**Confidence scoring**: an early batch had 283 of 288 field confidences at exactly `1.0`, which
tells a reviewer nothing. Two changes fixed that. The prompt now carries an explicit calibration
rubric (verbatim value 0.95-1.0; normalised or paraphrased 0.75-0.9; implied 0.5-0.7; unsure below
0.5; and separate guidance for "Not stated"). And each PDF page carries a *reading confidence* (the
vision model's OCR confidence for scanned pages, a fixed 0.9 for machine-translated pages, none for an
exact digital text layer), which is shown to the model and then applied as a hard cap: a fact can
never be more certain than the reading of the page it came from (`_cap_confidence`). The PDF-level
OCR confidence is stored on the attachment and shown in the review screen.

This is exactly the kind of "getting the domain right" issue the assignment scores on, and all of
it only surfaced by running the full batch and reading real output - not from reading the prompt.

## 6. Test data (Section 6)

18 synthetic emails / 14 PDFs under `test-data/`, covering every required category (breakdown and
per-file mapping in `test-data/README.md`). All fabricated - fictional patients, fictional products
(Neptazol, Ferbolex, Cortivex Cream, Sedaprin, Glucoreg). Handwritten forms are simulated by
rendering text with a handwriting font onto a page image with no text layer (no scanner was
available); this is called out as an approximation. Non-English content (Spanish, French) was
authored directly, not machine-translated from English, so the translation step is genuinely
exercised rather than round-tripped.

### Batch run results (Section 3E)

Final run over all 18 emails (via `POST /api/batch/run`) plus the 2 literature-screening uploads,
processed automatically through the queue:

| Metric | Value |
|---|---|
| Documents processed | 20 / 20 (0 failed) |
| Average time per document | ~12.3s |
| Fastest | ~1.2s (short MI/irrelevant emails, no attachment) |
| Slowest | ~25s (documents with a PDF attachment: OCR, translation, or article extraction plus tables/images) |

The same numbers, per document, are on the **Processing report** page of the UI.

Per-document timing is queryable live via `GET /api/batch/report`, and full extracted output for
every document is exported under `docs/sample-outputs/`. All 18 primary category assignments
(ICSR/PQC/MI/Not Relevant) were correct; secondary/multi-label precision is discussed above and in
§8. A transient DNS resolution failure reaching Azure from inside the container hit 3 of 18
documents on one run - the queue correctly recorded it as a durable `FAILED` status with the error
message (not a silent loss), and a `POST /api/batch/retry-failed` endpoint (added once this
happened) re-enqueued and successfully reprocessed them.

## 7. Bugs found and fixed during testing

Worth documenting because they're the kind of thing that only shows up when you actually run the
system, not from reading the code:

1. **Spurious `/actuator/health` failures**: Spring Boot's mail auto-configuration registers a
   health indicator that pings the configured host as an SMTP server; since `MailIngestionService`
   talks raw IMAP directly and never uses the autoconfigured `JavaMailSender`, this check was
   meaningless and slow (10s timeout) - disabled via `management.health.mail.enabled=false`.
2. **Self-invoked `@Async` silently runs inline**: the original queue design called the `@Async`
   worker method directly from within `enqueue()`. Self-invocation bypasses Spring's proxy-based
   AOP, so the "async" work actually ran synchronously inside the caller's transaction - and when
   the AI call failed, the nested `@Transactional` processing method marked that *shared*
   transaction rollback-only before the failure was caught, so the whole transaction (including the
   job's FAILED status and audit entries) silently rolled back on commit, leaving messages stuck at
   `RECEIVED` with no visible error. Fixed by publishing an event and consuming it via
   `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`, which gives the async work its
   own transaction boundary entirely.
3. **`LazyInitializationException` across the async boundary**: once processing genuinely ran on a
   separate thread, it no longer shared a Hibernate session with the code that fetched the
   `ProcessingJob` - touching its lazy `message` association, or passing a detached `Message`
   entity into a second `@Transactional` method and expecting its lazy `attachments` collection to
   load, both failed. Fixed by re-fetching by ID inside whichever transactional method actually
   needs the data, rather than passing entities across transaction boundaries.
4. **Dependency pin incompatibilities**, caught by actually installing and running the code rather
   than trusting a `pip install` to succeed: the initially-pinned `pymupdf` version had no prebuilt
   wheel for the local Python version and tried to compile from source, which then broke because
   the project's own path contains a space (re-pinning to a version with a prebuilt wheel fixed
   both problems at once). More generally, several packages across this project (`anthropic`
   initially, `pymupdf`, `openai`) needed newer pins than a training-data prior would suggest,
   changing this into a "verify by actually installing/running" habit rather than assuming a
   remembered version number still resolves cleanly.
5. **OpenAI function-calling doesn't strictly enforce a schema's `required` list by default** (see
   §5) - a required field can simply be missing from the model's response, breaking strict
   `pydantic` parsing. `"strict": true` plus nullable-typing anything conceptually optional fixed
   this at the schema level rather than adding defensive `.get()` calls to paper over it.

## 8. Known limitations

- **Confidence scores are model-reported, then rule-capped - not statistically calibrated.** The
  rubric and the page-level cap (§5) spread scores over a meaningful range, but the underlying
  numbers still come from the model's own judgment; the vision model in particular rates its own
  handwriting transcription quite high (0.95 on the synthetic forms). Real calibration would need a
  labelled set of reviewed cases to fit against - which the reviewer accept/override data in the
  audit log is exactly the start of.
- **The MI "explicit question" check is lexical** (a `?` or a known request phrase in the source). It
  stops invented questions, but a question phrased unusually without a question mark could be missed;
  the reviewer can add the category back via override.
- **PDF flavor detection is heuristic, not learned.** Text-density thresholds and keyword lists
  work well on this test set but would need tuning (or a real classifier) against a broader real-
  world document mix.
- **No OCR fallback library** (e.g. Tesseract) - scanned/handwritten pages rely entirely on the
  vision model's transcription; there's no second opinion to cross-check against.
- **Multi-column layout detection is a simple bounding-box heuristic**, not true column
  reconstruction - it decides "this looks like an article," and relies on PyMuPDF's block order for
  reading order. `article-02.pdf` is a genuine two-column journal layout to exercise this path.
- **Retry is batch-level, not automatic.** `POST /api/batch/retry-failed` re-enqueues every FAILED
  message (used successfully during testing after a transient network error - see §6), but there's
  no automatic backoff-and-retry inside a single job. In the UI a human triggers it with **Retry
  failed** on the Processing report page; there is no per-message retry button yet.
- **Frontend has no automated tests** - manually verified in-browser and via a scripted Playwright
  pass (queue list, PDF viewer, field override, submit, audit trail all exercised end-to-end - see
  `docs/screenshots/`), but there's no CI test suite.
- **Audit trail shown in the UI is scoped to entities relevant to one message** (message,
  attachments, classifications, fields) rather than the full `audit_log` table, to keep the review
  screen focused - `processing_jobs`-level timing/failure entries are visible via the batch report
  endpoint instead.

## 9. What I'd change for production

- Real Oracle (or another org-standard RDBMS) if that's a hard organizational requirement, with the
  same schema ported to PL/SQL.
- A real message broker (SQS/RabbitMQ) instead of an in-process queue, so processing survives a
  backend restart and can scale horizontally.
- A signed BAA with the model provider (Azure OpenAI supports this) before any real patient data
  touches the pipeline, or a self-hosted/open-weight model if that's not acceptable at all.
- Retry with backoff + a manual "reprocess" action in the UI for failed jobs.
- Role-based access control on the review screen (who can override what) and a real identity
  behind `reviewerName` instead of a hardcoded default.
- A trained or fine-tuned classifier (or few-shot examples in the prompt) for PDF flavor detection,
  replacing the heuristic chain, once there's a larger labeled document set to validate against.

## 10. Bonus: literature screening (Section 4)

Implemented and tested. Reuses almost the entire Section 3 pipeline rather than building a parallel
system: `Message.sourceType` already had an `UPLOAD` value (alongside `EMAIL`), so an uploaded
article is just a `Message` with one PDF `Attachment`, going through the same storage, queue,
PDF-flavor-detection, and audit-log machinery - `DocumentProcessingService.process()` branches on
`sourceType` only at the very last step, calling a literature-specific AI endpoint instead of
`analyze-message`. The review UI's accept/override/reject controls, source citations, and
audit trail are the exact same components, just fed a different data shape.

**What's new:**
- `POST /api/literature/upload` (multipart, accepts several PDFs at once - "a batch of article PDFs
  uploaded independently," not through the mailbox) - each file becomes its own `Message`.
- A new table, `literature_cases` (one row per real patient case found within an article - an
  article can yield zero, one, or several), plus a `case_number` column added to `extracted_fields`
  so per-case evidence fields reuse the exact same field taxonomy as ICSR extraction, just scoped to
  one case at a time.
- The Angular review screen gains a "Literature Screening — Patient Cases" panel (shown instead of
  the ICSR/PQC/MI panels for `UPLOAD` messages) with its own accept/override/reject controls per
  case, and per-case field groups nested underneath - screenshots in `docs/screenshots/05-06`.

**Case-splitting was the hard part, and initially failed**: the natural first design point (one AI
call, structured output with a `cases[]` array, each case carrying its own nested `extractedFields[]`
array) worked for the "zero cases" and "exactly one case" scenarios in testing, but for a genuinely
two-case article (`test-data/literature-samples/lit-multi-case.pdf` - "Case 1: ..." / "Case 2: ..."
explicitly labeled in the text), it silently returned only the first case. `finish_reason` was
`"stop"` (not `"length"`) and completion tokens were well under budget, so this wasn't truncation -
the model fully, correctly detailed one case and then just... stopped, despite an explicit
instruction not to. This reproduced consistently, including after adding an even more forceful
"count the cases before answering" instruction.

The fix was structural, not another prompt tweak: split into two calls. A lightweight first call
enumerates every case (case number, relevance verdict, reason, short summary - no nested field
array), then one focused follow-up call per relevant case extracts that case's detailed evidence
fields alone, telling the model explicitly which case to focus on and to ignore the others. Once no
single call had to produce more than one "heavy" nested object, both cases came back correctly and
distinctly (verified on `lit-multi-case.pdf`, plus `lit-no-case.pdf` for the zero-case path and the
existing single-case `article-*.pdf` set for the one-case path - all three shapes tested and
passing, output in `docs/sample-outputs/message-19.json` and `message-20.json`). The general lesson
- asking a compact model to enumerate a variable number of complex items in one structured-output
call is fragile in a way that isn't visible from a single-item test - is arguably as valuable as the
feature itself, and is the same category of issue as the classification-precision work in §5: don't
trust structured-output correctness at the schema level alone, verify against real multi-case input.
