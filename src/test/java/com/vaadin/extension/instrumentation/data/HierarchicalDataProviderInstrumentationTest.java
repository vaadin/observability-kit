package com.vaadin.extension.instrumentation.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HierarchicalDataProviderInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void fetchFromDataProvider_createsSpan() {
        HierarchicalDataProvider dataProvider = Mockito
                .mock(HierarchicalDataProvider.class);
        Query query = Mockito.mock(Query.class);
        Mockito.when(query.getOffset()).thenReturn(100);
        Mockito.when(query.getLimit()).thenReturn(50);

        HierarchicalDataProviderInstrumentation.FetchAdvice
                .onEnter(dataProvider, query, null, null);
        HierarchicalDataProviderInstrumentation.FetchAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Hierarchical Data Provider Fetch", span.getName());
        String dataProviderType = span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.dataprovider.type"));
        assertNotNull(dataProviderType);
        assertTrue(dataProviderType.startsWith(
                "com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider"));
        assertEquals(100, span.getAttributes()
                .get(AttributeKey.longKey("vaadin.dataprovider.offset")));
        assertEquals(50, span.getAttributes()
                .get(AttributeKey.longKey("vaadin.dataprovider.limit")));
    }

    @Test
    public void fetchChildItemsFromDataProvider_createsSpan() {
        HierarchicalDataProvider dataProvider = Mockito
                .mock(HierarchicalDataProvider.class);
        HierarchicalQuery query = Mockito.mock(HierarchicalQuery.class);
        Mockito.when(query.getOffset()).thenReturn(100);
        Mockito.when(query.getLimit()).thenReturn(50);

        HierarchicalDataProviderInstrumentation.FetchChildrenAdvice
                .onEnter(dataProvider, query, null, null);
        HierarchicalDataProviderInstrumentation.FetchChildrenAdvice.onExit(null,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Hierarchical Data Provider Fetch Children",
                span.getName());
        String dataProviderType = span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.dataprovider.type"));
        assertNotNull(dataProviderType);
        assertTrue(dataProviderType.startsWith(
                "com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider"));
        assertEquals(100, span.getAttributes()
                .get(AttributeKey.longKey("vaadin.dataprovider.offset")));
        assertEquals(50, span.getAttributes()
                .get(AttributeKey.longKey("vaadin.dataprovider.limit")));
    }
}
