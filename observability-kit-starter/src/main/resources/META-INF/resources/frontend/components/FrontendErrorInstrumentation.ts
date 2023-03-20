import {
    SpanKind,
    SpanStatusCode
} from '@opentelemetry/api';
import {
    InstrumentationBase,
    InstrumentationConfig,
} from '@opentelemetry/instrumentation';
import { SemanticAttributes } from '@opentelemetry/semantic-conventions';

export class FrontendErrorInstrumentation extends InstrumentationBase {

    readonly component: string = 'frontend-error';
    readonly version: string = '1';
    moduleName = this.component;

    constructor(config: InstrumentationConfig = {}) {
        super('@vaadin/frontend-error-instrumentation', "2.1-SNAPSHOT", config);
    }

    protected init() {

    }

    private _onError(event: ErrorEvent) {
        const span = this.tracer.startSpan("windowError", {
            kind: SpanKind.CLIENT,
            startTime: event.timeStamp
        })

        span.setAttribute("component", this.component)
        span.setAttribute(SemanticAttributes.EXCEPTION_TYPE, event.type);
        span.setAttribute(SemanticAttributes.EXCEPTION_MESSAGE, event.message);
        span.setAttribute(SemanticAttributes.HTTP_URL, location.href);
        span.setAttribute(SemanticAttributes.CODE_FILEPATH, event.filename);
        span.setAttribute(SemanticAttributes.CODE_LINENO, event.lineno);
        span.recordException(event.error);
        span.setStatus({ code: SpanStatusCode.ERROR });
        span.end();
    }

    private _onUnhandledRejection(event: PromiseRejectionEvent) {
        const span = this.tracer.startSpan("unhandledRejection", {
            kind: SpanKind.CLIENT,
            startTime: event.timeStamp
        })
        span.setAttribute("component", this.component)
        span.setAttribute(SemanticAttributes.EXCEPTION_TYPE, event.type);
        span.setAttribute(SemanticAttributes.HTTP_URL, location.href);
        span.recordException(event.reason);
        span.setStatus({ code: SpanStatusCode.ERROR });
        span.end();
    }


    enable(): void {
        window.removeEventListener('error', this._onError);
        window.removeEventListener('unhandledrejection', this._onUnhandledRejection);

        this._diag.debug('Start tracing errors')

        this._onError = this._onError.bind(this);
        this._onUnhandledRejection = this._onUnhandledRejection.bind(this);

        window.addEventListener('error', this._onError, {
            capture: true, passive: true
        });
        window.addEventListener('unhandledrejection', this._onUnhandledRejection, {
            capture: true, passive: true
        });
    }

    disable(): void {
        this._diag.debug('Stop tracing errors')
        window.removeEventListener('error', this._onError);
        window.removeEventListener('unhandledrejection', this._onUnhandledRejection);
    }

}
