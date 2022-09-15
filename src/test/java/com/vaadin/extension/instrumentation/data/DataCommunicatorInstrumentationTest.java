package com.vaadin.extension.instrumentation.data;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.data.provider.DataCommunicator;
import com.vaadin.flow.data.provider.DataProvider;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DataCommunicatorInstrumentationTest extends AbstractInstrumentationTest {
    @Test
    public void fetchFromDataProvider_createsSpan() {
        DataProvider dataProvider = Mockito.mock(DataProvider.class);
        DataCommunicator dataCommunicator = Mockito
                .mock(DataCommunicator.class);
        Mockito.when(dataCommunicator.getDataProvider())
                .thenReturn(dataProvider);

        DataCommunicatorInstrumentation.FetchAdvice.onEnter(dataCommunicator,
                100, 50, null, null);
        DataCommunicatorInstrumentation.FetchAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Data Provider Fetch", span.getName());
        String dataProviderType = span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.dataprovider.type"));
        assertNotNull(dataProviderType);
        assertTrue(dataProviderType
                .startsWith("com.vaadin.flow.data.provider.DataProvider"));
        assertEquals(100, span.getAttributes()
                .get(AttributeKey.longKey("vaadin.dataprovider.offset")));
        assertEquals(50, span.getAttributes()
                .get(AttributeKey.longKey("vaadin.dataprovider.limit")));
    }
}
