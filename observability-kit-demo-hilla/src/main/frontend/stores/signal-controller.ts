import type { Signal } from '@preact/signals-core';
import type { LitElement, ReactiveController } from 'lit';

export default class SignalController<T> implements ReactiveController {
  readonly #host: LitElement;
  readonly #signal: Signal<T>;
  #dispose?: () => void;

  constructor(host: LitElement, signal: Signal<T>) {
    this.#host = host;
    this.#signal = signal;
    host.addController(this);
  }

  get value(): T {
    return this.#signal.value;
  }

  hostConnected(): void {
    this.#dispose = this.#signal.subscribe(() => this.#host.requestUpdate());
  }

  hostDisconnected(): void {
    this.#dispose?.();
  }
}
