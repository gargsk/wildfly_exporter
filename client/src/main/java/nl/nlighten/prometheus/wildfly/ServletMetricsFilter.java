package nl.nlighten.prometheus.wildfly;

import io.prometheus.client.*;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * A servlet filter that will be added to deployed servlets by the MetricFilterExtension that provides the following metrics:
 * <p>
 * - A Histogram with response time distribution per context
 * - A Gauge with the number of concurrent request per context
 * - A Gauge with a the number of responses per context and status code
 * </p>
 * Example metrics being exported:
 * <pre>
 *     servlet_request_seconds_bucket{"/foo", "GET", "0.1",} 1.0
 *     ....
 *     servlet_request_seconds_bucket{"/foo", "GET", "+Inf",} 1.0
 *     servlet_request_concurrent_total{"/foo",} 1.0
 *     servlet_response_status_total{"/foo", "200",} 1.0
 *  </pre>
 */
public class ServletMetricsFilter implements Filter {
    public static final String BUCKET_CONFIG_PARAM = "buckets";
    private static Histogram servletLatency;
    private static Gauge servletConcurrentRequest;
    private static Gauge servletStatusCodes;

    private static int UNDEFINED_HTTP_STATUS = 999;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        if (servletLatency == null) {
            Histogram.Builder servletLatencyBuilder = Histogram.build()
                    .name("servlet_request_seconds")
                    .help("The time taken fulfilling servlet requests")
                    .labelNames("context", "method");

            if ((filterConfig.getInitParameter(BUCKET_CONFIG_PARAM) != null) && (!filterConfig.getInitParameter(BUCKET_CONFIG_PARAM).isEmpty())) {
                String[] bucketParams = filterConfig.getInitParameter(BUCKET_CONFIG_PARAM).split(",");
                double[] buckets = new double[bucketParams.length];
                for (int i = 0; i < bucketParams.length; i++) {
                    buckets[i] = Double.parseDouble(bucketParams[i].trim());
                }
                servletLatencyBuilder.buckets(buckets);
            } else {
                servletLatencyBuilder.buckets(.01, .05, .1, .25, .5, 1, 2.5, 5, 10, 30);
            }

            servletLatency = servletLatencyBuilder.register();

            Gauge.Builder servletConcurrentRequestBuilder = Gauge.build()
                    .name("servlet_request_concurrent_total")
                    .help("Number of concurrent requests for given context.")
                    .labelNames("context");

            servletConcurrentRequest = servletConcurrentRequestBuilder.register();

            Gauge.Builder servletStatusCodesBuilder = Gauge.build()
                    .name("servlet_response_status_total")
                    .help("Number of requests for given context and status code.")
                    .labelNames("context", "status");

            servletStatusCodes = servletStatusCodesBuilder.register();

        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        HttpServletRequest request = (HttpServletRequest) servletRequest;

        if (!request.isAsyncStarted()) {
            String context = getContext(request);

            servletConcurrentRequest.labels(context).inc();

            Histogram.Timer timer = servletLatency
                    .labels(context, request.getMethod())
                    .startTimer();

            try {
                filterChain.doFilter(servletRequest, servletResponse);
            } finally {
                timer.observeDuration();
                servletConcurrentRequest.labels(context).dec();
                servletStatusCodes.labels(context, Integer.toString(getStatus((HttpServletResponse) servletResponse))).inc();
            }
        } else {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }

    private int getStatus(HttpServletResponse response) {
        try {
            return response.getStatus();
        } catch (Exception ex) {
            return UNDEFINED_HTTP_STATUS;
        }
    }

    private String getContext(HttpServletRequest request) {
        if (request.getContextPath() != null && !request.getContextPath().isEmpty()) {
            return request.getContextPath();
        } else {
            return "/";
        }
    }

    @Override
    public void destroy() {
        // NOOP
    }
}