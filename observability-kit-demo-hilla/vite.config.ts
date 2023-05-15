import type { UserConfigFn } from 'vite';
import { vaadinConfig } from './vite.generated.js';

const customConfig: UserConfigFn = async (env) => {
  const cfg = await vaadinConfig(env);
  // eslint-disable-next-line @typescript-eslint/promise-function-async
  cfg.plugins = cfg.plugins?.filter((plugin) =>
    plugin && 'name' in plugin ? plugin.name !== 'vite-plugin-checker' : plugin,
  );
  return cfg;
};

export default customConfig;
