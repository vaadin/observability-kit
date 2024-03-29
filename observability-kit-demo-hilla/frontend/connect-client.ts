import { type MiddlewareContext, type MiddlewareNext, ConnectClient } from '@vaadin/hilla-frontend';
import { logout } from 'Frontend/stores/login-store';

const client = new ConnectClient({
  middlewares: [
    async (context: MiddlewareContext, next: MiddlewareNext) => {
      const response = await next(context);

      // Log out if the authentication has expired
      if (response.status === 401) {
        await logout();
      }

      return response;
    },
  ],
  prefix: 'connect',
});

export default client;
