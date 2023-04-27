import { login as serverLogin, logout as serverLogout } from '@hilla/frontend';
import { effect, signal } from '@preact/signals-core';
import { Router } from '@vaadin/router';

export const auth = signal(true);

export async function login(username: string, password: string): Promise<void> {
  const result = await serverLogin(username, password);
  if (!result.error) {
    auth.value = true;
  } else {
    throw new Error(result.errorMessage ?? 'Login failed');
  }
}

export async function logout(): Promise<void> {
  await serverLogout();
  auth.value = false;
}

const REDIRECT_PATH_KEY = 'login-redirect-path';

export function memorizeRedirectPath(path: string): void {
  sessionStorage.setItem(REDIRECT_PATH_KEY, path);
}

effect(() => {
  if (auth.value) {
    Router.go(sessionStorage.getItem(REDIRECT_PATH_KEY) ?? '/');
  } else if (location.pathname !== '/login') {
    memorizeRedirectPath(location.pathname);
    Router.go('/login');
  }
});
