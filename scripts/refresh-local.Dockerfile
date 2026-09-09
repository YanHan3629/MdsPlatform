# Offline refresh using existing dependency-complete local images.
# Normal builds use the service Dockerfiles.
FROM backend-sirius:latest AS backend
WORKDIR /app
COPY mdsPlatform/services/backend/target/sirius-backend-1.0-SNAPSHOT.jar /app/sirius-backend.jar
ENTRYPOINT ["java", "-jar", "/app/sirius-backend.jar"]

FROM sirius-mm-qa-service:baseline AS qa
WORKDIR /app
COPY mdsPlatform/services/qa-service/app.py mdsPlatform/services/qa-service/requirements.txt ./
COPY mdsPlatform/services/qa-service/core ./core
COPY mdsPlatform/services/qa-service/adapters ./adapters
COPY mdsPlatform/services/qa-service/services ./services
COPY mdsPlatform/services/qa-service/api ./api
COPY mdsPlatform/services/qa-service/schemas ./schemas
COPY mdsPlatform/services/qa-service/retriever ./retriever
COPY mdsPlatform/services/qa-service/scripts ./scripts
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18081"]
