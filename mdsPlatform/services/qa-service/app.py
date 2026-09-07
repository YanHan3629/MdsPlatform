import logging

from fastapi import FastAPI

from api.routes.health import router as health_router
from api.routes.qa import router as qa_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(title="multimodal-qa-service", version="1.0.0")

app.include_router(health_router)
app.include_router(qa_router, prefix="/api/v1")
