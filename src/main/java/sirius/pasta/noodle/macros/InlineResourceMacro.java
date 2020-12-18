/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.pasta.noodle.macros;

import parsii.tokenizer.Position;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Register;
import sirius.pasta.noodle.Environment;
import sirius.pasta.noodle.compiler.CompilationContext;
import sirius.web.resources.Resource;
import sirius.web.resources.Resources;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

/**
 * Inlines a complete resource file into a JavaScript string.
 * <p>
 * Most probably the {@link EscapeJSMacro} wil be used to make the included contents safe in JS.
 * <p>
 * If only a block of JS is to be included (e.g. to aggregate many JS files into one), use either
 * {@link sirius.pasta.tagliatelle.compiler.InvokeHandler} or {@link sirius.pasta.tagliatelle.compiler.IncludeHandler}.
 *
 * @see sirius.pasta.tagliatelle.compiler.InvokeHandler
 * @see sirius.pasta.tagliatelle.compiler.IncludeHandler
 * @see sirius.pasta.noodle.macros.EscapeJSMacro
 */
@Register
public class InlineResourceMacro extends BasicMacro {

    @Part
    private static Resources resources;

    @Override
    public Class<?> getType() {
        return String.class;
    }

    @Override
    public void verifyArguments(CompilationContext context, Position pos, List<Class<?>> args) {
        if (args.size() != 1 || !CompilationContext.isAssignableTo(args.get(0), String.class)) {
            throw new IllegalArgumentException("Expected a single String as argument.");
        }
    }

    @Override
    public Object invoke(Environment environment, Object[] args) {
        String path = (String) args[0];
        if (!path.startsWith("/assets")) {
            throw new IllegalArgumentException("Only assets can be inlined for security reasons.");
        }

        Optional<Resource> res = resources.resolve(path);
        return res.map(Resource::getContentAsString).orElse("");
    }

    @Nonnull
    @Override
    public String getName() {
        return "inlineResource";
    }

    @Override
    public String getDescription() {
        return "Returns the contents of the given asset as string. Use escapeJS() to place the data in a JS string.";
    }
}
