ARG QA_BASE_IMAGE=python:3.12-slim
FROM ${QA_BASE_IMAGE} AS runtime
WORKDIR /app
ARG INSTALL_PYTHON_PACKAGES=1
COPY requirements.txt /app/requirements.txt
RUN if [ "$INSTALL_PYTHON_PACKAGES" = "1" ]; then pip install --no-cache-dir -r /app/requirements.txt; fi
# .dockerignore excludes model weights; vLLM is managed by the root compose.yml.
COPY . /app
EXPOSE 18081
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18081"]

FROM runtime AS final
