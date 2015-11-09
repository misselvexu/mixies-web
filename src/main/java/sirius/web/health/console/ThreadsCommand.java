/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.health.console;

import sirius.kernel.async.CallContext;
import sirius.kernel.commons.Tuple;
import sirius.kernel.commons.Value;
import sirius.kernel.di.std.Register;

import javax.annotation.Nonnull;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.Optional;

/**
 * Console command which reports all running threads.
 */
@Register
public class ThreadsCommand implements Command {

    private ThreadMXBean t = ManagementFactory.getThreadMXBean();

    @Override
    public void execute(Output output, String... params) throws Exception {
        boolean withTraces = Value.indexOf(0, params).isFilled();
        boolean includeWaiting = "Y".equals(Value.indexOf(1, params).asString());
        if (withTraces) {
            for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
                ThreadInfo info = t.getThreadInfo(thread.getKey().getId());
                if ("all".equalsIgnoreCase(params[0]) || thread.getKey()
                                                               .getName()
                                                               .toLowerCase()
                                                               .contains(params[0].toLowerCase())) {
                    if (includeWaiting || !isWaitingOrNative(thread.getKey(), info)) {
                        output.blankLine();
                        output.line(thread.getKey().getName() + " (" + thread.getKey().getState() + ")");
                        output.separator();
                        for (StackTraceElement e : thread.getValue()) {
                            output.apply("%-60s %19s",
                                         e.getClassName() + "." + e.getMethodName(),
                                         e.getFileName() + ":" + e.getLineNumber());
                        }
                        output.separator();
                        Optional<CallContext> cc = CallContext.getContext(thread.getKey().getId());
                        if (cc.isPresent()) {
                            output.line("Mapped Diagnostic Context");
                            output.separator();
                            for (Tuple<String, String> e : cc.get().getMDC()) {
                                output.apply("%-20s %59s", e.getFirst(), e.getSecond());
                            }
                            output.apply("Flow duration: %s", cc.get().getWatch().duration());
                        }
                        output.blankLine();
                    }
                }
            }
        } else {
            output.line("Usage: threads [<filter> (or 'all')] [Y=include WAITING/NATIVE]");
            output.separator();
            output.apply("%-15s %10s %53s", "STATE", "ID", "NAME");
            output.separator();
            for (ThreadInfo info : t.dumpAllThreads(false, false)) {
                output.apply("%-15s %10s %53s",
                             info.isInNative() ? "NATIVE" : info.getThreadState().name(),
                             info.getThreadId(),
                             info.getThreadName());
            }
            output.separator();
        }
    }

    private boolean isWaitingOrNative(Thread thread, ThreadInfo info) {
        return (info != null && info.isInNative())
               || thread.getState() == Thread.State.WAITING
               || thread.getState() == Thread.State.TIMED_WAITING;
    }

    @Override
    @Nonnull
    public String getName() {
        return "threads";
    }

    @Override
    public String getDescription() {
        return "Reports a list of all threads.";
    }
}
