ARG PYTHON_VERSION=3.14
FROM python:${PYTHON_VERSION}-alpine
WORKDIR /report
COPY aggregate.py test_aggregate.py ./
COPY fixtures ./fixtures
ENTRYPOINT ["python", "/report/aggregate.py"]
