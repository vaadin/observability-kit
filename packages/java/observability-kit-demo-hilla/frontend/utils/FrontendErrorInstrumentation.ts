import { type AttributeValue, type Span, SpanKind, SpanStatusCode } from '@opentelemetry/api';
import { InstrumentationBase, type InstrumentationConfig } from '@opentelemetry/instrumentation';
import { getElementXPath } from '@opentelemetry/sdk-trace-web';
import { SemanticAttributes } from '@opentelemetry/semantic-conventions';

export class FrontendErrorInstrumentation extends InstrumentationBase {
  readonly component: string = 'frontend-error';
  moduleName = this.component;
  readonly version: string = '1';
  #listenerControllers = new Map<keyof WindowEventMap, AbortController>();

  constructor(config: InstrumentationConfig = { enabled: true }) {
    // Avoid issue when `enable` is called in the super constructor and fails
    // because of private methods
    super('@vaadin/frontend-error-instrumentation', '2.1-SNAPSHOT', { enabled: false });
    if (config.enabled) {
      this.enable();
    }
    this.setConfig(config);
  }

  override disable(): void {
    this._diag.debug('Stop tracing errors');
    for (const c of this.#listenerControllers.values()) {
      c.abort();
    }
  }

  override enable(): void {
    this._diag.debug('Start tracing errors');
    this.#listen('error', (event: ErrorEvent) => {
      const span = this.tracer.startSpan('windowError', {
        kind: SpanKind.CLIENT,
        startTime: event.timeStamp,
      });
      span.setAttribute('component', this.component);
      span.setAttribute(SemanticAttributes.EXCEPTION_TYPE, event.type);
      span.setAttribute(SemanticAttributes.EXCEPTION_MESSAGE, event.message);
      span.setAttribute(SemanticAttributes.HTTP_URL, location.href);
      span.setAttribute(SemanticAttributes.CODE_FILEPATH, event.filename);
      span.setAttribute(SemanticAttributes.CODE_LINENO, event.lineno);
      this.#addTargetAttributes(event, span);
      span.recordException(event.error);
      span.setStatus({ code: SpanStatusCode.ERROR });
      span.end();
    });
    this.#listen('unhandledrejection', (event: PromiseRejectionEvent) => {
      const span = this.tracer.startSpan('unhandledRejection', {
        kind: SpanKind.CLIENT,
        startTime: event.timeStamp,
      });

      span.setAttribute('component', this.component);
      span.setAttribute(SemanticAttributes.EXCEPTION_TYPE, event.type);
      span.setAttribute(SemanticAttributes.HTTP_URL, location.href);
      this.#addTargetAttributes(event, span);
      span.recordException(event.reason);
      span.setStatus({ code: SpanStatusCode.ERROR });
      span.end();
    });
  }

  protected init(): void {}

  #addTargetAttributes(event: Event, span: Span) {
    const eventTarget = event.target as Record<string, AttributeValue | undefined> | null;
    if (eventTarget?.tagName) {
      span.setAttribute('target_element', eventTarget.tagName);
      span.setAttribute('target_xpath', getElementXPath(eventTarget));
      if (eventTarget.src) {
        span.setAttribute('target_src', eventTarget.src);
      }
      if (eventTarget.href) {
        span.setAttribute('target_href', eventTarget.href);
      }
      if (eventTarget.data) {
        span.setAttribute('target_data', eventTarget.data);
      }
    }
  }

  #listen<T extends keyof WindowEventMap>(type: T, callback: (event: WindowEventMap[T]) => void) {
    this.#listenerControllers.get(type)?.abort();

    const controller = new AbortController();
    addEventListener(type, callback, { capture: true, passive: true, signal: controller.signal });
    this.#listenerControllers.set(type, controller);
  }
}
