from fastapi import FastAPI
from api.routes.health import router as health_router
from api.routes.search import router as search_router
from api.routes.admin import router as admin_router
from core.worker_authority import install_guard

app=FastAPI(title="multimodal-search-service")
install_guard(app,"search")
app.include_router(health_router)
app.include_router(search_router,prefix="/internal/v1")
app.include_router(admin_router,prefix="/internal/v1")
