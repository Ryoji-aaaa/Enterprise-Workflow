ARG NODE_VERSION=24.18.0
ARG PLAYWRIGHT_VERSION=1.62.0

FROM node:${NODE_VERSION}-bookworm-slim
ARG PLAYWRIGHT_VERSION
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
RUN test "$(node -p "require('@playwright/test/package.json').version")" = "${PLAYWRIGHT_VERSION}"
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN npx playwright install --with-deps chromium
RUN chmod -R a+rX "${PLAYWRIGHT_BROWSERS_PATH}"
COPY . .
CMD ["npm", "test"]
