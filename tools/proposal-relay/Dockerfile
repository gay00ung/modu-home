FROM python:3.14-slim@sha256:cd89c30eda26ee911a4611b18680086e0ea42e6f5f8cbe653d07fa7958c9cec8

WORKDIR /app
COPY --chown=65532:65532 relay.py /app/relay.py
COPY --chown=65532:65532 dropoff_web /app/dropoff_web
USER 65532:65532
ENTRYPOINT ["python", "-B", "/app/relay.py"]
