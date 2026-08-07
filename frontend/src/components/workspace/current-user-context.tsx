"use client";

import { createContext, use, type ReactNode } from "react";

import type { CurrentUser } from "@/lib/backend-api";

const CurrentUserContext = createContext<CurrentUser | null>(null);

export function CurrentUserProvider({
  children,
  user,
}: {
  children: ReactNode;
  user: CurrentUser;
}) {
  return (
    <CurrentUserContext.Provider value={user}>
      {children}
    </CurrentUserContext.Provider>
  );
}

export function useCurrentUser(): CurrentUser {
  const user = use(CurrentUserContext);
  if (!user) {
    throw new Error("useCurrentUser must be used within CurrentUserProvider.");
  }
  return user;
}
