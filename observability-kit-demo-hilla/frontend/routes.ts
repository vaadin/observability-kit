import type { Route, Router } from '@vaadin/router';
import './views/helloworld/hello-world-view.js';
import './views/main-layout.js';
import './views/login-view.ts';
import './views/image-list/image-list-view.js';
import { user, login, logout, doesUserHaveRole } from 'Frontend/stores/login-store.js';

export type ViewRoute = Route & {
  children?: ViewRoute[];
  icon?: string;
  title?: string;
  requiresRole?: string;
  showInMenu?(): boolean;
};

export function hasAccess(route: ViewRoute): boolean {
  return !route.requiresRole || doesUserHaveRole(route.requiresRole);
}

function checkAuthentication(this: ViewRoute, context: Router.Context, commands: Router.Commands) {
  if (!hasAccess(this)) {
    return commands.redirect('/login');
  }

  return undefined;
}

export const views = [
  // Place routes below (more info https://hilla.dev/docs/routing)
  {
    action: checkAuthentication,
    component: 'hello-world-view',
    path: 'hello',
    title: 'Hello World',
  },
  {
    action: checkAuthentication,
    component: 'image-list-view',
    path: 'image-list',
    title: 'Image List',
  },
  {
    action: checkAuthentication,
    component: 'address-form-view',
    path: 'address-form',
    requiresRole: 'ROLE_USER',
    title: 'Address Form',
  },
] satisfies readonly ViewRoute[];

export const routes = [
  {
    children: [
      {
        action(context, commands) {
          if (user.value && context.pathname === '/login') {
            return commands.redirect('/');
          }

          if (!hasAccess(context.route as ViewRoute)) {
            return commands.redirect('/login');
          }

          return undefined;
        },
        component: 'login-view',
        path: 'login',
      },
      ...views,
    ],
    component: 'main-layout',
    path: '',
  },
  {
    async action(_, commands) {
      await logout();
      return commands.redirect('/login');
    },
    path: 'logout',
  },
] satisfies readonly ViewRoute[];
