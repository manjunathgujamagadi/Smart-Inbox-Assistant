from fastapi import APIRouter

from app.literature_screening import screen_literature
from app.schemas import ScreenLiteratureRequest, ScreenLiteratureResponse

router = APIRouter(prefix="/ai", tags=["literature"])


@router.post("/screen-literature", response_model=ScreenLiteratureResponse)
def screen_literature_endpoint(req: ScreenLiteratureRequest) -> ScreenLiteratureResponse:
    return screen_literature(req)
