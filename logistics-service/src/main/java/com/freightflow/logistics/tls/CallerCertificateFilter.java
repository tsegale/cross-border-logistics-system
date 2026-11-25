package com.freightflow.logistics.tls;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

/**
 * Only the callers this service expects get past TLS (ADR 0040). The connector already refused any client without a
 * certificate from the internal CA; this checks whose certificate it is, by its common name (the agent's identity,
 * infra/tls/identities.conf), against {@code freightflow.tls.allowed-callers}, before the bearer token is looked at.
 * A caller that is not on the list gets a 403 problem; the token check that follows is unchanged (ADR 0017).
 */
public class CallerCertificateFilter extends OncePerRequestFilter {

    static final String METRIC = "freightflow.tls.callers.refused";
    private static final String CERTIFICATES = "jakarta.servlet.request.X509Certificate";
    private static final Logger events = LoggerFactory.getLogger("freightflow.security");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Set<String> allowed;
    private final MeterRegistry meters;

    public CallerCertificateFilter(List<String> allowed, MeterRegistry meters) {
        this.allowed = Set.copyOf(allowed);
        this.meters = meters;
        // From the start, at zero: a refusal counted only when it happens is no increase to Prometheus.
        meters.counter(METRIC);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String caller = caller(request);
        if (caller != null && allowed.contains(caller)) {
            chain.doFilter(request, response);
            return;
        }
        meters.counter(METRIC).increment();
        // The name is one of the CA's own identities or nothing: no caller-supplied text reaches the log.
        events.atWarn()
                .addKeyValue("event.action", "caller-not-allowed")
                .addKeyValue("tls.client.subject", caller)
                .addKeyValue("url.path", request.getRequestURI())
                .addKeyValue("http.response.status_code", (Object) HttpStatus.FORBIDDEN.value())
                .log("Security event caller-not-allowed");
        ObjectNode body = JSON.createObjectNode()
                .put("type", "https://freightflow.dev/problems/caller-not-allowed")
                .put("title", "Caller not allowed")
                .put("status", HttpStatus.FORBIDDEN.value())
                .put("detail", "This service does not take requests from the client certificate presented.")
                .put("instance", request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }

    /** The common name of the client's certificate, or null without one. */
    static String caller(HttpServletRequest request) {
        if (!(request.getAttribute(CERTIFICATES) instanceof X509Certificate[] chain) || chain.length == 0) {
            return null;
        }
        try {
            for (Rdn rdn : new LdapName(chain[0].getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException e) {
            return null;
        }
        return null;
    }
}
