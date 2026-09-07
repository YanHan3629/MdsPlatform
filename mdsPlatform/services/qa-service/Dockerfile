# 多模态问答服务（FastAPI 包装层）
# 参考 Multimodel-DataSpace-Platform 的 vLLM 部署方式：
# Qwen3.5 由独立的 vLLM 容器提供（见 docker-compose.yml），本镜像不打包模型与推理框架。
ARG QA_BASE_IMAGE=python:3.12-slim
FROM ${QA_BASE_IMAGE}

WORKDIR /app

COPY requirements-service.txt .
RUN pip install --no-cache-dir -r requirements-service.txt

COPY app.py .
COPY log_config.json .
COPY core ./core
COPY adapters ./adapters
COPY services ./services
COPY api ./api
COPY schemas ./schemas
COPY scripts ./scripts

EXPOSE 18081

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18081", "--log-config", "log_config.json"]
