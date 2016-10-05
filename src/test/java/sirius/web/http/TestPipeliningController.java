/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.http;

import io.netty.handler.codec.http.HttpResponseStatus;
import sirius.kernel.di.std.Register;
import sirius.kernel.health.HandledException;
import sirius.web.controller.Controller;
import sirius.web.controller.Routed;

@Register
public class TestPipeliningController implements Controller {
    @Override
    public void onError(WebContext ctx, HandledException error) {
        throw new IllegalStateException();
    }

    @Routed("/pipelining/:1")
    public void test(WebContext ctx, String time) throws Exception {
        Thread.sleep(Long.parseLong(time));
        ctx.respondWith().addHeader("URI", ctx.getRequestedURI()).direct(HttpResponseStatus.OK, "OK");
    }
}