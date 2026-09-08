"""
Section 4 bonus: literature screening. Given an independently-uploaded article
PDF (already text-extracted by pdf_processing.process_pdf), identifies every
distinct real, identifiable patient case described - an article may describe
zero, one, or several - and for each one, judges whether it's actually worth
reporting (vs. general discussion/review with no specific patient) and
extracts the same evidence fields Section 3 uses for ICSR, scoped per case.

Two-phase design, not one call: asking a single call to both enumerate every
case AND produce a full multi-field extraction for each one, in one nested
JSON array, empirically caused gpt-4o-mini to fully detail the first case and
then stop (finish_reason="stop", well under the token budget - it wasn't
truncation, it just didn't continue). Splitting into a lightweight
"identify every case" call followed by one focused "extract this one case's
fields" call per relevant case fixed it - each call only has to do one
straightforward thing.
"""
from __future__ import annotations

from app.llm_client import call_tool
from app.config import get_settings
from app.schemas import ExtractedFieldResult, LiteratureCaseResult, ScreenLiteratureRequest, ScreenLiteratureResponse

CASE_ID_SYSTEM_PROMPT = """You are an assistant for a pharmacovigilance literature-screening team. You read the \
text of a published article (page numbers marked) and identify every distinct REAL, IDENTIFIABLE patient case it \
describes - a single article can describe zero, one, or several separate cases.

Scan the entire article text and count the cases: look for explicit markers like "Case 1" / "Case 2", "Patient \
A" / "Patient B", "the first patient" / "the second patient", "another patient," or simply a new patient (new \
age/sex/history) being introduced partway through the case-report section. List EVERY one you find, in order - \
do not stop after the first.

For each case, judge whether it is actually worth reporting:
   - isRelevantCase = true only for a real, identifiable patient case with enough concrete clinical detail \
(a specific patient, what happened to them, a specific product) to be a reportable safety case.
   - isRelevantCase = false for anything that is NOT a specific patient case: general discussion, a literature \
review, aggregate statistics, a hypothetical/illustrative example, or background information. Still report these \
as a case entry (so a reviewer can see what was considered and why it was excluded) with a clear relevanceReason.

If the article describes no patient-case-like content at all (e.g. a pure methods/statistics paper), return an \
empty cases list.

Give every case a confidence score (0.0-1.0) for your isRelevantCase judgment, a one-sentence relevanceReason \
stated plainly without hedging, and a short summary (2-4 sentences) of that specific case.

You will be given the article's text below, with page numbers marked."""

CASE_ID_SCHEMA = {
    "type": "object",
    "properties": {
        "cases": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "caseNumber": {"type": "integer", "description": "1-based, in the order cases appear in the article"},
                    "isRelevantCase": {"type": "boolean"},
                    "relevanceReason": {"type": "string"},
                    "confidence": {"type": "number"},
                    "summary": {"type": "string"},
                },
                "required": ["caseNumber", "isRelevantCase", "relevanceReason", "confidence", "summary"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["cases"],
    "additionalProperties": False,
}

CASE_FIELDS_SYSTEM_PROMPT = """You extract structured evidence fields for ONE SPECIFIC patient case within an \
article that may describe multiple cases. Focus ONLY on the case identified below (by its case number and \
summary) - if the article describes other patient cases too, ignore them completely; another call handles those \
separately.

Extract one row per field below. If a detail is not mentioned for THIS case, set fieldValue to exactly \
"Not stated" - never guess, and never pull in a detail that belongs to a different case in the same article.
     fieldGroup=PATIENT: fieldName in {age, sex, weight_height, relevant_history}
     fieldGroup=REPORTER: fieldName in {reporter_name_or_role, reporter_role, reporter_country}
     fieldGroup=PRODUCT: fieldName in {product_name, dose, route, start_date, stop_date}
     fieldGroup=REACTION: fieldName in {reaction_description, onset, outcome}
     fieldGroup=SEVERITY: fieldName in {serious, seriousness_criteria}
     fieldGroup=NARRATIVE: fieldName=case_narrative (a short plain-language summary you write, 2-4 sentences, \
about this case only)

GROUND RULES: say "Not stated" rather than guessing; give a confidence score for every extracted field; every \
field must cite sourceType=PDF_PAGE and sourceRef="page N" for the page this specific detail came from."""

CASE_FIELDS_SCHEMA = {
    "type": "object",
    "properties": {
        "fields": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "fieldGroup": {
                        "type": "string",
                        "enum": ["PATIENT", "REPORTER", "PRODUCT", "REACTION", "SEVERITY", "NARRATIVE"],
                    },
                    "fieldName": {"type": "string"},
                    "fieldValue": {"type": "string"},
                    "confidence": {"type": ["number", "null"]},
                    "sourceType": {"type": "string", "enum": ["EMAIL_BODY", "PDF_PAGE"]},
                    "sourceRef": {"type": ["string", "null"]},
                    "attachmentId": {"type": ["integer", "null"]},
                },
                "required": ["fieldGroup", "fieldName", "fieldValue", "confidence", "sourceType", "sourceRef", "attachmentId"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["fields"],
    "additionalProperties": False,
}


def screen_literature(request: ScreenLiteratureRequest) -> ScreenLiteratureResponse:
    settings = get_settings()
    article_text = _build_context(request)

    id_result = call_tool(
        model=settings.text_model,
        system=CASE_ID_SYSTEM_PROMPT,
        user_text=article_text,
        tool_name="emit_case_list",
        tool_description="List every distinct patient case found in the article, with a relevance verdict for each.",
        input_schema=CASE_ID_SCHEMA,
        max_tokens=2048,
        temperature=0,
    )

    cases: list[LiteratureCaseResult] = []
    for stub in id_result.get("cases", []):
        fields: list[ExtractedFieldResult] = []
        if stub["isRelevantCase"]:
            fields = _extract_case_fields(article_text, stub, settings.text_model)
        cases.append(LiteratureCaseResult(
            caseNumber=stub["caseNumber"],
            isRelevantCase=stub["isRelevantCase"],
            relevanceReason=stub["relevanceReason"],
            confidence=stub["confidence"],
            summary=stub["summary"],
            extractedFields=fields,
        ))
    return ScreenLiteratureResponse(cases=cases)


def _extract_case_fields(article_text: str, stub: dict, model: str) -> list[ExtractedFieldResult]:
    user_text = (
        f"Focus only on Case {stub['caseNumber']}: {stub['summary']}\n\n"
        f"Full article text (extract fields for Case {stub['caseNumber']} only):\n\n{article_text}"
    )
    result = call_tool(
        model=model,
        system=CASE_FIELDS_SYSTEM_PROMPT,
        user_text=user_text,
        tool_name="emit_case_fields",
        tool_description=f"Report the extracted evidence fields for case {stub['caseNumber']} only.",
        input_schema=CASE_FIELDS_SCHEMA,
        max_tokens=2048,
        temperature=0,
    )
    return [ExtractedFieldResult(**f) for f in result.get("fields", [])]


def _build_context(request: ScreenLiteratureRequest) -> str:
    parts = [f"ARTICLE (attachmentId={request.attachmentId}, file={request.filename})"]
    for page in request.pages:
        parts.append(f"--- page {page.pageNumber} ---")
        parts.append(page.text[:6000])
    return "\n".join(parts)[:30000]
