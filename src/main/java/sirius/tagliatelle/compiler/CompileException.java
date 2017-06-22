/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.tagliatelle.compiler;

import sirius.tagliatelle.Template;

import java.util.List;

/**
 * Thrown to indicate one or more {@link CompileError compilation errors}.
 * <p>
 * As the compiler is quite optimistic, it keeps compiling as long as possible instead of aborting for the first error.
 * Therefore a list of errors is given here which is also appropriately formatted in the exception message.
 */
public class CompileException extends Exception {

    private static final long serialVersionUID = -8697032594602395681L;

    private final transient Template template;
    private final transient List<CompileError> errors;

    private CompileException(String message, Template template, List<CompileError> errors) {
        super(message);
        this.template = template;
        this.errors = errors;
    }

    /**
     * Creates a new exception based on the list of errors.
     *
     * @param errors the errors which occurred while processing the user input
     * @return a new CompileException which can be thrown
     */
    public static CompileException create(Template template, List<CompileError> errors) {
        StringBuilder message = new StringBuilder();
        message.append("Cannot compile: ").append(template.getName());
        if (template.getResource() != null) {
            message.append(" (").append(template.getResource().getUrl()).append(")");
        }
        message.append("):\n");
        errors.forEach(message::append);

        return new CompileException(message.toString(), template, errors);
    }

    /**
     * Provides a list of all errors and warnings which occurred
     *
     * @return all errors and warnings which occurred while processing the user input
     */
    public List<CompileError> getErrors() {
        return errors;
    }

    /**
     * Returns the template for which the compilation failed.
     *
     * @return the template which was compiled
     */
    public Template getTemplate() {
        return template;
    }
}

