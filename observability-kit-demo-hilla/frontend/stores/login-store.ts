import { login as serverLogin, logout as serverLogout } from '@hilla/frontend';
import { effect, signal } from '@preact/signals-core';
import { Router } from '@vaadin/router';
import { UserEndpoint } from 'Frontend/generated/endpoints.js';
import type UserDetails from 'Frontend/generated/org/springframework/security/core/userdetails/UserDetails.js';

export const user = signal<UserDetails | undefined>(undefined);

export async function checkAuthentication(): Promise<void> {
  const _user = await UserEndpoint.getAuthenticatedUser();

  if (_user) {
    user.value = _user;
  }
}

export async function login(username: string, password: string): Promise<void> {
  const result = await serverLogin(username, password);
  if (!result.error) {
    user.value = await UserEndpoint.getAuthenticatedUser();
  } else {
    throw new Error(result.errorMessage ?? 'Login failed');
  }
}

export async function logout(): Promise<void> {
  await serverLogout();
  user.value = undefined;
}

const REDIRECT_PATH_KEY = 'login-redirect-path';

export function memorizeRedirectPath(path: string): void {
  sessionStorage.setItem(REDIRECT_PATH_KEY, path);
}

export function doesUserHaveRole(role: string): boolean {
  return !!user.value?.authorities?.some((granted) => granted?.authority === role);
}

effect(() => {
  if (user.value) {
    Router.go(sessionStorage.getItem(REDIRECT_PATH_KEY) ?? '/');
  } else if (location.pathname !== '/login') {
    memorizeRedirectPath(location.pathname);
    Router.go('/login');
  }
});
