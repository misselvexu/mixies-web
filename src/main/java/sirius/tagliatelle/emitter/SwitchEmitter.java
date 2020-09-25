/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.tagliatelle.emitter;

import parsii.tokenizer.Position;
import sirius.kernel.commons.Strings;
import sirius.tagliatelle.expression.Expression;
import sirius.tagliatelle.expression.ExpressionVisitor;
import sirius.tagliatelle.rendering.LocalRenderContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Represents a switch block where inner blocks are only rendered if their name matche the switch expression.
 *
 * @see sirius.tagliatelle.tags.SwitchTag
 */
public class SwitchEmitter extends Emitter {

    protected Map<String, Emitter> blocks;
    protected Expression switchExpression;

    /**
     * Creates a new emitter for the given position.
     *
     * @param startOfBlock the position where the switch block was defined.
     */
    public SwitchEmitter(Position startOfBlock) {
        super(startOfBlock);
    }

    public void setBlocks(Map<String, Emitter> blocks) {
        this.blocks = blocks;
    }

    public void setSwitchExpression(Expression switchExpression) {
        this.switchExpression = switchExpression;
    }

    @Override
    protected void emitToContext(LocalRenderContext context) throws Exception {
        if (switchExpression == null) {
            return;
        }

        String block = String.valueOf(switchExpression.eval(context));
        if (Strings.isFilled(block)) {
            blocks.getOrDefault(block, ConstantEmitter.EMPTY).emitToContext(context);
        }
    }

    @Override
    public Emitter copy() {
        SwitchEmitter copy = new SwitchEmitter(startOfBlock);
        copy.switchExpression = switchExpression.copy();

        if (blocks != null) {
            copy.blocks = new HashMap<>();
            for (Map.Entry<String, Emitter> e : blocks.entrySet()) {
                copy.blocks.put(e.getKey(), e.getValue().copy());
            }
        }

        return copy;
    }

    /**
     * Reduces the switch expression as well as the inner blocks.
     * <p>
     * If the switch expression becomes constant, the emitter is reduced the the respective blocks to render.
     *
     * @return either the emitter itself or, if the expression is constant, the inner blocks
     */
    @Override
    public Emitter reduce() {
        this.switchExpression = switchExpression.reduce();
        if (switchExpression.isConstant()) {
            Object value = switchExpression.eval(null);
            if (Strings.isEmpty(value) || !blocks.containsKey(value)) {
                return ConstantEmitter.EMPTY;
            }

            return blocks.get(value).reduce();
        }

        if (blocks != null) {
            Map<String, Emitter> copy = new HashMap<>();
            for (Map.Entry<String, Emitter> e : blocks.entrySet()) {
                copy.put(e.getKey(), e.getValue().reduce());
            }
            this.blocks = copy;
        }

        return this;
    }

    @Override
    public void visitExpressions(Function<Position, ExpressionVisitor> visitorSupplier) {
        switchExpression = switchExpression.propagateVisitor(visitorSupplier.apply(getStartOfBlock()));
        if (blocks != null) {
            this.blocks.values().forEach(e -> e.visitExpressions(visitorSupplier));
        }
    }

    @Override
    public String toString() {
        return "switch on " + switchExpression;
    }
}
