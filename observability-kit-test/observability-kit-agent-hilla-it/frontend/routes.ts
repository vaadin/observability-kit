import type { Route } from '@vaadin/router';
import './views/main/main-view.ts';

export type ViewRoute = Route & {
  children?: ViewRoute[];
  icon?: string;
  title?: string;
};

export const routes = [
  {
    path: '',
    component: 'main-view',
  },
];
