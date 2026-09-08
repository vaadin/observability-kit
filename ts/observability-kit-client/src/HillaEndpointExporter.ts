import { diag } from '@opentelemetry/api';
import { type ExportResult, ExportResultCode } from '@opentelemetry/core';
import { JsonTraceSerializer } from '@opentelemetry/otlp-transformer';
import type { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace-base';

export type HillaEndpointExportMethod = (jsonString: string) => Promise<void>;

export interface HillaEndpointExporterConfig {
  method: HillaEndpointExportMethod;
}

/**
 * Exports spans as OTLP/JSON through a Hilla endpoint method instead of an
 * HTTP URL, so no separate collector endpoint has to be exposed to the browser.
 */
export class HillaEndpointExporter implements SpanExporter {
  readonly #method: HillaEndpointExportMethod;
  #shutdown = false;

  constructor(config: HillaEndpointExporterConfig) {
    this.#method = config.method;
  }

  export(spans: ReadableSpan[], resultCallback: (result: ExportResult) => void): void {
    if (this.#shutdown) {
      diag.debug('Shutdown already started. Cannot send objects');
      resultCallback({ code: ExportResultCode.FAILED, error: new Error('Exporter has already been shut down') });
      return;
    }

    diag.debug('Sending spans');
    const payload = JsonTraceSerializer.serializeRequest(spans);
    if (!payload) {
      resultCallback({ code: ExportResultCode.FAILED, error: new Error('Could not serialize spans') });
      return;
    }

    this.#method(new TextDecoder().decode(payload)).then(
      () => resultCallback({ code: ExportResultCode.SUCCESS }),
      (error: unknown) => resultCallback({ code: ExportResultCode.FAILED, error: error as Error }),
    );
  }

  async forceFlush(): Promise<void> {
    // Spans are handed to the endpoint as soon as `export` is called, so there
    // is nothing buffered in the exporter itself.
  }

  async shutdown(): Promise<void> {
    this.#shutdown = true;
  }
}
