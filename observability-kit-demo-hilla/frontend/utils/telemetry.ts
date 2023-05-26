import { type InstrumentationBase, registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { LongTaskInstrumentation } from '@opentelemetry/instrumentation-long-task';
import { type EventName, UserInteractionInstrumentation } from '@opentelemetry/instrumentation-user-interaction';
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request';
import { Resource } from '@opentelemetry/resources';
import { BatchSpanProcessor, StackContextManager, WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';
import { FrontendErrorInstrumentation } from './FrontendErrorInstrumentation.js';
import { HillaEndpointExporter } from './HillaEndpointExporter.js';

export type EndpointMethod = (jsonString: string) => Promise<void>;

export type TelemetryInitializationOptions = Readonly<{
  ignoredURLs?: readonly string[];
  ignoreVaadinURLs?: boolean;
  instanceId?: string;
  serviceName?: string;
  serviceVersion?: string;
  traceDocumentLoad?: boolean;
  traceErrors?: boolean;
  traceLongTask?: boolean;
  traceUserInteraction?: readonly EventName[];
  traceXmlHTTPRequest?: boolean;
}>;

export type Dispose = () => void;

const VAADIN_URLS = [/\/?v-r=.*/u, /\/VAADIN\/.*/u];
const DEFAULT_URLS = [/v-r=heartbeat/u, /v-r=o11y/u];

export function initTelemetry(
  method: EndpointMethod,
  {
    ignoredURLs,
    ignoreVaadinURLs,
    serviceName,
    serviceVersion,
    traceDocumentLoad,
    traceErrors,
    traceLongTask,
    traceUserInteraction,
    traceXmlHTTPRequest,
  }: TelemetryInitializationOptions = {},
): Dispose {
  const resource = Resource.default().merge(
    new Resource({
      [SemanticResourceAttributes.SERVICE_NAME]: serviceName,
      [SemanticResourceAttributes.SERVICE_VERSION]: serviceVersion,
    }),
  );

  const provider = new WebTracerProvider({
    resource,
  });

  const exporter = new HillaEndpointExporter({
    method,
  });

  provider.addSpanProcessor(
    new BatchSpanProcessor(exporter, {
      // How long the export can run before it is cancelled
      exportTimeoutMillis: 30000,
      // The maximum batch size of every export. It must be smaller or equal to maxQueueSize.
      maxExportBatchSize: 10,
      // The maximum queue size. After the size is reached spans are dropped.
      maxQueueSize: 100,
      // The interval between two consecutive exports
      scheduledDelayMillis: 500,
    }),
  );

  provider.register({
    // Changing default contextManager to use StackContextManager
    contextManager: new StackContextManager(),
  });

  const instrumentations: InstrumentationBase[] = [];
  if (traceDocumentLoad) {
    instrumentations.push(new DocumentLoadInstrumentation());
  }
  if (traceUserInteraction) {
    instrumentations.push(
      new UserInteractionInstrumentation({
        eventNames: traceUserInteraction as EventName[],
      }),
    );
  }
  if (traceXmlHTTPRequest) {
    const ignoredUrls: Array<RegExp | string> = ignoreVaadinURLs ? VAADIN_URLS : DEFAULT_URLS;
    if (ignoredURLs) {
      ignoredUrls.push(
        ...ignoredURLs.map((url) => {
          const match = /^RE:\/(.*)\/$/u.exec(url);
          if (match) {
            return new RegExp(match[1], 'u');
          }
          return url;
        }),
      );
    }
    instrumentations.push(
      new XMLHttpRequestInstrumentation({
        ignoreUrls: ignoredUrls,
      }),
    );
  }
  if (traceLongTask) {
    instrumentations.push(new LongTaskInstrumentation());
  }
  if (traceErrors) {
    instrumentations.push(new FrontendErrorInstrumentation());
  }

  return registerInstrumentations({
    instrumentations,
  });
}
