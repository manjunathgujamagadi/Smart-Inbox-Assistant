# Smart Inbox Assistant

A prototype that reads incoming emails and PDF attachments for a healthcare company's shared
mailbox, classifies each into Safety Report (ICSR) / Quality Complaint (PQC) / Info Request (MI) /
Not Relevant, extracts structured facts with source traceability, and presents everything on an
Angular review screen for a human to accept or override.

Built for the Clinevo Technologies take-home assignment. See [`docs/write-up.md`](docs/write-up.md)
for architecture, tech-choice rationale, prompting approach, and known limitations.

## Stack

| Layer | Tech |
|---|---|
| Frontend | Angular 18 + Angular Material |
| Backend API | Spring Boot 3 (Java 21) |
| AI service | Python (FastAPI) + Azure OpenAI (gpt-4o-mini) |
| Database | PostgreSQL (see write-up for why this replaces the suggested Oracle) |

All four run via Docker Compose.

## Prerequisites

- Docker + Docker Compose
- An Azure OpenAI resource with a `gpt-4o-mini` (or similar multimodal) deployment
- (Optional, for real mailbox intake) An IMAP-accessible test mailbox — e.g. a free Gmail account
  with an [app password](https://myaccount.google.com/apppasswords)

## Setup

1. Copy the env template and fill in real values:

   ```bash
   cp .env.example .env
   # edit .env: set AZURE_OPENAI_API_KEY / AZURE_OPENAI_ENDPOINT to real values
   ```

2. Build and start everything:

   ```bash
   docker compose up --build
   ```

3. Open the app:

   - Review UI: http://localhost:4200
   - Backend API / Swagger: http://localhost:8081/swagger-ui.html
   - AI service docs: http://localhost:8001/docs

   > Host ports 8081 / 8001 / 5433 (instead of the more obvious 8080 / 8000 / 5432) were chosen
   > because those defaults were already in use by unrelated services on the machine this was
   > built on. Change the left-hand side of the `ports:` mappings in `docker-compose.yml` if you'd
   > rather use the defaults on a clean machine.

## Running the required batch (Section 3E / Section 6)

The synthetic test-data set lives in [`test-data/`](test-data/) — 18 emails and 14 PDFs covering
every category in Section 6 (see [`test-data/README.md`](test-data/README.md)). To process it:

- Click **"Run batch (test-data)"** in the review queue toolbar, or
- `curl -X POST http://localhost:8081/api/batch/run`

Processing runs asynchronously (see the write-up for why); watch progress in the queue view, or
poll `GET /api/batch/report` for per-document status and timing once jobs complete.

## Real mailbox intake (Section 3A)

Set these in `.env` to enable IMAP polling instead of / in addition to the batch runner:

```
MAIL_POLLING_ENABLED=true
MAIL_IMAP_HOST=imap.gmail.com
MAIL_IMAP_PORT=993
MAIL_USERNAME=your-test-mailbox@gmail.com
MAIL_PASSWORD=your-app-password
```

Restart the backend (`docker compose up -d backend`) after changing these. The poller runs every
`MAIL_POLL_INTERVAL_MS` (default 60s) and only ever reads from the configured mailbox — use a
dedicated test account, never a real one, and never send it real patient data.

## Bonus: literature screening (Section 4)

Upload one or more article PDFs directly (independent of the mailbox) for case-splitting relevance
screening — an article can describe zero, one, or several real patient cases, and each gets its own
relevance verdict, summary, and extracted evidence fields, reviewable in the same UI.

- Click **"Upload article(s)"** in the review queue toolbar (select one or more PDFs), or
- `curl -X POST http://localhost:8081/api/literature/upload -F "files=@article1.pdf" -F "files=@article2.pdf"`

Two test articles are provided under [`test-data/literature-samples/`](test-data/literature-samples/):
one describing two distinct patient cases, one describing none — see the write-up (§10) for how
case-splitting works and a real bug that surfaced while testing it.

## Environment variables

All variables are read by `docker-compose.yml` from a root `.env` file (see `.env.example` for
placeholders — never commit real secrets).

| Variable | Required | Purpose |
|---|---|---|
| `AZURE_OPENAI_API_KEY` | Yes | Azure OpenAI key used by the AI service |
| `AZURE_OPENAI_ENDPOINT` | Yes | Azure OpenAI resource endpoint, e.g. `https://your-resource.openai.azure.com` |
| `AZURE_OPENAI_DEPLOYMENT` | No | Deployment name (default `gpt-4o-mini`) - must be a multimodal (vision-capable) deployment |
| `AZURE_OPENAI_API_VERSION` | No | API version (default `2024-12-01-preview`) |
| `MAIL_POLLING_ENABLED` | No | `true` to enable real IMAP polling (default `false`) |
| `MAIL_IMAP_HOST` / `MAIL_IMAP_PORT` | No | IMAP server for the test mailbox |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | No | Test mailbox credentials (app password, not your real password) |

## Project layout

```
backend/        Spring Boot API - ingestion orchestration, DB, review/audit endpoints
ai-service/     FastAPI service - Azure OpenAI (gpt-4o-mini) backed classification, extraction, OCR
frontend/       Angular review UI
test-data/      Synthetic emails + PDFs (see test-data/README.md) and the generator script
docs/           Write-up, architecture diagram, sample outputs, screenshots
```

## Local development (without Docker)

- **ai-service**: `cd ai-service && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt && AZURE_OPENAI_API_KEY=... AZURE_OPENAI_ENDPOINT=... uvicorn app.main:app --reload --port 8001`
- **backend**: needs Java 21 + Maven, or run via Docker as above; point it at a local Postgres via the `DB_*` env vars in `backend/src/main/resources/application.yml`.
- **frontend**: `cd frontend && npm install && npm start` — proxies `/api` to `localhost:8081` (see `proxy.conf.json`).

## Data handling note

All test data is synthetic (see the "No real patient data" rule in the assignment). Because the AI
service calls Azure OpenAI, email/PDF content is sent to a cloud-hosted model for processing —
acceptable here since nothing sent is real patient data, but a production deployment would need a
signed BAA / data-processing agreement (Azure OpenAI supports this, unlike the public OpenAI API)
or a self-hosted model before handling real PHI. See the write-up for the full trade-off discussion.
