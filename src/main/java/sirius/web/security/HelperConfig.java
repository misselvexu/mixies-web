/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field in a helper class as filled via the config of the current scope.
 * <p>
 * A helper is generated by a {@link HelperFactory} and obtained by calling {@link UserContext#getHelper(Class)}
 * or {@link ScopeInfo#getHelper(Class)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface HelperConfig {

    /**
     * Returns the key or path within the scope config ({@link ScopeInfo#getSettings()} which should be used
     * to populate the field.
     *
     * @return the path within the scope config to read
     */
    String value();
}
