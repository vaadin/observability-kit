import { ZoneContextManager } from '@opentelemetry/context-zone';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { SimpleSpanProcessor, WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { HillaEndpointExporter, type HillaEndpointExportMethod } from './HillaEndpointExporter.js';

export function init(
  endpoint: HillaEndpointExportMethod,
  exporter: typeof HillaEndpointExporter = HillaEndpointExporter,
): void {
  const provider = new WebTracerProvider();

  provider.register({
    contextManager: new ZoneContextManager(),
  });

  provider.addSpanProcessor(
    new SimpleSpanProcessor(
      new exporter({
        endpoint,
      }),
    ),
  );

  registerInstrumentations({
    instrumentations: [new DocumentLoadInstrumentation()],
  });
}
