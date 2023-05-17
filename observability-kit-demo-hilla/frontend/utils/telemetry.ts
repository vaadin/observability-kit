import { ZoneContextManager } from '@opentelemetry/context-zone';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { SimpleSpanProcessor, WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { ObservabilityEndpoint } from 'Frontend/generated/endpoints.js';
import { HillaEndpointExporter } from 'Frontend/generated/jar-resources/components/HillaEndpointExporter.js';

export default function initTelemetry(): void {
  const provider = new WebTracerProvider();

  provider.register({
    contextManager: new ZoneContextManager(),
  });

  provider.addSpanProcessor(
    new SimpleSpanProcessor(
      new HillaEndpointExporter({
        // eslint-disable-next-line @typescript-eslint/no-misused-promises
        endpoint: ObservabilityEndpoint.export,
      }),
    ),
  );

  registerInstrumentations({
    instrumentations: [new DocumentLoadInstrumentation()],
  });
}
