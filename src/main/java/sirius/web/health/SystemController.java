/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.health;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import sirius.kernel.Sirius;
import sirius.kernel.commons.Explain;
import sirius.kernel.commons.MultiMap;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Tuple;
import sirius.kernel.di.GlobalContext;
import sirius.kernel.di.PartCollection;
import sirius.kernel.di.std.ConfigValue;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Parts;
import sirius.kernel.di.std.Register;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.Microtiming;
import sirius.kernel.health.metrics.Metric;
import sirius.kernel.health.metrics.MetricState;
import sirius.kernel.health.metrics.Metrics;
import sirius.kernel.nls.NLS;
import sirius.web.controller.BasicController;
import sirius.web.controller.Page;
import sirius.web.controller.Routed;
import sirius.web.http.WebContext;
import sirius.web.http.WebServer;
import sirius.web.security.Permission;
import sirius.web.services.Format;
import sirius.web.services.PublicService;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Contains the default admin GUI.
 */
@Register
public class SystemController extends BasicController {

    private static final String LOAD_INFO_METRIC_PREFIX = "load_";

    @Part
    private Cluster cluster;

    @Part
    private Metrics metrics;

    @Parts(LoadInfoProvider.class)
    private PartCollection<LoadInfoProvider> loadInfoProviders;

    @Part
    private GlobalContext context;

    @ConfigValue("sirius.metrics.labelPrefix")
    private String metricLabelPrefix;

    @ConfigValue("sirius.metrics.blockPublicAccess")
    private boolean blockPublicAccess;

    /**
     * Describes the permission required to access the system console.
     */
    public static final String PERMISSION_SYSTEM_TIMING = "permission-system-timing";

    /**
     * Describes the permission required to view the system state.
     */
    public static final String PERMISSION_SYSTEM_STATE = "permission-system-state";

    /**
     * Describes the permission required to view the system load.
     */
    public static final String PERMISSION_SYSTEM_LOAD = "permission-system-load";

    /**
     * Simply responds with OK for <tt>/system/ok</tt>
     * <p>
     * This can be used to monitoring tools the check the system health.
     *
     * @param ctx the request being handled
     */
    @Routed("/system/ok")
    @PublicService(apiName = "health", format = Format.RAW)
    @Operation(summary = "Health check", description = """
            Provides a very simple API to "ping" the system. This will constantly return "OK".
            """)
    @ApiResponse(responseCode = "200",
            description = "Successful response",
            content = @Content(mediaType = "text/plain", examples = @ExampleObject("OK")))
    public void ok(WebContext ctx) {
        ctx.respondWith().direct(HttpResponseStatus.OK, "OK");
    }

    /**
     * Determines if there is currently an ALARM present or not for: <tt>/system/monitor</tt>
     * <p>
     * Reports OK or ERROR, if a cluster alarm is present.
     *
     * @param ctx the request being handled
     */
    @Routed("/system/monitor")
    @PublicService(apiName = "health", format = Format.RAW)
    @Operation(summary = "Monitoring API", description = """
            Provides a simple monitoring service. As long as the system operates normally,
            it returns "OK", otherwise, "ERROR" and the reason are returned.
            """)
    @ApiResponse(responseCode = "200",
            description = "Successful response",
            content = @Content(mediaType = "text/plain", examples = @ExampleObject("OK")))
    public void monitorNode(WebContext ctx) {
        if (!cluster.isAlarmPresent() || cluster.getNodeState() != MetricState.RED) {
            ctx.respondWith().direct(HttpResponseStatus.OK, "OK");
            return;
        }

        try (PrintWriter writer = createSimpleErrorResponse(ctx)) {
            writer.println("ERROR");
            writer.println();
            writer.println("Failing Metrics on this node:");
            metrics.getMetrics()
                   .stream()
                   .filter(metric -> metric.getState() == MetricState.RED)
                   .forEach(metric -> writer.println(Strings.apply("%-30s %15s",
                                                                   metric.getLabel().toLowerCase(),
                                                                   metric.getValueAsString().toLowerCase())));
            writer.println();
        }
    }

    private PrintWriter createSimpleErrorResponse(WebContext ctx) {
        OutputStream os = ctx.respondWith().outputStream(HttpResponseStatus.EXPECTATION_FAILED, null);
        return new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
    }

    /**
     * Sends the value for the requested metric for <tt>/system/metric/[name]</tt>
     *
     * @param ctx the request being handled
     * @param key the name of the metric to fetch
     */
    @Routed("/system/metric/:1")
    public void metric(WebContext ctx, String key) {
        for (Metric m : metrics.getMetrics()) {
            if (Strings.areEqual(key, m.getCode())) {
                ctx.respondWith().direct(HttpResponseStatus.OK, NLS.toMachineString(m.getValue()));
                return;
            }
        }
        ctx.respondWith().direct(HttpResponseStatus.OK, NLS.toMachineString(0d));
    }

    /**
     * Sends all known metrics in a format understood by <b>prometheus.io</b>.
     *
     * @param ctx the request being handled
     */
    @Routed("/system/metrics")
    @PublicService(apiName = "health", format = Format.RAW)
    @Operation(summary = "Metrics API", description = """
            Provides all collected metrics in Prometheus compatible format.
            """)
    @ApiResponse(responseCode = "200",
            description = "Successful response",
            content = @Content(mediaType = "text/plain", examples = @ExampleObject("""
                    # HELP sirius_node_state Node State
                    # TYPE sirius_node_state gauge
                    sirius_node_state 0.0
                    # HELP sirius_http_open_connections HTTP Open Connections
                    # TYPE sirius_http_open_connections gauge
                    sirius_http_open_connections 7.0
                    """)))
    public void metrics(WebContext ctx) {
        if (blockPublicAccess && ctx.getHeaderValue(WebServer.HEADER_X_FORWARDED_FOR).isFilled()) {
            ctx.respondWith().error(HttpResponseStatus.FORBIDDEN);
            return;
        }

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(ctx.respondWith()
                                                                         .outputStream(HttpResponseStatus.OK,
                                                                                       "text/plain; version=0.0.4"),
                                                                      StandardCharsets.UTF_8))) {
            outputNodeStateAsMetric(out);

            for (Metric m : metrics.getMetrics()) {
                outputMetric(out, m);
            }

            for (LoadInfoProvider provider : loadInfoProviders) {
                for (LoadInfo info : provider.collectLoadInfos()) {
                    outputMetric(out, transformLoadIntoToMetric(provider, info));
                }
            }
        }
    }

    private Metric transformLoadIntoToMetric(LoadInfoProvider provider, LoadInfo info) {
        return new Metric(LOAD_INFO_METRIC_PREFIX + info.getCode(),
                          provider.getLabel() + ": " + info.getLabel(),
                          info.getValue(),
                          MetricState.GREEN,
                          info.getUnit());
    }

    /**
     * Reports the node state as metric (0=OK, 1=WARN, 2=ERROR).
     *
     * @param out the output stream to write the metric to
     */
    @SuppressWarnings("squid:S2184")
    @Explain("We're only doing calculations with simple operations and small numbers.")
    private void outputNodeStateAsMetric(PrintWriter out) {
        outputMetric(out,
                     new Metric("node_state",
                                "Node State",
                                cluster.getNodeState().ordinal() - 1,
                                cluster.getNodeState(),
                                null));
    }

    private void outputMetric(PrintWriter out, Metric m) {
        String effectiveCode = metricLabelPrefix + m.getCode().toLowerCase().replaceAll("[^a-z0-9]", "_");
        out.print("# HELP ");
        out.print(effectiveCode);
        out.print(" ");
        if (Strings.isFilled(m.getUnit())) {
            out.print(m.getLabel());
            out.print(" (");
            out.print(m.getUnit());
            out.println(")");
        } else {
            out.println(m.getLabel());
        }

        out.print("# TYPE ");
        out.print(effectiveCode);
        out.println(" gauge");
        out.print(effectiveCode);
        out.print(" ");
        out.println(NLS.toMachineString(m.getValue()));
    }

    /**
     * Can be used to forcefully create an error. (A HandledException in this case.)
     *
     * @param ctx the current request
     */
    @Routed("/system/fail")
    public void fail(WebContext ctx) {
        throw Exceptions.createHandled().withSystemErrorMessage("Forced Exception").handle();
    }

    /**
     * Reports useful information for the current user agent and request.
     * <p>
     * This will output all headers and session information available for the current request
     *
     * @param webContext the current request
     */
    @Routed("/system/info")
    public void info(WebContext webContext) {
        webContext.respondWith().template("/templates/system/info.html.pasta", webContext);
    }

    /**
     * Clears the server and client session.
     * <p>
     * Clears all session data available for the current request.
     *
     * @param ctx the current request
     */
    @Routed("/system/reset")
    public void reset(WebContext ctx) {
        ctx.clearSession();
        ctx.respondWith().direct(HttpResponseStatus.OK, "Session has been cleared...");
    }

    /**
     * Reports the system and cluster state.
     *
     * @param ctx the current request
     */
    @Routed("/system/state")
    @Permission(PERMISSION_SYSTEM_STATE)
    public void state(WebContext ctx) {
        ctx.respondWith()
           .template("/templates/system/state.html.pasta",
                     cluster,
                     metrics,
                     ctx.get("all").asBoolean(false),
                     NLS.convertDuration(Sirius.getUptimeInMilliseconds(), true, false));
    }

    /**
     * Reports the system load.
     *
     * @param ctx the current request
     */
    @Routed("/system/load")
    @Permission(PERMISSION_SYSTEM_STATE)
    public void load(WebContext ctx) {
        ctx.respondWith()
           .template("/templates/system/load.html.pasta",
                     loadInfoProviders.getParts()
                                      .stream()
                                      .sorted(Comparator.comparing(LoadInfoProvider::getLabel))
                                      .toList(),
                     ctx.get("all").asBoolean(false));
    }

    /**
     * Provides a list of recorded micro timings.
     *
     * @param ctx the current request
     */
    @Routed("/system/timing")
    @Permission(PERMISSION_SYSTEM_TIMING)
    public void timing(WebContext ctx) {
        if (ctx.hasParameter("enable")) {
            Microtiming.setEnabled(true);
        }
        if (ctx.hasParameter("disable")) {
            Microtiming.setEnabled(false);
        }

        String periodSinceReset =
                NLS.convertDuration(System.currentTimeMillis() - Microtiming.getLastReset(), true, false);

        Page<String> page = new Page<>();
        page.bindToRequest(ctx);

        List<Tuple<String, Collection<Tuple<String, String>>>> timings = computeTimingInfos(page);

        ctx.respondWith()
           .template("/templates/system/timing.html.pasta", Microtiming.isEnabled(), timings, page, periodSinceReset);
    }

    private List<Tuple<String, Collection<Tuple<String, String>>>> computeTimingInfos(Page<String> page) {
        MultiMap<String, Tuple<String, String>> timingMap = MultiMap.createOrdered();
        String query = Strings.isFilled(page.getQuery()) ? page.getQuery().toLowerCase() : null;
        List<Microtiming.Timing> timings = Microtiming.getTimings();
        timings.sort(Comparator.comparingDouble(t -> t.getAvg().getCount() * t.getAvg().getAvg() * -1d));
        for (Microtiming.Timing timing : timings) {
            if (matchesQuery(query, timing)) {
                timingMap.put(timing.getCategory(),
                              Tuple.create(timing.getKey(),
                                           NLS.toUserString(timing.getAvg().getCount())
                                           + " ("
                                           + NLS.toUserString(timing.getAvg().getAvg() / 1000)
                                           + " ms)"));
            }
        }

        return timingMap.getUnderlyingMap()
                        .entrySet()
                        .stream()
                        .map(e -> Tuple.create(e.getKey(), e.getValue()))
                        .collect(Collectors.toList());
    }

    private boolean matchesQuery(String query, Microtiming.Timing timing) {
        return query == null || timing.getKey().toLowerCase().contains(query) || timing.getCategory()
                                                                                       .toLowerCase()
                                                                                       .contains(query);
    }
}
