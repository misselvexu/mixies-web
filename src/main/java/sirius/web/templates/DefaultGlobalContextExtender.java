/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.templates;

import sirius.kernel.Sirius;
import sirius.kernel.async.CallContext;
import sirius.kernel.di.std.ConfigValue;
import sirius.kernel.di.std.Register;
import sirius.kernel.info.Product;
import sirius.kernel.nls.NLS;
import sirius.web.http.WebContext;
import sirius.web.security.UserContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.BiConsumer;

/**
 * Provides access to commonly used global variables.
 */
@Register
public class DefaultGlobalContextExtender implements GlobalContextExtender {

    @ConfigValue("product.wondergemRoot")
    private String wondergemRoot;

    @ConfigValue("product.tagLine")
    private String tagLine;

    private String detailedVersion;

    @Override
    public void collectTemplate(BiConsumer<String, Object> parameterCollector) {
        CallContext ctx = CallContext.getCurrent();
        parameterCollector.accept("user", ctx.get(UserContext.class));
        parameterCollector.accept("product", Product.getProduct().getName());
        parameterCollector.accept("now", LocalDateTime.now());
        parameterCollector.accept("today", LocalDate.now());
        parameterCollector.accept("detailedVersion", getDetailedVersion());
        parameterCollector.accept("nodeName", CallContext.getNodeName());
        parameterCollector.accept("isDev", Sirius.isDev());
        parameterCollector.accept("call", ctx.get(WebContext.class));
        parameterCollector.accept("watch",ctx.getWatch());
        parameterCollector.accept("lang", NLS.getCurrentLang());
        parameterCollector.accept("wondergemRoot", wondergemRoot);
        parameterCollector.accept("tagLine", tagLine);
        parameterCollector.accept("dateFormat", NLS.get("RythmConfig.jsDateFormat"));
        parameterCollector.accept("timeFormat", NLS.get("RythmConfig.jsTimeFormat"));
    }

    @Override
    public void collectScripting(BiConsumer<String, Object> globalParameterCollector) {
        // Nothing provided
    }

    private String getDetailedVersion() {
        if (detailedVersion == null) {
            detailedVersion = Product.getProduct().getDetails();
        }

        return detailedVersion;
    }

}