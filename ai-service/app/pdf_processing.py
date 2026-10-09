"""
PDF understanding (Section 3B): detects which of the four "flavors" a PDF is
(digital / scanned-handwritten / article / non-English) and extracts text,
tables, meaningful images, and a reviewer summary accordingly.

Flavor detection is a pragmatic heuristic chain, not a trained classifier:
  1. Text-layer density -> SCANNED_HANDWRITTEN (needs vision OCR) vs has-text.
  2. Language of the text layer -> NON_ENGLISH (needs translation).
  3. Keyword + multi-column heuristics on English text -> ARTICLE.
  4. Otherwise -> DIGITAL (a plain filled-in report/form).
This is documented as a deliberate simplification in the write-up.
"""
from __future__ import annotations

import io
import logging

import fitz  # PyMuPDF
import pdfplumber
from langdetect import detect
from langdetect.lang_detect_exception import LangDetectException
from PIL import Image

from app.llm_client import call_text, call_tool, png_bytes_to_b64
from app.config import get_settings
from app.schemas import ImageDto, PageDto, PdfProcessResponse, TableDto

logger = logging.getLogger(__name__)

OCR_SYSTEM_PROMPT = (
    "You are transcribing a scanned or handwritten page from a pharmacovigilance "
    "intake document (patient safety report, complaint form, or info request). "
    "Transcribe exactly what is written, including filled-in form field values. "
    "If handwriting is ambiguous, make your best reading and lower the confidence "
    "score accordingly rather than guessing wildly. Never invent content that isn't "
    "visibly present."
)

OCR_PAGE_SCHEMA = {
    "type": "object",
    "properties": {
        "text": {"type": "string", "description": "Full transcribed text of the page, preserving form field labels next to their values."},
        "confidence": {"type": "number", "description": "0.0-1.0 confidence in transcription accuracy; lower for unclear handwriting."},
    },
    "required": ["text", "confidence"],
    "additionalProperties": False,
}

IMAGE_DESC_SYSTEM_PROMPT = (
    "You are describing an image embedded in a pharmacovigilance intake document, "
    "for a human reviewer who has not seen it yet. Be factual and concise."
)

IMAGE_DESC_SCHEMA = {
    "type": "object",
    "properties": {
        "description": {"type": "string", "description": "1-2 sentence factual description. Note if it shows a damaged/defective product, an injury/rash, a filled-in form or checkbox, or something else."},
        "flaggedForReview": {"type": "boolean", "description": "true if a human should look at this image directly before finalizing the record."},
    },
    "required": ["description", "flaggedForReview"],
    "additionalProperties": False,
}

TRANSLATE_SYSTEM_PROMPT = (
    "You translate pharmacovigilance document pages to English. Preserve all facts, "
    "names, numbers, and dates exactly. Output only the translated text, no commentary."
)

ARTICLE_SYSTEM_PROMPT = (
    "You extract the patient-case-relevant portion of a published article for a "
    "pharmacovigilance reviewer. Ignore the references/bibliography list and general "
    "background discussion. If the article describes one or more real, identifiable "
    "patient cases, extract those sections verbatim or near-verbatim. If it describes "
    "no real patient case at all, say so explicitly in one sentence."
)

# Machine translation can shift nuance (e.g. dose units, negations), so facts read from a
# translated page are never reported as fully certain.
TRANSLATION_CONFIDENCE = 0.9

SUMMARY_SYSTEM_PROMPT = (
    "You write short, plain-language summaries of pharmacovigilance intake documents "
    "for a human reviewer who has not read the document yet."
)


def process_pdf(file_path: str) -> PdfProcessResponse:
    settings = get_settings()
    doc = fitz.open(file_path)
    try:
        page_count = doc.page_count
        raw_pages_text = [page.get_text("text") for page in doc]
        total_chars = sum(len(t.strip()) for t in raw_pages_text)
        avg_chars_per_page = total_chars / max(page_count, 1)

        if avg_chars_per_page < settings.scanned_min_chars_per_page:
            return _process_scanned(doc, page_count)

        combined_sample = " ".join(raw_pages_text)[:3000].strip()
        try:
            lang = detect(combined_sample) if combined_sample else "en"
        except LangDetectException:
            lang = "en"

        if lang != "en":
            return _process_non_english(doc, file_path, raw_pages_text, page_count, lang)

        if _looks_like_article(doc, raw_pages_text):
            return _process_article(doc, file_path, raw_pages_text, page_count)

        return _process_digital(doc, file_path, raw_pages_text, page_count)
    finally:
        doc.close()


def _process_scanned(doc, page_count: int) -> PdfProcessResponse:
    settings = get_settings()
    pages: list[PageDto] = []
    tables: list[TableDto] = []
    images: list[ImageDto] = []
    confidences: list[float] = []

    for i in range(page_count):
        page = doc[i]
        pix = page.get_pixmap(dpi=200)
        b64 = png_bytes_to_b64(pix.tobytes("png"))
        result = call_tool(
            model=settings.vision_model,
            system=OCR_SYSTEM_PROMPT,
            user_text=f"Transcribe page {i + 1} of this scanned/handwritten document.",
            tool_name="emit_ocr_page",
            tool_description="Report the transcribed text and a confidence score for one page.",
            input_schema=OCR_PAGE_SCHEMA,
            images_b64=[b64],
        )
        page_confidence = max(0.0, min(1.0, float(result["confidence"])))
        pages.append(PageDto(pageNumber=i + 1, text=result["text"], confidence=page_confidence))
        confidences.append(page_confidence)

    images.extend(_extract_meaningful_images(doc))

    full_text = "\n".join(p.text for p in pages)
    overall_confidence = sum(confidences) / len(confidences) if confidences else 0.5
    summary = _summarize(full_text, "SCANNED_HANDWRITTEN")

    return PdfProcessResponse(
        flavor="SCANNED_HANDWRITTEN",
        detectedLanguage=None,
        pageCount=page_count,
        pages=pages,
        fullText=full_text,
        tables=tables,
        images=images,
        summary=summary,
        confidence=round(overall_confidence, 3),
    )


def _process_non_english(doc, file_path: str, raw_pages_text: list[str], page_count: int, lang: str) -> PdfProcessResponse:
    settings = get_settings()
    pages: list[PageDto] = []
    for i, text in enumerate(raw_pages_text):
        translated = call_text(
            model=settings.text_model,
            system=TRANSLATE_SYSTEM_PROMPT,
            user_text=f"Translate this page {i + 1} text to English:\n\n{text}",
        )
        pages.append(PageDto(pageNumber=i + 1, text=text, translatedText=translated, confidence=TRANSLATION_CONFIDENCE))

    full_text = "\n".join(p.translatedText or "" for p in pages)
    tables = _extract_tables(file_path)
    images = _extract_meaningful_images(doc)
    summary = _summarize(full_text, "NON_ENGLISH")

    return PdfProcessResponse(
        flavor="NON_ENGLISH",
        detectedLanguage=lang,
        pageCount=page_count,
        pages=pages,
        fullText=full_text,
        tables=tables,
        images=images,
        summary=summary,
        confidence=TRANSLATION_CONFIDENCE,
    )


def _process_article(doc, file_path: str, raw_pages_text: list[str], page_count: int) -> PdfProcessResponse:
    settings = get_settings()
    pages = [PageDto(pageNumber=i + 1, text=t) for i, t in enumerate(raw_pages_text)]
    full_raw_text = "\n".join(raw_pages_text)
    case_extract = call_text(
        model=settings.text_model,
        system=ARTICLE_SYSTEM_PROMPT,
        user_text=f"Article text:\n\n{full_raw_text[:15000]}",
    )
    tables = _extract_tables(file_path)
    images = _extract_meaningful_images(doc)
    summary = _summarize(case_extract, "ARTICLE")

    return PdfProcessResponse(
        flavor="ARTICLE",
        detectedLanguage="en",
        pageCount=page_count,
        pages=pages,
        fullText=case_extract,
        tables=tables,
        images=images,
        summary=summary,
        confidence=0.8,
    )


def _process_digital(doc, file_path: str, raw_pages_text: list[str], page_count: int) -> PdfProcessResponse:
    pages = [PageDto(pageNumber=i + 1, text=t) for i, t in enumerate(raw_pages_text)]
    full_text = "\n".join(raw_pages_text)
    tables = _extract_tables(file_path)
    images = _extract_meaningful_images(doc)
    summary = _summarize(full_text, "DIGITAL")

    return PdfProcessResponse(
        flavor="DIGITAL",
        detectedLanguage="en",
        pageCount=page_count,
        pages=pages,
        fullText=full_text,
        tables=tables,
        images=images,
        summary=summary,
        confidence=0.95,
    )


def _looks_like_article(doc, raw_pages_text: list[str]) -> bool:
    combined_lower = " ".join(raw_pages_text).lower()
    keywords = ["abstract", "references", "doi:", "journal of", "et al.", "case report", "introduction"]
    keyword_hits = sum(1 for k in keywords if k in combined_lower)
    return keyword_hits >= 2 or _detect_multi_column(doc)


def _detect_multi_column(doc) -> bool:
    multi_col_pages = 0
    total_checked = 0
    for page in doc:
        blocks = [b for b in page.get_text("blocks") if b[4] and b[4].strip()]
        if len(blocks) < 4:
            continue
        total_checked += 1
        width = page.rect.width
        left = sum(1 for b in blocks if b[0] < width * 0.48)
        right = sum(1 for b in blocks if b[0] > width * 0.52)
        if left >= 3 and right >= 3:
            multi_col_pages += 1
    if total_checked == 0:
        return False
    return (multi_col_pages / total_checked) >= 0.4


def _extract_tables(file_path: str) -> list[TableDto]:
    tables: list[TableDto] = []
    try:
        with pdfplumber.open(file_path) as pdf:
            for i, page in enumerate(pdf.pages):
                for raw_table in page.extract_tables():
                    if not raw_table or len(raw_table) < 2:
                        continue
                    headers = [c or "" for c in raw_table[0]]
                    rows = [[c or "" for c in row] for row in raw_table[1:]]
                    tables.append(TableDto(pageNumber=i + 1, headers=headers, rows=rows))
    except Exception:
        logger.exception("Table extraction failed for %s", file_path)
    return tables


def _extract_meaningful_images(doc) -> list[ImageDto]:
    settings = get_settings()
    images: list[ImageDto] = []
    for i in range(doc.page_count):
        page = doc[i]
        for img in page.get_images(full=True):
            xref = img[0]
            try:
                base_image = doc.extract_image(xref)
            except Exception:
                continue
            width, height = base_image.get("width", 0), base_image.get("height", 0)
            if width < settings.meaningful_image_min_px or height < settings.meaningful_image_min_px:
                continue
            try:
                im = Image.open(io.BytesIO(base_image["image"])).convert("RGB")
                buf = io.BytesIO()
                im.save(buf, format="PNG")
                b64 = png_bytes_to_b64(buf.getvalue())
            except Exception:
                logger.exception("Failed to normalize embedded image on page %d", i + 1)
                continue

            result = call_tool(
                model=settings.vision_model,
                system=IMAGE_DESC_SYSTEM_PROMPT,
                user_text="Describe this image from a pharmacovigilance document.",
                tool_name="emit_image_description",
                tool_description="Report a short description and whether a human should review this image directly.",
                input_schema=IMAGE_DESC_SCHEMA,
                images_b64=[b64],
            )
            images.append(ImageDto(
                pageNumber=i + 1,
                description=result["description"],
                flaggedForReview=bool(result.get("flaggedForReview", True)),
            ))
    return images


def _summarize(full_text: str, flavor: str) -> str:
    settings = get_settings()
    if not full_text.strip():
        return "No extractable text was found in this document."
    return call_text(
        model=settings.text_model,
        system=SUMMARY_SYSTEM_PROMPT,
        user_text=(
            f"Document type detected: {flavor}.\n\n"
            f"Document text:\n\n{full_text[:8000]}\n\n"
            "Write a 10-15 sentence summary for a human reviewer. Clearly state whether "
            "this document looks relevant to a patient-safety/quality-complaint/info-request "
            "review, and why."
        ),
    )
