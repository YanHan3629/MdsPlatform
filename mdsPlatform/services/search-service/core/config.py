from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "multimodal-search-service"
    model_path: str = "clip_model_chinese"
    local_cache_dir: str = "/tmp/mm-search-cache"
    backend_base_url: str = "http://localhost:8888"
    backend_bearer_token: str = ""
    request_timeout_seconds: int = 120


settings = Settings()
