/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.services;

import sirius.kernel.xml.StructuredOutput;
import sirius.web.http.WebContext;

/**
 * JSON encoder for calls to a {@link StructuredService}.
 *
 * @deprecated the whole StructuredService framework has been deprecated.
 */
@Deprecated
class JSONServiceCall extends ServiceCall {

    JSONServiceCall(WebContext ctx) {
        super(ctx);
    }

    @Override
    protected StructuredOutput createOutput() {
        return ctx.respondWith().json();
    }
}
