FROM python:3.13-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    FORMFUSION_ML_DATA_ROOT=/data

WORKDIR /app

RUN addgroup --system formfusion && adduser --system --ingroup formfusion formfusion

COPY pyproject.toml README.md ./
COPY src ./src
RUN python -m pip install --upgrade pip && python -m pip install .
RUN mkdir -p /data && chown -R formfusion:formfusion /data

USER formfusion
EXPOSE 8100

CMD ["uvicorn", "src.api:app", "--host", "0.0.0.0", "--port", "8100", "--timeout-keep-alive", "10"]
