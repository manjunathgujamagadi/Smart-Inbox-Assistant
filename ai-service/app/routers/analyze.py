from fastapi import APIRouter

from app.classification import analyze_message
from app.schemas import AnalyzeMessageRequest, AnalyzeMessageResponse

router = APIRouter(prefix="/ai", tags=["analyze"])


@router.post("/analyze-message", response_model=AnalyzeMessageResponse)
def analyze_message_endpoint(req: AnalyzeMessageRequest) -> AnalyzeMessageResponse:
    return analyze_message(req)
