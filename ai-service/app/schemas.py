from typing import Optional

from pydantic import BaseModel


# ---- /ai/process-pdf ----

class PdfProcessRequest(BaseModel):
    attachmentId: int
    filePath: str


class PageDto(BaseModel):
    pageNumber: int
    text: str
    translatedText: Optional[str] = None


class TableDto(BaseModel):
    pageNumber: int
    headers: list[str]
    rows: list[list[str]]


class ImageDto(BaseModel):
    pageNumber: int
    description: str
    flaggedForReview: bool = True


class PdfProcessResponse(BaseModel):
    flavor: str  # DIGITAL | SCANNED_HANDWRITTEN | ARTICLE | NON_ENGLISH
    detectedLanguage: Optional[str] = None
    pageCount: int
    pages: list[PageDto]
    fullText: str
    tables: list[TableDto] = []
    images: list[ImageDto] = []
    summary: str
    confidence: float


# ---- /ai/analyze-message ----

class MessageContext(BaseModel):
    sender: Optional[str] = None
    subject: Optional[str] = None
    bodyText: Optional[str] = None
    receivedAt: Optional[str] = None


class PageContext(BaseModel):
    pageNumber: int
    text: str


class AttachmentContext(BaseModel):
    attachmentId: int
    filename: str
    summary: Optional[str] = None
    flavor: Optional[str] = None
    pages: list[PageContext] = []


class AnalyzeMessageRequest(BaseModel):
    message: MessageContext
    attachments: list[AttachmentContext] = []


class ClassificationResult(BaseModel):
    category: str  # ICSR | PQC | MI | NOT_RELEVANT
    confidence: float
    reason: str


class ExtractedFieldResult(BaseModel):
    fieldGroup: str
    fieldName: str
    fieldValue: str
    confidence: Optional[float] = None
    sourceType: str  # EMAIL_BODY | PDF_PAGE
    sourceRef: Optional[str] = None
    attachmentId: Optional[int] = None


class AnalyzeMessageResponse(BaseModel):
    classifications: list[ClassificationResult]
    extractedFields: list[ExtractedFieldResult]


# ---- /ai/screen-literature (Section 4 bonus) ----

class ScreenLiteratureRequest(BaseModel):
    attachmentId: int
    filename: str
    pages: list[PageContext] = []


class LiteratureCaseResult(BaseModel):
    caseNumber: int
    isRelevantCase: bool
    relevanceReason: str
    confidence: float
    summary: str
    extractedFields: list[ExtractedFieldResult] = []


class ScreenLiteratureResponse(BaseModel):
    cases: list[LiteratureCaseResult]
