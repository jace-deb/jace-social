"use client";
// Account switcher: the accounts you've signed in with on this device (and bots you own),
// so you can hop between them without signing in again. Tokens stay in this browser.
import { getToken, setToken } from "./client";

export type SavedAccount = { uuid: string; name: string; avatar_url: string | null; token: string; is_bot?: boolean };

const KEY = "jace_social_accounts";
const MAX = 10;

export function savedAccounts(): SavedAccount[] {
  try { return JSON.parse(localStorage.getItem(KEY) ?? "[]") as SavedAccount[]; } catch { return []; }
}

function save(list: SavedAccount[]) {
  try { localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX))); } catch { /* private mode */ }
}

/** Remember the account that's signed in now (called after loading /me). */
export function rememberAccount(a: Omit<SavedAccount, "token">, token = getToken()) {
  if (!token) return;
  save([{ ...a, token }, ...savedAccounts().filter((x) => x.uuid !== a.uuid)]);
}

export function forgetAccount(uuid: string) {
  save(savedAccounts().filter((x) => x.uuid !== uuid));
}

export function switchAccount(uuid: string) {
  const a = savedAccounts().find((x) => x.uuid === uuid);
  if (!a) return;
  setToken(a.token);
  location.reload();
}

/** Sign in to another account: keep this one in the list, show the sign-in screen. */
export function addAnotherAccount() {
  setToken(null);
  location.href = "/app";
}
