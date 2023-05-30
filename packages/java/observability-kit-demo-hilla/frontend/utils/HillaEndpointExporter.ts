import { diag } from '@opentelemetry/api';
import {
  OTLPExporterBrowserBase,
  OTLPExporterError,
  type OTLPExporterConfigBase,
} from '@opentelemetry/otlp-exporter-base';
import { createExportTraceServiceRequest, type IExportTraceServiceRequest } from '@opentelemetry/otlp-transformer';
import type { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace-base';

type TelemetryEndpointMethod = (json: string) => Promise<void>;

export class HillaEndpointExporter
  extends OTLPExporterBrowserBase<ReadableSpan, IExportTraceServiceRequest>
  implements SpanExporter
{
  protected _method: TelemetryEndpointMethod;

  constructor(config: HillaEndpointExporterConfig) {
    super(config);
    this._method = config.method;
  }

  convert(spans: ReadableSpan[]): IExportTraceServiceRequest {
    return createExportTraceServiceRequest(spans, true);
  }

  override async send(
    spans: ReadableSpan[],
    onSuccess: () => void,
    onError: (error: OTLPExporterError) => void,
  ): Promise<void> {
    if (this._shutdownOnce.isCalled) {
      diag.debug('Shutdown already started. Cannot send objects');
      return;
    }

    diag.debug('Sending spans');
    try {
      const serviceRequest = this.convert(spans);
      const jsonString = JSON.stringify(serviceRequest);

      await this._method(jsonString);
      onSuccess();
    } catch (e: unknown) {
      if (e instanceof OTLPExporterError) {
        onError(e);
      } else {
        throw e;
      }
    }
  }

  // OTLPExporterBrowserBase contains some useful exporter implementation,
  // however it is based on a URL endpoint, hence the getDefaultUrl function
  // needs to be defined even though it is not used in practice.
  getDefaultUrl(config: OTLPExporterConfigBase): string {
    return typeof config.url === 'string' ? config.url : '';
  }
}

export type HillaEndpointExporterConfig = OTLPExporterConfigBase &
  Readonly<{
    method: TelemetryEndpointMethod;
  }>;
