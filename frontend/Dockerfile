ARG NODE_VERSION=24.18.0

FROM node:${NODE_VERSION}-alpine AS dependencies
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS test
COPY . .
RUN BETTER_AUTH_URL=http://localhost:3000 \
    BETTER_AUTH_SECRET=build-only-placeholder-at-least-32-characters \
    BACKEND_INTERNAL_URL=http://backend:8080 \
    KEYCLOAK_ISSUER=http://localhost:8180/realms/workflow \
    KEYCLOAK_INTERNAL_URL=http://keycloak:8080 \
    KEYCLOAK_REALM=workflow \
    KEYCLOAK_CLIENT_ID=workflow-web \
    KEYCLOAK_CLIENT_SECRET=build-only-placeholder \
    NEXT_TELEMETRY_DISABLED=1 \
    npm run check

FROM dependencies AS build
WORKDIR /workspace
COPY . .
RUN BETTER_AUTH_URL=http://localhost:3000 \
    BETTER_AUTH_SECRET=build-only-placeholder-at-least-32-characters \
    BACKEND_INTERNAL_URL=http://backend:8080 \
    KEYCLOAK_ISSUER=http://localhost:8180/realms/workflow \
    KEYCLOAK_INTERNAL_URL=http://keycloak:8080 \
    KEYCLOAK_REALM=workflow \
    KEYCLOAK_CLIENT_ID=workflow-web \
    KEYCLOAK_CLIENT_SECRET=build-only-placeholder \
    NEXT_TELEMETRY_DISABLED=1 \
    npm run build

FROM node:${NODE_VERSION}-alpine AS runtime
WORKDIR /workspace
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=build /workspace/public ./public
COPY --from=build /workspace/.next/standalone ./
COPY --from=build /workspace/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
