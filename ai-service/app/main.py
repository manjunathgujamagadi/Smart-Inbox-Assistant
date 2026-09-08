import logging

from fastapi import FastAPI

from app.routers import analyze, literature, pdf

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="Smart Inbox AI Service", version="0.1.0")
app.include_router(pdf.router)
app.include_router(analyze.router)
app.include_router(literature.router)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}
