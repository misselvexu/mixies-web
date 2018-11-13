/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.tagliatelle.macros;

import sirius.kernel.Sirius;
import sirius.kernel.commons.Strings;
import sirius.kernel.di.std.Register;
import sirius.kernel.nls.NLS;
import sirius.tagliatelle.Tagliatelle;
import sirius.tagliatelle.expression.ConstantString;
import sirius.tagliatelle.expression.Expression;
import sirius.tagliatelle.rendering.LocalRenderContext;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Represents <tt>i18n(String)</tt> which is essentially a call to {@link NLS#get(String)}.
 */
@Register
public class I18nMacro implements Macro {
    @Override
    public Class<?> getType() {
        return String.class;
    }

    /**
     * Verifies the argument type.
     * <p>
     * If the i18n key is constant, it also ensures that a matching translation is present.
     *
     * @param args the expressions which will be passed in at runtime.
     */
    @Override
    public void verifyArguments(List<Expression> args) {
        if (args.size() != 1 || !Tagliatelle.isAssignableTo(args.get(0).getType(), String.class)) {
            throw new IllegalArgumentException("Expected a single String as argument.");
        }

        if (Sirius.isDev()) {
            Expression exp = args.get(0);
            if (exp instanceof ConstantString) {
                String key = (String) exp.eval(null);
                if (Strings.isFilled(key) && NLS.getTranslationEngine()
                                                .getEntriesStartingWith(key)
                                                .noneMatch(t -> key.equals(t.getKey()))) {
                    throw new IllegalArgumentException(Strings.apply("No translation found for key: %s", key));
                }
            }
        }
    }

    @Override
    public boolean isConstant(Expression[] args) {
        // An i18n macro is inherently not constant unless it is invoked for an empty string
        return args.length == 1 && args[0].isConstant() && Strings.isEmpty(args[0].eval(null));
    }

    @Override
    public Object eval(LocalRenderContext ctx, Expression[] args) {
        String key = (String) args[0].eval(ctx);
        if (Strings.isFilled(key)) {
            return NLS.get(key);
        } else {
            return "";
        }
    }

    @Nonnull
    @Override
    public String getName() {
        return "i18n";
    }

    @Override
    public String getDescription() {
        return "Returns the translation for the given key in the currently active language.";
    }
}
