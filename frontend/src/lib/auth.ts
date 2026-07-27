import { betterAuth } from "better-auth";
import { nextCookies } from "better-auth/next-js";
import { genericOAuth } from "better-auth/plugins";

import { serverEnvironment } from "@/lib/environment";

const keycloakRealmUrl =
  `${serverEnvironment.keycloakInternalUrl}/realms/${serverEnvironment.keycloakRealm}`;
const secureCookies = process.env.NODE_ENV === "production";
const rateLimitEnabled =
  process.env.BETTER_AUTH_RATE_LIMIT_ENABLED !== "false";

export const auth = betterAuth({
  appName: process.env.NEXT_PUBLIC_APP_NAME ?? "ワークフローシステム",
  baseURL: serverEnvironment.betterAuthUrl,
  secret: serverEnvironment.betterAuthSecret,
  rateLimit: {
    enabled: rateLimitEnabled,
  },
  trustedOrigins: [serverEnvironment.betterAuthUrl],
  emailAndPassword: {
    enabled: false,
  },
  session: {
    expiresIn: 60 * 60 * 8,
    cookieCache: {
      enabled: true,
      maxAge: 60 * 15,
      strategy: "jwe",
      refreshCache: true,
    },
  },
  account: {
    storeStateStrategy: "cookie",
    storeAccountCookie: true,
  },
  advanced: {
    useSecureCookies: secureCookies,
    defaultCookieAttributes: {
      httpOnly: true,
      sameSite: "lax",
      secure: secureCookies,
      path: "/",
    },
  },
  plugins: [
    genericOAuth({
      config: [
        {
          providerId: "keycloak",
          clientId: serverEnvironment.keycloakClientId,
          clientSecret: serverEnvironment.keycloakClientSecret,
          issuer: serverEnvironment.keycloakIssuer,
          authorizationUrl:
            `${serverEnvironment.keycloakIssuer}/protocol/openid-connect/auth`,
          tokenUrl: `${keycloakRealmUrl}/protocol/openid-connect/token`,
          userInfoUrl: `${keycloakRealmUrl}/protocol/openid-connect/userinfo`,
          scopes: ["openid", "profile", "email"],
          pkce: true,
          authentication: "post",
        },
      ],
    }),
    nextCookies(),
  ],
});
