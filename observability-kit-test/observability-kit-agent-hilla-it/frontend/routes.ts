import type { Route } from '@vaadin/router';
import './views/main/main-view.ts';

export type ViewRoute = Route & {
  children?: ViewRoute[];
  icon?: string;
  title?: string;
};

export const views: ViewRoute[] = [
  // Place routes below (more info https://hilla.dev/docs/routing)
  {
    path: '',
    component: 'main-view',
    icon: '',
    title: '',
  }
];

export const routes = [
  {
    path: '',
    component: 'main-view',
  },
];
