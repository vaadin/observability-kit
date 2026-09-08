import { diag } from '@opentelemetry/api';
import { ExportResult, ExportResultCode } from '@opentelemetry/core';
import { JsonTraceSerializer } from '@opentelemetry/otlp-transformer';
import { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace-base';

export class HillaEndpointExporter implements SpanExporter {
  protected _endpoint: (jsonString: string) => Promise<void>;
  private _isShutdown = false;

  constructor(config: HillaEndpointExporterConfig) {
    this._endpoint = config.endpoint;
  }

  export(spans: ReadableSpan[], resultCallback: (result: ExportResult) => void): void {
    if (this._isShutdown) {
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

    this._endpoint(new TextDecoder().decode(payload)).then(
      () => resultCallback({ code: ExportResultCode.SUCCESS }),
      (error: unknown) => resultCallback({ code: ExportResultCode.FAILED, error: error as Error })
    );
  }

  async forceFlush(): Promise<void> {
    // Spans are handed to the endpoint as soon as `export` is called, so there
    // is nothing buffered in the exporter itself.
  }

  async shutdown(): Promise<void> {
    this._isShutdown = true;
  }
}

export interface HillaEndpointExporterConfig {
  endpoint: (jsonString: string) => Promise<void>;
}
