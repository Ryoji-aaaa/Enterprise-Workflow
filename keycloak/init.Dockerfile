FROM curlimages/curl:8.21.0

USER root
RUN apk add --no-cache jq
USER curl_user
