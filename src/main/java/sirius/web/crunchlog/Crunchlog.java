/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.crunchlog;

import io.netty.handler.codec.http.HttpHeaderNames;
import sirius.kernel.async.CallContext;
import sirius.kernel.commons.Callback;
import sirius.kernel.commons.Context;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Register;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.Log;
import sirius.web.http.WebContext;
import sirius.web.security.UserContext;
import sirius.web.security.UserInfo;

import javax.annotation.CheckReturnValue;
import java.io.File;
import java.util.function.Consumer;

/**
 * Provides a facility to write large amounts of semi structured data into log files to be queried later.
 * <p>
 * The Crunchlog manages a directory stucture where all submitted records are stored in compressed files. These contain
 * a JSON object per line which represents the map that was submitted. Being a map, records with varying attributes
 * can be stored. The crunchlog will start a file per day, and limit the file size to a sane value (10 MB). Being
 * JSON files, processing of the generated files is quite easy. The files are compressed as JSON contains a lot
 * of reoccuring elements. Also many attribute names will be used frequently.
 * <p>
 * The implementation of the Crunchlog is put into {@link CrunchlogKernel} as some frameworks which require public
 * methods are utilized.
 * <p>
 * The Crunchlog contains some safety switches to disable itself if either no valid base directory is present or if
 * the underlying disk runs out of free space.
 * <p>
 * Note that crunchlog files older than one year are deleted by {@link DeleteOldCrunchlogs}. If files need to be kept
 * longer, {@link #collectAllCompletedFiles(Consumer)} can be invoked to collect all available files and store them in
 * a permanent location. The given consumer may also delete the files by itself, to free up disk space.
 * <p>
 * To disable the crunchlog entirely, set <tt>frameworks.crunchlog = false</tt> in the system config.
 */
@Deprecated
@Register(classes = Crunchlog.class)
public class Crunchlog {

    protected static final Log LOG = Log.get("crunchlog");

    /**
     * This field will be automatically added to each record, containing the timestamp when is was logged.
     */
    public static final String FIELD_DATE = "date";

    /**
     * It is highly recommended to add a value for this field in each record to better support filtering for
     * similar events.
     */
    public static final String FIELD_TYPE = "type";

    @Part
    private CrunchlogKernel crunchlogKernel;

    /**
     * Creates a new builder for a log entry in the crunchlog.
     */
    public class LogBuilder {

        private Context context;

        protected LogBuilder(String event) {
            this.context = Context.create();
            this.context.set("event", event);
            this.context.set("name", CallContext.getNodeName());
        }

        /**
         * Adds the id of the current user and tenant to the log entry.
         *
         * @return the builder itself for fluent method calls
         */
        @CheckReturnValue
        public LogBuilder withUser() {
            UserInfo user = UserContext.getCurrentUser();
            if (user.isLoggedIn()) {
                set("user", user.getUserId());
                set("tenant", user.getTenantId());
            } else {
                set("user", null);
                set("tenant", null);
            }

            return this;
        }

        /**
         * Adds the id of the current scope to the log entry.
         *
         * @return the builder itself for fluent method calls
         */
        @CheckReturnValue
        public LogBuilder withScope() {
            set("scope", UserContext.getCurrentScope().getScopeId());

            return this;
        }

        /**
         * Adds the uri of the given request.
         *
         * @param ctx the request to log
         * @return the builder itself for fluent method calls
         */
        @CheckReturnValue
        public LogBuilder withURI(WebContext ctx) {
            set("uri", ctx.getRequestedURI());
            return this;
        }

        /**
         * Adds the uri and the user agent of the given request.
         *
         * @param ctx the request to log
         * @return the builder itself for fluent method calls
         */
        @CheckReturnValue
        public LogBuilder withURIAndUserAgent(WebContext ctx) {
            if (ctx != null) {
                set("uri", ctx.getRequestedURI());
                set("userAgent", ctx.getHeader(HttpHeaderNames.USER_AGENT));
            }
            return this;
        }

        /**
         * Sets a given field and value in the log entry.
         *
         * @param key   the key of the value to log
         * @param value the value to log
         * @return the builder itself for fluent method calls
         */
        @CheckReturnValue
        public LogBuilder set(String key, Object value) {
            if (context == null) {
                throw new IllegalArgumentException("LogBuilder of Crunchlog was already submitted.");
            }

            context.set(key, value);
            return this;
        }

        /**
         * Submits the constructed entry to the crunchlog.
         */
        public void submit() {
            if (context == null) {
                throw new IllegalArgumentException("LogBuilder of Crunchlog was already submitted.");
            }

            Crunchlog.this.submit(context);
            this.context = null;
        }
    }

    /**
     * Creates a new builder to create a log message using fluid method calls.
     * <p>
     * Consider using {@link #safeLog(String, Callback)} to perform logging in a critical section.
     *
     * @param event the event name to log
     * @return a builder to create and submit a log message
     */
    @CheckReturnValue
    public LogBuilder log(String event) {
        return new LogBuilder(event);
    }

    /**
     * Implements a safe way of logging to the crunchlog.
     * <p>
     * As the crunchlog might be involved in critical operations, where a failure during logging must to abort the
     * actual operation, this method creates (<b>and submits</b>) a {@link LogBuilder} while surrounding all calls
     * with a try / catch block, which logs all errors, but does not throw an exception.
     *
     * @param event the event to log
     * @param log   the consumer to actually apply the logging
     */
    public void safeLog(String event, Callback<LogBuilder> log) {
        LogBuilder builder = log(event);
        try {
            log.invoke(builder);
            builder.submit();
        } catch (Exception e) {
            Exceptions.handle(LOG, e);
        }
    }

    /**
     * Submits a record to the crunchlog which will be eventually persisted to disk.
     *
     * @param data the record to persist
     */
    public void submit(Context data) {
        if (crunchlogKernel != null) {
            crunchlogKernel.addToBuffer(data);
        }
    }

    /**
     * Iterates over all completely written files (excluding the one currently being written to).
     * <p>
     * This could be used to upload all files to another repository for further processing. The consumer may delete
     * the files to better manage disk usage.
     *
     * @param fileProcessor a consumer which will be supplied with all completely written files of the Crunchlog
     */
    public void collectAllCompletedFiles(Consumer<File> fileProcessor) {
        if (crunchlogKernel != null) {
            crunchlogKernel.collectAllCompletedFiles(fileProcessor);
        }
    }
}
