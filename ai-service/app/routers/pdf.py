from fastapi import APIRouter

from app.pdf_processing import process_pdf
from app.schemas import PdfProcessRequest, PdfProcessResponse

router = APIRouter(prefix="/ai", tags=["pdf"])


@router.post("/process-pdf", response_model=PdfProcessResponse)
def process_pdf_endpoint(req: PdfProcessRequest) -> PdfProcessResponse:
    return process_pdf(req.filePath)
