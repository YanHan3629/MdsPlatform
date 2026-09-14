ARG INDEX_BUILDER_BASE_IMAGE=python:3.11-slim
FROM ${INDEX_BUILDER_BASE_IMAGE}
WORKDIR /app
ARG INSTALL_SYSTEM_PACKAGES=1
RUN if [ "$INSTALL_SYSTEM_PACKAGES" = "1" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends curl bash file libcairo2 libgl1 fonts-dejavu-core \
      && rm -rf /var/lib/apt/lists/*; \
    fi
COPY requirements.txt /app/requirements.txt
ARG INSTALL_PYTHON_PACKAGES=1
RUN if [ "$INSTALL_PYTHON_PACKAGES" = "1" ]; then pip install --no-cache-dir -r /app/requirements.txt; fi
COPY . /app
ARG PREPARE_TEXT_MODEL=1
RUN if [ "$PREPARE_TEXT_MODEL" = "1" ]; then python /app/prepare_text_model.py; fi
CMD ["python", "/app/build_index.py"]
