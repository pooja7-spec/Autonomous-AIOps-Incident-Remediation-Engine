package io.github.aiops.incident;

import io.github.aiops.incident.api.IncidentCorrelationFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IncidentCorrelationFilterTest {

    @Test
    void exposesValidatedCorrelationIdToRequestAndResponse() throws Exception {
        IncidentCorrelationFilter filter = new IncidentCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(IncidentCorrelationFilter.HEADER_NAME, "incident-123");

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertEquals("incident-123", MDC.get(IncidentCorrelationFilter.MDC_KEY)));

        assertEquals("incident-123", response.getHeader(IncidentCorrelationFilter.HEADER_NAME));
        assertNull(MDC.get(IncidentCorrelationFilter.MDC_KEY));
    }

    @Test
    void replacesInvalidCorrelationId() throws Exception {
        IncidentCorrelationFilter filter = new IncidentCorrelationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(IncidentCorrelationFilter.HEADER_NAME, "id with newline\nand sensitive value");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            String correlationId = MDC.get(IncidentCorrelationFilter.MDC_KEY);
            assertEquals(response.getHeader(IncidentCorrelationFilter.HEADER_NAME), correlationId);
            org.junit.jupiter.api.Assertions.assertTrue(correlationId.matches("[A-Za-z0-9._-]{1,64}"));
        });

        assertNull(MDC.get(IncidentCorrelationFilter.MDC_KEY));
    }
}
