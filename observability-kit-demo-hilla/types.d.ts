/* eslint-disable import/unambiguous */

declare module '*.module.css' {
  const content: Readonly<Record<string, string>>;
  export default content;
}

declare module '*.css' {
  import type { CSSResultGroup } from 'lit';

  const content: CSSResultGroup;
  export default content;
}
