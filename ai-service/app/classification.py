"""
Message classification + category-specific structured extraction (Section 3C/3D).
One model call, forced into a structured tool response, produces both the
multi-label classification and the field-level extraction so the model reasons
about them together (the fields it extracts are evidence for the category it
assigned).
"""
from __future__ import annotations

import re

from app.llm_client import call_tool
from app.config import get_settings
from app.schemas import (
    AnalyzeMessageRequest,
    AnalyzeMessageResponse,
    ClassificationResult,
    ExtractedFieldResult,
)

ANALYSIS_SYSTEM_PROMPT = """You are an assistant for a pharmacovigilance intake team at a pharmaceutical \
company. You read an incoming email (plus the text of any processed PDF attachments) and do two things.

1. CLASSIFY the message into one or more of these four categories. A message can belong to more than one \
category at once (e.g. a bad reaction caused by a defective product is both ICSR and PQC) - do not force a \
single label. If nothing relevant applies, classify as NOT_RELEVANT alone.

   Only assign a category when the message contains concrete, explicit evidence for it - never because the \
general subject matter is thematically related, or because the same product happens to come up elsewhere. \
When you're on the fence about a marginal secondary category, leave it out rather than include it at low \
confidence.

   - ICSR (Safety Report): a patient had a bad reaction to a drug. Look for all four elements, even loosely \
stated: a specific patient, a specific person reporting it, a specific drug, and a bad outcome. This applies \
equally whether the report reaches you directly from a patient/clinician or via a shared/published case report -  \
a real patient case described in a forwarded article is still a safety report, not merely "sharing literature."
   - PQC (Quality Complaint): something is physically wrong with the product itself - broken seal, wrong \
color, contamination, damaged packaging, counterfeit. Assign this only if the message names a concrete defect \
(and ideally a batch/lot number); a product simply being involved in an unrelated adverse reaction is not, by \
itself, a quality complaint.
   - MI (Info Request): someone in the message explicitly asks a question about a product (dosing, how to \
take it, interactions) with NO bad reaction and NO defect mentioned. MI therefore never accompanies ICSR or PQC: \
a question asked inside a safety report or complaint (e.g. "should we be worried?") is part of that report, not \
a separate info request. Assign this only if the message contains a \
literal interrogative sentence (typically ending in "?") or an unambiguous explicit request such as "please \
advise" / "can you tell me" / "should we be worried" - actually written in the source, not something you infer \
the sender might want to know. Simply forwarding, describing, or discussing a case - even one that implicitly \
invites follow-up review or touches on drug-safety information generally - is NOT a question. A report that \
only describes what happened to a patient is not an information request just because more information could, \
in principle, be useful to someone. Never invent a question that was not actually asked: every question_N field \
you extract for MI must quote the actual question verbatim from the source.
   - NOT_RELEVANT: anything else - marketing, spam, internal admin chatter.

   HARD CONSTRAINT, check this before you answer: NOT_RELEVANT can never appear in the same classifications \
list as ICSR, PQC, or MI - not even at low confidence "just in case." These are mutually exclusive by \
definition: NOT_RELEVANT means none of the other three apply. If your classifications list contains ICSR, PQC, \
or MI, it must not also contain a NOT_RELEVANT entry; if you find yourself wanting to add one anyway, that is a \
signal you should reconsider whether the other category truly applies instead of adding both.

   SELF-CONSISTENCY CHECK, apply this to every category before including it: your one-line `reason` must be a \
confident positive statement of the evidence, not a hedge. If the reason you'd write contains phrases like "no \
specific X is mentioned," "not a direct Y," "could indicate," "more of a Z than a W," or similar hedging - that \
is the evidence telling you this category does not apply. Do not include it "with a caveat"; leave it out of \
the classifications list entirely. A category belongs in the list only if you can state its evidence plainly \
and without qualification. The same applies to fields: if you cannot fill an extracted field with a real value \
or the literal string "Not stated," that is a sign the category it belongs to shouldn't have been assigned at \
all - go back and remove it rather than writing a hedge like "not applicable" or "unclear."

   For every category you assign, give a confidence score from 0.0 to 1.0 and a one-line reason. Calibrate it: \
0.95-1.0 only when every defining element is stated explicitly (for ICSR: patient, reporter, drug and bad \
outcome all present); 0.7-0.9 when one element is only loosely or indirectly stated; 0.5-0.7 when the \
evidence is thin but still points to this category.

2. EXTRACT structured facts, but only for the categories you actually assigned:

   If ICSR is assigned, extract one row per field below. If a detail is not mentioned anywhere, set \
fieldValue to exactly "Not stated" - never guess or infer a plausible-sounding value.
     fieldGroup=PATIENT: fieldName in {age, sex, weight_height, relevant_history}
     fieldGroup=REPORTER: fieldName in {reporter_name_or_role, reporter_role, reporter_country}
     fieldGroup=PRODUCT: fieldName in {product_name, dose, route, start_date, stop_date}
     fieldGroup=REACTION: fieldName in {reaction_description, onset, outcome}
     fieldGroup=SEVERITY: fieldName in {serious, seriousness_criteria}
     fieldGroup=NARRATIVE: fieldName=case_narrative (a short plain-language case summary you write, 2-4 \
sentences; give your own confidence in its accuracy)

   If PQC is assigned, extract:
     fieldGroup=PQC: fieldName in {product_name, batch_lot_number, defect_description, photo_mentioned} \
(photo_mentioned is "yes", "no", or "Not stated")

   If MI is assigned, extract:
     fieldGroup=MI: fieldName=question_1, question_2, ... (one row per distinct question asked), and \
fieldName=product_or_topic

3. GROUND RULES:
   - Say "Not stated" rather than guessing. Give a confidence score for every extracted field, calibrated as \
follows - do not default to 1.0:
       0.95-1.0  the value is written verbatim and unambiguously in the source.
       0.75-0.9  you normalised, paraphrased or combined statements (e.g. "since Monday" -> a date), or wrote \
the narrative yourself.
       0.5-0.7   the value is implied by context, or the source wording is ambiguous.
       below 0.5 you are unsure the reading is right.
     For "Not stated": 0.9+ only when the source is a structured form or clearly covers that topic without \
giving the value; 0.6-0.8 when the source is short or informal and the value may simply have been left out.
     Pages marked with a reading confidence (OCR of handwriting, or machine translation) cap the confidence of \
every field taken from them - never exceed that page's reading confidence.
   - Every field must cite where it came from. Set sourceType to EMAIL_BODY or PDF_PAGE. When sourceType is \
PDF_PAGE, set attachmentId to that attachment's id (given below) and sourceRef to "page N". When sourceType \
is EMAIL_BODY, you may omit attachmentId and sourceRef.
   - Do not extract ICSR/PQC/MI fields for a category you did not assign.

You will be given the email content and the text of any processed PDF attachments below, with page numbers \
marked."""

ANALYSIS_SCHEMA = {
    "type": "object",
    "properties": {
        "classifications": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "category": {"type": "string", "enum": ["ICSR", "PQC", "MI", "NOT_RELEVANT"]},
                    "confidence": {"type": "number"},
                    "reason": {"type": "string"},
                },
                "required": ["category", "confidence", "reason"],
                "additionalProperties": False,
            },
        },
        "extractedFields": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "fieldGroup": {
                        "type": "string",
                        "enum": ["PATIENT", "REPORTER", "PRODUCT", "REACTION", "SEVERITY", "NARRATIVE", "PQC", "MI"],
                    },
                    "fieldName": {"type": "string"},
                    "fieldValue": {"type": "string"},
                    # Strict mode requires every property in `required`; fields that
                    # are conceptually optional are typed nullable instead (e.g. a
                    # narrative field the model wrote itself has no natural sourceRef).
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
    "required": ["classifications", "extractedFields"],
    "additionalProperties": False,
}


def analyze_message(request: AnalyzeMessageRequest) -> AnalyzeMessageResponse:
    settings = get_settings()
    user_text = _build_context(request)
    result = call_tool(
        model=settings.text_model,
        system=ANALYSIS_SYSTEM_PROMPT,
        user_text=user_text,
        tool_name="emit_analysis",
        tool_description="Report the message classification(s) and the structured fields extracted for each.",
        input_schema=ANALYSIS_SCHEMA,
        max_tokens=4096,
        # Classification is a judgment call on borderline multi-label cases (e.g. does
        # a case-report email also count as an info request?) where a temperature=1
        # default produces visible run-to-run flip-flopping in testing; 0 favors the
        # model's single most-confident reading instead. Reasoning models (gpt-5-mini) only
        # accept the default temperature, so llm_client drops this for them; the Section 2
        # rules enforced below keep the categories consistent either way.
        temperature=0,
    )
    classifications = _enforce_category_rules(
        [ClassificationResult(**c) for c in result.get("classifications", [])], request
    )
    assigned = {c.category for c in classifications}
    fields = [
        _cap_confidence(f, request)
        for f in (ExtractedFieldResult(**f) for f in result.get("extractedFields", []))
        if FIELD_GROUP_CATEGORY.get(f.fieldGroup) in assigned
    ]
    return AnalyzeMessageResponse(classifications=classifications, extractedFields=fields)


# Which category each extracted field group belongs to.
FIELD_GROUP_CATEGORY = {
    "PATIENT": "ICSR", "REPORTER": "ICSR", "PRODUCT": "ICSR", "REACTION": "ICSR",
    "SEVERITY": "ICSR", "NARRATIVE": "ICSR", "PQC": "PQC", "MI": "MI",
}

# Phrases that count as an explicit request for information when no "?" is present.
_REQUEST_PHRASES = ("please advise", "can you tell", "could you tell", "please confirm", "please let me know",
                    "i would like to know", "we would like to know", "kindly advise", "please clarify")


def _enforce_category_rules(
    classifications: list[ClassificationResult], request: AnalyzeMessageRequest
) -> list[ClassificationResult]:
    """Applies the Section 2 definitions deterministically, so a prompt slip can't break them."""
    # The model occasionally repeats a category (e.g. once per attached PDF); a message belongs
    # to each bucket at most once, so keep the most confident entry per category.
    best: dict[str, ClassificationResult] = {}
    for c in classifications:
        c.confidence = max(0.0, min(1.0, c.confidence))
        if c.confidence <= 0:
            continue  # a 0-confidence label is the model saying "not this one"
        if c.category not in best or c.confidence > best[c.category].confidence:
            best[c.category] = c

    # MI means a question with no bad reaction and no defect.
    if "MI" in best and ("ICSR" in best or "PQC" in best):
        del best["MI"]
    # MI needs a question that is actually written in the source.
    if "MI" in best and not _has_explicit_question(request):
        del best["MI"]
    # NOT_RELEVANT means none of the other three apply.
    if best.keys() - {"NOT_RELEVANT"}:
        best.pop("NOT_RELEVANT", None)
    if not best:
        best["NOT_RELEVANT"] = ClassificationResult(
            category="NOT_RELEVANT",
            confidence=0.5,
            reason="No explicit safety, quality or information-request evidence was found.",
        )
    return list(best.values())


def _has_explicit_question(request: AnalyzeMessageRequest) -> bool:
    texts = [request.message.subject or "", request.message.bodyText or ""]
    texts += [p.text for a in request.attachments for p in a.pages]
    combined = "\n".join(texts).lower()
    return "?" in combined or any(p in combined for p in _REQUEST_PHRASES)


def _cap_confidence(field: ExtractedFieldResult, request: AnalyzeMessageRequest) -> ExtractedFieldResult:
    """A fact can't be more certain than the reading of the page it came from (OCR / translation)."""
    if field.sourceType != "PDF_PAGE" or field.confidence is None or field.attachmentId is None:
        return field
    page_no = _page_number(field.sourceRef)
    for att in request.attachments:
        if att.attachmentId != field.attachmentId:
            continue
        page_conf = [p.confidence for p in att.pages if p.confidence is not None and (page_no is None or p.pageNumber == page_no)]
        if page_conf:
            field.confidence = round(min(field.confidence, min(page_conf)), 3)
    return field


def _page_number(source_ref: str | None) -> int | None:
    match = re.search(r"\d+", source_ref or "")
    return int(match.group()) if match else None


def _build_context(request: AnalyzeMessageRequest) -> str:
    parts = [
        "EMAIL",
        f"From: {request.message.sender}",
        f"Subject: {request.message.subject}",
        f"Received: {request.message.receivedAt}",
        "Body:",
        request.message.bodyText or "(empty)",
    ]
    for att in request.attachments:
        parts.append(f"\nATTACHMENT (attachmentId={att.attachmentId}, file={att.filename}, flavor={att.flavor})")
        if att.summary:
            parts.append(f"Summary: {att.summary}")
        for page in att.pages:
            marker = f" (reading confidence {page.confidence:.2f})" if page.confidence is not None else ""
            parts.append(f"--- page {page.pageNumber}{marker} ---")
            parts.append(page.text[:4000])
    text = "\n".join(parts)
    return text[:30000]
