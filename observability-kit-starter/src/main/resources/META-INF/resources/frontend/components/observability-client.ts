import {html, LitElement, nothing, PropertyValues} from 'lit';
import {customElement} from 'lit/decorators.js';
import {
  BatchSpanProcessor,
  StackContextManager,
  WebTracerProvider
} from "@opentelemetry/sdk-trace-web";
import {registerInstrumentations} from "@opentelemetry/instrumentation";
import {
  DocumentLoadInstrumentation
} from "@opentelemetry/instrumentation-document-load";
import {
  UserInteractionInstrumentation,
  EventName
} from "@opentelemetry/instrumentation-user-interaction";
import {
  XMLHttpRequestInstrumentation
} from "@opentelemetry/instrumentation-xml-http-request";
import {
  LongTaskInstrumentation
} from "@opentelemetry/instrumentation-long-task";
import {OTLPTraceExporter} from "@opentelemetry/exporter-trace-otlp-http";
import { FrontendErrorInstrumentation } from './FrontendErrorInstrumentation';

@customElement('vaadin-observability-client')
export class ObservabilityClient extends LitElement {
  provider: WebTracerProvider;
  instanceId?: string;

  traceDocumentLoad?: boolean;
  traceUserInteraction?: EventName[]
  traceXmlHTTPRequest?: boolean;
  ignoreURLs?: string[];
  ignoreVaadinURLs?: boolean;

  traceLongTask?: boolean;
  traceErrors?: boolean

  unloadInstrumentations? : () => void;

  constructor() {
    super();

    this.provider = new WebTracerProvider();
  }

  render() {
    return html`${nothing}`;
  }

  protected firstUpdated(_changedProperties: PropertyValues) {
    super.firstUpdated(_changedProperties);

    const exporter = new OTLPTraceExporter({
      url: '/?v-r=o11y&id=' + this.instanceId,
    })
    this.provider.addSpanProcessor(new BatchSpanProcessor(exporter, {
      // The maximum queue size. After the size is reached spans are dropped.
      maxQueueSize: 100,
      // The maximum batch size of every export. It must be smaller or equal to maxQueueSize.
      maxExportBatchSize: 10,
      // The interval between two consecutive exports
      scheduledDelayMillis: 500,
      // How long the export can run before it is cancelled
      exportTimeoutMillis: 30000,
    }));

    this.provider.register({
      // Changing default contextManager to use StackContextManager
      contextManager: new StackContextManager(),
    });

    const instrumentations = [];
    if (this.traceDocumentLoad) {
      instrumentations.push(new DocumentLoadInstrumentation());
    }
    if (this.traceUserInteraction) {
      instrumentations.push(new UserInteractionInstrumentation({
        eventNames: [...this.traceUserInteraction]
      }));
    }
    if (this.traceXmlHTTPRequest) {
      const ignoredUrls = [];
      if (this.ignoreVaadinURLs) {
        ignoredUrls.push(/\/?v-r=.*/);
        ignoredUrls.push(/\/VAADIN\/.*/);
      } else {
        ignoredUrls.push('/?v-r=o11y');
      }
      if (this.ignoreURLs) {
        ignoredUrls.push(...this.ignoreURLs.map((url) => {
          const match = url.match(/^RE:\/(.*)\/$/);
          if (match) {
            return new RegExp(match[1]);
          }
          return url;
        }));
      }
      instrumentations.push(new XMLHttpRequestInstrumentation({
        ignoreUrls: ignoredUrls
      }));
    }
    if (this.traceLongTask) {
      instrumentations.push(new LongTaskInstrumentation());
    }
    if (this.traceErrors) {
      instrumentations.push(new FrontendErrorInstrumentation());
    }

    this.unloadInstrumentations = registerInstrumentations({
      instrumentations: instrumentations
    })
  }

  disconnectedCallback() {
    super.disconnectedCallback();

    if (this.unloadInstrumentations) {
      this.unloadInstrumentations();
    }
  }
}
