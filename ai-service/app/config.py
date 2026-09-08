from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    azure_openai_api_key: str = ""
    azure_openai_endpoint: str = ""
    azure_openai_api_version: str = "2024-12-01-preview"
    # gpt-4o-mini is multimodal, so the same deployment serves both text and
    # vision calls; kept as two settings for symmetry/future flexibility.
    azure_openai_deployment: str = "gpt-4o-mini"

    max_output_tokens: int = 4096

    # Heuristic thresholds for PDF flavor detection.
    scanned_min_chars_per_page: int = 30
    meaningful_image_min_px: int = 80

    @property
    def text_model(self) -> str:
        return self.azure_openai_deployment

    @property
    def vision_model(self) -> str:
        return self.azure_openai_deployment


@lru_cache
def get_settings() -> Settings:
    return Settings()
