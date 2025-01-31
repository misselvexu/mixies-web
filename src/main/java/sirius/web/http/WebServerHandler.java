/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.http;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import sirius.kernel.async.CallContext;
import sirius.kernel.async.TaskContext;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Watch;
import sirius.kernel.di.std.ConfigValue;
import sirius.kernel.di.std.Part;
import sirius.kernel.health.Average;
import sirius.kernel.health.Exceptions;
import sirius.kernel.nls.NLS;
import sirius.web.security.UserContext;

import javax.annotation.Nullable;
import javax.net.ssl.SSLHandshakeException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles incoming HTTP requests.
 * <p>
 * Takes care of gluing together chunks, handling file uploads etc. In order to participate in handling HTTP requests,
 * one has to provide a {@link WebDispatcher} rather than modifying this class.
 */
class WebServerHandler extends ChannelDuplexHandler implements ActiveHTTPConnection {

    private int numKeepAlive = maxKeepalive;
    private HttpRequest currentRequest;
    private WebContext currentContext;
    private CallContext currentCall;
    private final long connected;
    private final AtomicLong bytesIn = new AtomicLong(0);
    private final AtomicLong bytesOut = new AtomicLong(0);
    private final AtomicLong currentBytesIn = new AtomicLong(0);
    private final AtomicLong currentBytesOut = new AtomicLong(0);
    private final AtomicLong uplink = new AtomicLong(0);
    private final AtomicLong downlink = new AtomicLong(0);
    private final AtomicLong lastBandwidthUpdate = new AtomicLong(0);
    private SocketAddress remoteAddress;
    private boolean preDispatched = false;
    private boolean dispatched = false;
    private final Average inboundLatency = new Average();
    private final Average processLatency = new Average();
    private Watch latencyWatch;
    private final boolean ssl;

    @Part
    private static DispatcherPipeline pipeline;

    @Part
    @Nullable
    private static Firewall firewall;

    @ConfigValue("http.maxKeepalive")
    private static int maxKeepalive;

    /**
     * Creates a new instance and initializes some statistics.
     */
    WebServerHandler(boolean ssl) {
        this.ssl = ssl;
        this.connected = System.currentTimeMillis();
    }

    /**
     * Periodically called by {@link sirius.web.http.WebServer.BandwidthUpdater#runTimer()} to recompute the current
     * bandwidth.
     */
    protected void updateBandwidth() {
        long now = System.currentTimeMillis();
        long lastUpdate = lastBandwidthUpdate.get();
        long deltaInSeconds = TimeUnit.SECONDS.convert(now - lastUpdate, TimeUnit.MILLISECONDS);
        if (lastUpdate > 0 && deltaInSeconds > 0) {
            uplink.set(currentBytesIn.get() / deltaInSeconds);
            downlink.set(currentBytesOut.get() / deltaInSeconds);
        }
        currentBytesIn.set(0);
        currentBytesOut.set(0);
        lastBandwidthUpdate.set(now);
    }

    /*
     * Used when this handler is bound to an incoming connection
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        WebServer.addOpenConnection(this);
        this.remoteAddress = ctx.channel().remoteAddress();
        super.channelRegistered(ctx);
    }

    /*
     * Get notified about each exception which occurs while processing channel events
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        if (currentCall != null) {
            CallContext.setCurrent(currentCall);
        }

        String uri = "unknown";
        if (currentContext != null && currentContext.getRequest() != null) {
            uri = currentContext.getRequest().uri();
        }

        if (e instanceof SSLHandshakeException || e.getCause() instanceof SSLHandshakeException) {
            SSLWebServerInitializer.LOG.FINE(e);
        } else if (e instanceof IOException || e instanceof DecoderException) {
            WebServer.LOG.FINE("Received an error for url: %s - %s", uri, NLS.toUserString(e));
        } else {
            Exceptions.handle()
                      .to(WebServer.LOG)
                      .error(e)
                      .withSystemErrorMessage("Received an error for %s - %s (%s)", uri)
                      .handle();
        }

        try {
            if (ctx.channel().isOpen()) {
                ctx.channel().close();
            }
        } catch (Exception t) {
            Exceptions.ignore(t);
        }
        currentRequest = null;
    }

    /*
     * Binds the request to the CallContext
     */
    private WebContext setupContext(ChannelHandlerContext ctx, HttpRequest req) {
        currentCall = initializeContext(ctx, req, this.ssl);
        return currentCall.get(WebContext.class);
    }

    /**
     * Creates a new CallContext for given request.
     *
     * @param ctx   the current handler
     * @param req   the current request
     * @param isSSL true if the current connection is known to be SSL protected
     * @return the newly initialized call context
     */
    protected static CallContext initializeContext(ChannelHandlerContext ctx, HttpRequest req, boolean isSSL) {
        CallContext currentCall = CallContext.initialize();
        currentCall.addToMDC("uri", req.uri());
        WebContext wc = currentCall.get(WebContext.class);
        // If we know we're an SSL endpoint, tell the WebContext, otherwise let the null value remain
        // so that the automatic detection (headers set by an upstream proxy like X-Forwarded-Proto)
        // is performed when needed...
        if (isSSL) {
            wc.ssl = true;
        }
        wc.setCtx(ctx);
        wc.setRequest(req);
        currentCall.get(TaskContext.class).setSystem("HTTP").setJob(wc.getRequestedURI());

        // Adds a deferred handler to determine the language to i18n stuff.
        // If a user is present, the system will sooner or later detect it and set the appropriate
        // language. If not, this handler will be evaluated, check for a user in the session or
        // if everything else fails, parse the lang header.
        currentCall.deferredSetLang(callContext -> {
            if (callContext.get(UserContext.class).bindUserIfPresent(wc).isEmpty()) {
                callContext.setLangIfEmpty(UserContext.getCurrentScope().makeLang(wc.getLang().orElse(null)));
            }
        });

        return currentCall;
    }

    /*
     * Will be notified by the IdleStateHandler if a channel is completely idle for a certain amount of time
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            if (currentCall != null) {
                CallContext.setCurrent(currentCall);
            } else {
                ctx.channel().close();
                return;
            }
            WebContext wc = currentCall.get(WebContext.class);
            if (!wc.isLongCall() && !wc.responseCompleted) {
                if (WebServer.LOG.isFINE()) {
                    WebServer.LOG.FINE("IDLE: " + wc.getRequestedURI());
                }
                if (WebServer.idleTimeouts.incrementAndGet() < 0) {
                    WebServer.idleTimeouts.set(0);
                }
                ctx.channel().close();
            }
        }
    }

    /**
     * Used by responses to determine if keepalive is supported.
     * <p>
     * Internally we used a countdown, to limit the max number of keepalives for a connection. Calling this method
     * decrements the internal counter, therefore this must not be called several times per request.
     * <p>
     * For proxies however, we don't apply any limit to permit best resource utilization.
     *
     * @return <tt>true</tt> if keepalive is still supported, <tt>false</tt> otherwise.
     */
    public boolean shouldKeepAlive() {
        if (!WebServer.getProxyIPs().isEmpty() && WebServer.getProxyIPs()
                                                           .accepts(((InetSocketAddress) this.remoteAddress).getAddress())) {
            return true;
        }

        return numKeepAlive-- > 0;
    }

    /*
     * Called once a connection is closed. Note that due to keep-alive approaches specified by HTTP 1.1, several
     * independent requests can be handled via one connection
     */
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        cleanup();

        WebServer.removeOpenConnection(this);

        // Detach the CallContext we created
        if (currentCall != null) {
            CallContext.setCurrent(currentCall);
            CallContext.detach();
        }
        super.channelUnregistered(ctx);
    }

    /*
     * Notified if a new message is available
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (latencyWatch != null) {
                inboundLatency.addValue(latencyWatch.elapsed(TimeUnit.MILLISECONDS, true));
            } else {
                latencyWatch = Watch.start();
            }
            if (msg instanceof HttpRequest) {
                channelReadRequest(ctx, (HttpRequest) msg);
            } else if (msg instanceof LastHttpContent) {
                channelReadLastHttpContent(ctx, msg);
            } else if (msg instanceof HttpContent) {
                channelReadHttpContent(msg);
            }
        } catch (Exception t) {
            String errorMessage = Exceptions.handle(WebServer.LOG, t).getMessage();
            if (currentRequest != null && currentContext != null) {
                try {
                    if (!currentContext.responseCompleted) {
                        currentContext.respondWith().error(HttpResponseStatus.INTERNAL_SERVER_ERROR, errorMessage);
                    }
                } catch (Exception e) {
                    Exceptions.ignore(e);
                }
                currentRequest = null;
            }
            ctx.channel().close();
        }
        processLatency.addValue(latencyWatch.elapsed(TimeUnit.MILLISECONDS, true));
    }

    private void channelReadHttpContent(Object msg) throws IOException {
        try {
            if (currentRequest == null || currentCall == null) {
                WebServer.LOG.FINE("Ignoring CHUNK without request: " + msg);
                return;
            }
            boolean last = msg instanceof LastHttpContent;
            if (!last && WebServer.chunks.incrementAndGet() < 0) {
                WebServer.chunks.set(0);
            }
            if (currentContext.contentHandler != null) {
                CallContext.setCurrent(currentCall);
                currentContext.contentHandler.handle(((HttpContent) msg).content(), last);
            } else {
                processContent((HttpContent) msg);
            }
        } finally {
            ((HttpContent) msg).release();
        }
    }

    private void channelReadLastHttpContent(ChannelHandlerContext ctx, Object msg) throws Exception {
        channelReadHttpContent(msg);
        if (currentRequest != null && currentCall != null) {
            CallContext.setCurrent(currentCall);
            if (!preDispatched) {
                if (WebContext.corsAllowAll && isPreflightRequest()) {
                    handlePreflightRequest();
                } else {
                    dispatch();
                }
            }
        } else if (!preDispatched) {
            WebServer.LOG.FINE("Terminating a channel for a last http content without a request: " + msg);
            ctx.channel().close();
        }
    }

    private void handlePreflightRequest() {
        String requestHeaders = currentRequest.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
        currentContext.respondWith()
                      .setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,PUT,POST,DELETE")
                      .setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                      .setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS,
                                 requestHeaders == null ? "" : requestHeaders)
                      .status(HttpResponseStatus.OK);
    }

    private boolean isPreflightRequest() {
        if (currentRequest == null || !HttpMethod.OPTIONS.equals(currentRequest.method())) {
            return false;
        }

        return currentRequest.headers().contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
    }

    private void channelReadRequest(ChannelHandlerContext ctx, HttpRequest msg) {
        // Reset stats
        bytesIn.set(0);
        bytesOut.set(0);
        inboundLatency.getAndClear();
        processLatency.getAndClear();
        if (WebServer.requests.incrementAndGet() < 0) {
            WebServer.requests.set(0);
        }

        // Do some housekeeping...
        cleanup();
        preDispatched = false;
        dispatched = false;

        handleRequest(ctx, msg);
    }

    /*
     * Signals that a bad or incomplete request was received
     */
    private void signalBadRequest(ChannelHandlerContext ctx) {
        if (WebServer.clientErrors.incrementAndGet() < 0) {
            WebServer.clientErrors.set(0);
        }
        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
           .addListener(ChannelFutureListener.CLOSE);
        currentRequest = null;
    }

    /*
     * Handles a new request - called once the first chunk of data of a request is available.
     */
    private void handleRequest(ChannelHandlerContext ctx, HttpRequest req) {
        try {
            if (WebServer.LOG.isFINE()) {
                WebServer.LOG.FINE("REQUEST: " + req.uri());
            }

            // Handle a bad request.
            if (!req.decoderResult().isSuccess()) {
                signalBadRequest(ctx);
                return;
            }
            currentRequest = req;
            currentContext = setupContext(ctx, req);

            processRequest(ctx, req);
        } catch (Exception t) {
            Exceptions.handle(WebServer.LOG, t);
            try {
                ctx.channel().close();
            } catch (Exception ex) {
                Exceptions.ignore(ex);
            }
            cleanup();
            currentRequest = null;
        }
    }

    private void processRequest(ChannelHandlerContext ctx, HttpRequest req) {
        try {
            if (checkIfBlockedByIPFilter(ctx, req)) {
                return;
            }

            handle100Continue(ctx, req);

            processRequestMethod(req);
        } catch (Exception t) {
            currentContext.respondWith()
                          .error(HttpResponseStatus.INTERNAL_SERVER_ERROR, Exceptions.handle(WebServer.LOG, t));
            currentRequest = null;
        }
    }

    private void processRequestMethod(HttpRequest req) throws Exception {
        if (HttpMethod.POST.equals(req.method()) || HttpMethod.PUT.equals(req.method())) {
            preDispatched = preDispatch();
            if (!preDispatched) {
                setupContentReceiver(req);
            }
        } else if (!HttpMethod.GET.equals(currentRequest.method())
                   && !HttpMethod.HEAD.equals(currentRequest.method())
                   && !HttpMethod.DELETE.equals(currentRequest.method())
                   && !HttpMethod.OPTIONS.equals(currentRequest.method())) {
            currentContext.respondWith()
                          .error(HttpResponseStatus.BAD_REQUEST,
                                 Strings.apply("Cannot %s as method. Use GET, POST, PUT, HEAD, DELETE, OPTIONS",
                                               req.method().name()));
            currentRequest = null;
        }
    }

    private void setupContentReceiver(HttpRequest req) throws IOException {
        String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (isDecodeableContent(contentType)) {
            if (WebServer.LOG.isFINE()) {
                WebServer.LOG.FINE("POST/PUT-FORM: " + req.uri());
            }
            HttpPostRequestDecoder postDecoder = new HttpPostRequestDecoder(WebServer.getHttpDataFactory(), req);
            currentContext.setPostDecoder(postDecoder);
        } else {
            if (WebServer.LOG.isFINE()) {
                WebServer.LOG.FINE("POST/PUT-DATA: " + req.uri());
            }
            Attribute body = WebServer.getHttpDataFactory().createAttribute(req, "body");
            if (req instanceof FullHttpRequest) {
                body.setContent(((FullHttpRequest) req).content().retain());
            }
            currentContext.content = body;
        }
    }

    private boolean isDecodeableContent(String contentType) {
        return Strings.isFilled(contentType) && (contentType.startsWith("multipart/form-data")
                                                 || contentType.startsWith("application/x-www-form-urlencoded"));
    }

    private void handle100Continue(ChannelHandlerContext ctx, HttpRequest req) {
        if (HttpUtil.is100ContinueExpected(req)) {
            if (WebServer.LOG.isFINE()) {
                WebServer.LOG.FINE("CONTINUE: " + req.uri());
            }
            send100Continue(ctx);
        }
    }

    /**
     * Although the {@link LowLevelHandler} already checked the effective TCP remote IP,
     * we now check again, as the parsed request might contain a X-Forwarded-For header,
     * which contains the effective remote IP to verify.
     *
     * @param ctx the current channel
     * @param req the current request
     * @return <tt>true</tt> if the request was blocked, false otherwise
     */
    private boolean checkIfBlockedByIPFilter(ChannelHandlerContext ctx, HttpRequest req) {
        if (isBlocked(currentContext)) {
            if (WebServer.blocks.incrementAndGet() < 0) {
                WebServer.blocks.set(0);
            }
            if (WebServer.LOG.isFINE()) {
                WebServer.LOG.FINE("BLOCK: " + req.uri());
            }
            ctx.channel().close();
            return true;
        }
        return false;
    }

    private boolean isBlocked(WebContext ctx) {
        if (!WebServer.getIPFilter().isEmpty() && WebServer.getIPFilter().accepts(ctx.getRemoteIP())) {
            return true;
        }

        return firewall != null && firewall.isIPBlacklisted(ctx);
    }

    /*
     * Sends an 100 CONTINUE response to conform to the keepalive protocol
     */
    private void send100Continue(ChannelHandlerContext e) {
        if (WebServer.LOG.isFINE()) {
            WebServer.LOG.FINE("100 - CONTINUE: " + currentContext.getRequestedURI());
        }
        HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        e.writeAndFlush(response);
    }

    /*
     * Releases the last context (request) which was processed by this handler.
     */
    private void cleanup() {
        if (currentContext != null) {
            currentContext.release();
            currentContext = null;
        }
    }

    /*
     * Reads another chunk of data for a previously started request
     */
    private void processContent(HttpContent chunk) {
        try {
            if (currentContext.getPostDecoder() != null) {
                if (WebServer.LOG.isFINE()) {
                    WebServer.LOG.FINE("POST-CHUNK: "
                                       + currentContext.getRequestedURI()
                                       + " - "
                                       + chunk.content()
                                              .readableBytes()
                                       + " bytes");
                }
                currentContext.getPostDecoder().offer(chunk);
            } else if (currentContext.content != null) {
                if (WebServer.LOG.isFINE()) {
                    WebServer.LOG.FINE("DATA-CHUNK: "
                                       + currentContext.getRequestedURI()
                                       + " - "
                                       + chunk.content()
                                              .readableBytes()
                                       + " bytes");
                }
                currentContext.content.addContent(chunk.content().retain(), chunk instanceof LastHttpContent);

                if (!currentContext.content.isInMemory()) {
                    File file = currentContext.content.getFile();
                    checkUploadFileLimits(file);
                }
            } else if (!(chunk instanceof LastHttpContent)
                       && !HttpMethod.POST.equals(currentRequest.method())
                       && !HttpMethod.PUT.equals(currentRequest.method())) {
                currentContext.respondWith()
                              .error(HttpResponseStatus.BAD_REQUEST, "Only POST or PUT may sent chunked data");
                currentRequest = null;
            }
        } catch (Exception ex) {
            currentContext.respondWith()
                          .error(HttpResponseStatus.INTERNAL_SERVER_ERROR, Exceptions.handle(WebServer.LOG, ex));
            currentRequest = null;
        }
    }

    /*
     * Checks if the can still upload more date
     */
    private void checkUploadFileLimits(File file) {
        if (file.getFreeSpace() < WebServer.getMinUploadFreespace() && WebServer.getMinUploadFreespace() > 0) {
            if (WebServer.LOG.isFINE()) {
                WebServer.LOG.FINE("Not enough space to handle: " + currentContext.getRequestedURI());
            }
            currentContext.respondWith()
                          .error(HttpResponseStatus.INSUFFICIENT_STORAGE,
                                 Exceptions.handle()
                                           .withSystemErrorMessage(
                                                   "The web server is running out of temporary space to store the upload")
                                           .to(WebServer.LOG)
                                           .handle());
            currentRequest = null;
        }
        if (file.length() > WebServer.getMaxUploadSize() && WebServer.getMaxUploadSize() > 0) {
            if (WebServer.LOG.isFINE()) {
                WebServer.LOG.FINE("Body is too large: " + currentContext.getRequestedURI());
            }
            currentContext.respondWith()
                          .error(HttpResponseStatus.INSUFFICIENT_STORAGE,
                                 Exceptions.handle()
                                           .withSystemErrorMessage(
                                                   "The uploaded file exceeds the maximal upload size of %d bytes",
                                                   WebServer.getMaxUploadSize())
                                           .to(WebServer.LOG)
                                           .handle());
            currentRequest = null;
        }
    }

    /*
     * Tries to dispatch a POST or PUT request before it is completely received so that the handler can install
     * a ContentHandler to process all incoming data.
     */
    private boolean preDispatch() {
        if (WebServer.LOG.isFINE() && currentContext != null) {
            WebServer.LOG.FINE("DISPATCHING: " + currentContext.getRequestedURI());
        }

        return pipeline.preDispatch(currentContext);
    }

    /*
     * Dispatches the completely read request.
     */
    private void dispatch() {
        if (WebServer.LOG.isFINE() && currentContext != null) {
            WebServer.LOG.FINE("DISPATCHING: " + currentContext.getRequestedURI());
        }
        dispatched = true;
        pipeline.dispatch(currentContext);
        currentRequest = null;
    }

    @Override
    public int getNumKeepAlive() {
        return numKeepAlive;
    }

    @Override
    public String getURL() {
        if (currentContext == null) {
            return "";
        }
        if (currentContext.responseCompleted) {
            return currentContext.getRequestedURI() + " (completed)";
        } else if (currentContext.responseCommitted) {
            return currentContext.getRequestedURI() + " (committed)";
        } else if (preDispatched) {
            return currentContext.getRequestedURI() + " (pre-dispatched)";
        } else if (dispatched) {
            return currentContext.getRequestedURI() + " (dispatched)";
        } else {
            return currentContext.getRequestedURI();
        }
    }

    /*
     * Updates inbound traffic (called via LowLevelHandler)
     */
    protected void inbound(long bytes) {
        bytesIn.addAndGet(bytes);
        currentBytesIn.addAndGet(bytes);
    }

    /*
     * Updates outbound traffic (called via LowLevelHandler)
     */
    protected void outbound(long bytes) {
        bytesOut.addAndGet(bytes);
        currentBytesOut.addAndGet(bytes);
    }

    @Override
    public String getConnectedSince() {
        return TimeUnit.SECONDS.convert(System.currentTimeMillis() - connected, TimeUnit.MILLISECONDS) + "s";
    }

    @Override
    public String getBytesIn() {
        if (bytesIn.get() == 0) {
            return "-";
        }
        return NLS.formatSize(bytesIn.get());
    }

    @Override
    public String getBytesOut() {
        if (bytesOut.get() == 0) {
            return "-";
        }
        return NLS.formatSize(bytesOut.get());
    }

    @Override
    public String getUplink() {
        if (uplink.get() == 0) {
            return "-";
        }
        return NLS.formatSize(uplink.get()) + "/s";
    }

    @Override
    public String getDownlink() {
        if (downlink.get() == 0) {
            return "-";
        }
        return NLS.formatSize(downlink.get()) + "/s";
    }

    @Override
    public String getLatency() {
        return Strings.apply("%1.2f ms / %1.2f ms", inboundLatency.getAvg(), processLatency.getAvg());
    }

    @Override
    public String getRemoteAddress() {
        if (currentContext != null && currentContext.isValid()) {
            return String.valueOf(currentContext.getRemoteIP());
        }

        return String.valueOf(remoteAddress);
    }
}
