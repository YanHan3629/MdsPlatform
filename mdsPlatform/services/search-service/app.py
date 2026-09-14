import secrets

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from api.routes.health import router as health_router
from api.routes.search import router as search_router
from api.routes.admin import router as admin_router
from core.config import settings

app = FastAPI(title="multimodal-search-service")


@app.middleware("http")
async def require_internal_token(request: Request, call_next):
    if request.url.path.startswith("/internal/"):
        supplied = request.headers.get("X-MM-Internal-Token", "")
        if not settings.mm_internal_token or not secrets.compare_digest(settings.mm_internal_token, supplied):
            return JSONResponse(status_code=401, content={"detail": "invalid internal service credential"})
    return await call_next(request)


app.include_router(health_router)
app.include_router(search_router, prefix="/internal/v1")
app.include_router(admin_router, prefix="/internal/v1")
