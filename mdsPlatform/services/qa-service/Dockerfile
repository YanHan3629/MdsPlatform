ARG QA_BASE_IMAGE=python:3.12-slim
FROM ${QA_BASE_IMAGE}
WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
# .dockerignore excludes model weights; vLLM is managed by the root compose.yml.
COPY . /app
EXPOSE 18081
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18081"]
