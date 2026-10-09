import re
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    azure_openai_api_key: str = ""
    azure_openai_endpoint: str = ""
    azure_openai_api_version: str = "2024-10-21"
    # The deployment is multimodal (gpt-5-mini / gpt-4o-mini), so it serves both text and
    # vision calls; kept as two settings for symmetry/future flexibility.
    azure_openai_deployment: str = "gpt-5-mini"

    max_output_tokens: int = 4096
    # Reasoning models (gpt-5*, o-series) spend part of the completion budget on hidden reasoning
    # tokens, so they get a larger budget or long structured answers come back truncated/empty.
    reasoning_output_tokens: int = 16000
    # None = auto-detect from the deployment name. Set true/false to override for custom names.
    azure_openai_reasoning_model: bool | None = None
    # Optional: minimal | low | medium | high. Left unset (model default) on purpose - "low"
    # was observed to skip reading images on gpt-5-mini, which breaks OCR.
    azure_openai_reasoning_effort: str | None = None

    # Heuristic thresholds for PDF flavor detection.
    scanned_min_chars_per_page: int = 30
    meaningful_image_min_px: int = 80

    @property
    def text_model(self) -> str:
        return self.azure_openai_deployment

    @property
    def vision_model(self) -> str:
        return self.azure_openai_deployment

    @property
    def is_reasoning_model(self) -> bool:
        """Reasoning models reject a custom temperature and the legacy max_tokens parameter."""
        if self.azure_openai_reasoning_model is not None:
            return self.azure_openai_reasoning_model
        return bool(re.search(r"gpt-5|(^|[^a-z])o[1-9]", self.azure_openai_deployment.lower()))


@lru_cache
def get_settings() -> Settings:
    return Settings()
