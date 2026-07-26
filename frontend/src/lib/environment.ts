function required(name: string): string {
  const value = process.env[name];

  if (!value) {
    throw new Error(`Required server environment variable ${name} is not set.`);
  }

  return value;
}

export const serverEnvironment = {
  betterAuthUrl: required("BETTER_AUTH_URL"),
  betterAuthSecret: required("BETTER_AUTH_SECRET"),
  backendInternalUrl: required("BACKEND_INTERNAL_URL").replace(/\/$/, ""),
  keycloakIssuer: required("KEYCLOAK_ISSUER").replace(/\/$/, ""),
  keycloakInternalUrl: required("KEYCLOAK_INTERNAL_URL").replace(/\/$/, ""),
  keycloakRealm: required("KEYCLOAK_REALM"),
  keycloakClientId: required("KEYCLOAK_CLIENT_ID"),
  keycloakClientSecret: required("KEYCLOAK_CLIENT_SECRET"),
} as const;
