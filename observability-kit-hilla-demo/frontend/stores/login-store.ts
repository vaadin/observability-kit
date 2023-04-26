import { login as serverLogin, logout as serverLogout } from '@hilla/frontend';
import { Router } from '@vaadin/router';
import { action, atom, onSet } from 'nanostores';

export const auth = atom(true);

export const login = action(auth, 'login', async (store, username: string, password: string) => {
  const result = await serverLogin(username, password);
  if (!result.error) {
    store.set(true);
  } else {
    throw new Error(result.errorMessage ?? 'Login failed');
  }
});

export const logout = action(auth, 'logout', async (store) => {
  await serverLogout();
  store.set(false);
});

const REDIRECT_PATH_KEY = 'login-redirect-path';

export function memorizeRedirectPath(path: string): void {
  sessionStorage.setItem(REDIRECT_PATH_KEY, path);
}

onSet(auth, ({ newValue: loggedIn }) => {
  if (loggedIn) {
    Router.go(sessionStorage.getItem(REDIRECT_PATH_KEY) ?? '/');
  } else if (location.pathname !== '/login') {
    memorizeRedirectPath(location.pathname);
    Router.go('/login');
  }
});
