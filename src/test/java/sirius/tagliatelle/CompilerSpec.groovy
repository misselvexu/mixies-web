/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.tagliatelle

import sirius.kernel.BaseSpecification
import sirius.kernel.commons.Strings
import sirius.kernel.di.std.Part
import sirius.web.resources.Resources

class CompilerSpec extends BaseSpecification {

    @Part
    private static Tagliatelle tagliatelle
    @Part
    private static Resources resources

    private boolean basicallyEqual(String left, String right) {
        return Strings.areEqual(left.replaceAll("\\s", ""), right.replaceAll("\\s", ""))
    }

    def "nesting of { brackets works as expected"() {
        given:
        String expectedResult = resources.resolve("templates/brackets.html").get().getContentAsString()
        when:
        String result = tagliatelle.resolve("templates/brackets.html.pasta").get().renderToString()
        then:
        basicallyEqual(result, expectedResult)
    }

    def "dynamicInvoke works"() {
        given:
        String expectedResult = resources.resolve("templates/dynamic-invoke.html").get().getContentAsString()
        when:
        String result = tagliatelle.resolve("templates/dynamic-invoke-outer.html.pasta").get().renderToString()
        then:
        basicallyEqual(result, expectedResult)
    }

}
