export type SynchronousMutationGuard = {
  tryStart: () => boolean;
  finish: () => void;
  isActive: () => boolean;
};

export function createSynchronousMutationGuard(): SynchronousMutationGuard {
  let active = false;
  return {
    tryStart() {
      if (active) return false;
      active = true;
      return true;
    },
    finish() {
      active = false;
    },
    isActive() {
      return active;
    },
  };
}
