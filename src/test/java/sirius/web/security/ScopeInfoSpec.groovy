/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.security

import sirius.kernel.BaseSpecification

/**
 * Created by aha on 16.09.16.
 */
class ScopeInfoSpec extends BaseSpecification {

    def "default config is loaded"() {
        when:
        List<String> test = UserContext.getCurrentScope().getDefaultScopeConfigFiles();
        then:
        test.size() >= 1;
        and:
        test.indexOf("test") >= 0;
    }

    def "default config is read"() {
        when:
        String value = UserContext.getConfig().getString("settings.test");
        then:
        value == "Hello";
    }

    def "default config original contents are available"() {
        when:
        String value = UserContext.getCurrentScope().getDefaulScopeConfigContents("test");
        then:
        value == "# Test\nsettings.test =\"Hello\"";
    }

}
