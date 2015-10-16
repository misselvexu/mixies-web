/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.http

import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpMethod
import sirius.kernel.BaseSpecification
import sirius.kernel.xml.Outcall

class CORSSpec extends BaseSpecification {

    def "expect 'Access-Control-Allow-Origin' for requests with 'origin'"() {
        when:
        // Allow us to set the Origin: header...
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        Outcall
        HttpURLConnection c = new URL("http://localhost:9999/system/ok").openConnection();
        c.addRequestProperty("Origin", "TEST");

        then:
        c.getInputStream().close();
        c.getHeaderField(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN) == "TEST"
    }

    def "expect a CORS preflight request to be answered correctly"() {
        when:
        // Allow us to set the Origin: header...
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        Outcall
        HttpURLConnection c = new URL("http://localhost:9999/some/url").openConnection();
        c.setRequestMethod(HttpMethod.OPTIONS.name());
        c.addRequestProperty("Origin", "TEST");
        c.addRequestProperty("Access-Control-Request-Method", "GET");
        c.addRequestProperty("Access-Control-Request-Headers", "X-Test");

        then:
        c.getInputStream().close();
        c.getHeaderField(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN) == "TEST"
        c.getHeaderField(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_METHODS).indexOf("GET") >= 0
        c.getHeaderField(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_HEADERS).indexOf("X-Test") >= 0
    }

}