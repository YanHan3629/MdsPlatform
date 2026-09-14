ARG SEARCH_BASE_IMAGE=python:3.11-slim
FROM ${SEARCH_BASE_IMAGE}
WORKDIR /app
ARG INSTALL_PYTHON_PACKAGES=1
COPY requirements.txt /app/requirements.txt
RUN if [ "$INSTALL_PYTHON_PACKAGES" = "1" ]; then pip install --no-cache-dir -r /app/requirements.txt; fi
COPY . /app
ARG PREPARE_TEXT_MODEL=1
RUN if [ "$PREPARE_TEXT_MODEL" = "1" ]; then python /app/prepare_text_model.py; fi
EXPOSE 18080
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18080"]
