import { diag } from '@opentelemetry/api';
import {
  OTLPExporterBrowserBase,
  type OTLPExporterError,
  type OTLPExporterConfigBase,
} from '@opentelemetry/otlp-exporter-base';
import { createExportTraceServiceRequest, type IExportTraceServiceRequest } from '@opentelemetry/otlp-transformer';
import type { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace-base';

export type HillaEndpointExportMethod = (jsonString: string) => Promise<void>;

export interface HillaEndpointExporterConfig extends OTLPExporterConfigBase {
  method: HillaEndpointExportMethod;
}

export class HillaEndpointExporter
  extends OTLPExporterBrowserBase<ReadableSpan, IExportTraceServiceRequest>
  implements SpanExporter
{
  protected _method: HillaEndpointExportMethod;

  constructor(config: HillaEndpointExporterConfig) {
    super(config);
    this._method = config.method;
  }

  convert(spans: ReadableSpan[]): IExportTraceServiceRequest {
    return createExportTraceServiceRequest(spans, true);
  }

  override send(spans: ReadableSpan[], onSuccess: () => void, onError: (error: OTLPExporterError) => void): void {
    if (this._shutdownOnce.isCalled) {
      diag.debug('Shutdown already started. Cannot send objects');
      return;
    }

    diag.debug('Sending spans');
    try {
      const serviceRequest = this.convert(spans);
      const jsonString = JSON.stringify(serviceRequest);

      this._method(jsonString).then(onSuccess).catch(onError);
    } catch (e: unknown) {
      onError(e as OTLPExporterError);
    }
  }

  // OTLPExporterBrowserBase contains some useful exporter implementation,
  // however it is based on a URL endpoint, hence the getDefaultUrl function
  // needs to be defined even though it is not used in practice.
  getDefaultUrl(config: OTLPExporterConfigBase): string {
    return typeof config.url === 'string' ? config.url : '';
  }
}
