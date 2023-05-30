import { diag } from '@opentelemetry/api';
import { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace-base';
import { OTLPExporterBrowserBase, OTLPExporterError, OTLPExporterConfigBase } from '@opentelemetry/otlp-exporter-base';
import { createExportTraceServiceRequest, IExportTraceServiceRequest } from '@opentelemetry/otlp-transformer';

export class HillaEndpointExporter
  extends OTLPExporterBrowserBase<ReadableSpan, IExportTraceServiceRequest>
  implements SpanExporter
{
  protected _endpoint: (jsonString: string) => Promise<void>;

  constructor(config: HillaEndpointExporterConfig) {
    super(config);
    this._endpoint = config.endpoint;
  }

  convert(spans: ReadableSpan[]): IExportTraceServiceRequest {
    return createExportTraceServiceRequest(spans, true);
  }

  send(spans: ReadableSpan[], onSuccess: () => void, onError: (error: OTLPExporterError) => void): void {
    if (this._shutdownOnce.isCalled) {
      diag.debug('Shutdown already started. Cannot send objects');
      return;
    }

    diag.debug('Sending spans');
    try {
      const serviceRequest = this.convert(spans);
      const jsonString = JSON.stringify(serviceRequest);

      this._endpoint(jsonString).then(onSuccess).catch(onError);
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

export interface HillaEndpointExporterConfig extends OTLPExporterConfigBase {
  endpoint: (jsonString: string) => Promise<void>;
}
