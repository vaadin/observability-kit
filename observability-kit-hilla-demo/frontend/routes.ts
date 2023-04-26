import type { Route } from '@vaadin/router';
import './views/helloworld/hello-world-view.js';
import './views/main-layout.js';
import './views/login-view.ts';
import { auth, login, logout, memorizeRedirectPath } from 'Frontend/stores/login-store.js';

export type ViewRoute = Route & {
  title?: string;
  icon?: string;
  children?: ViewRoute[];
};

export const views = [
  // Place routes below (more info https://hilla.dev/docs/routing)
  {
    component: 'hello-world-view',
    icon: '',
    path: '',
    title: '',
  },
  {
    component: 'hello-world-view',
    icon: 'globe-solid',
    path: 'hello',
    title: 'Hello World',
  },
  {
    async action() {
      await import('./views/about/about-view.js');
    },
    component: 'about-view',
    icon: 'file',
    path: 'about',
    title: 'About',
  },
] satisfies readonly ViewRoute[];

export const routes = [
  {
    async action(context, commands) {
      if (!auth.get()) {
        memorizeRedirectPath(context.pathname);
        return commands.redirect('/login');
      }

      return undefined;
    },
    children: views,
    component: 'main-layout',
    path: '',
  },
  {
    component: 'login-view',
    path: 'login',
  },
  {
    async action(_, commands) {
      // eslint-disable-next-line @typescript-eslint/no-floating-promises
      logout();
      return commands.redirect('/login');
    },
    path: 'logout',
  },
] satisfies readonly ViewRoute[];
