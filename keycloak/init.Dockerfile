FROM curlimages/curl:8.17.0

USER root
RUN apk add --no-cache jq
USER curl_user
