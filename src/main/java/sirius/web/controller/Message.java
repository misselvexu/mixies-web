/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.controller;

import sirius.kernel.commons.Strings;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.PriorityParts;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.HandledException;
import sirius.web.security.UserContext;
import sirius.web.templates.ContentHelper;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Used by the {@link sirius.web.security.UserContext} to collect error or success messages.
 */
public class Message {

    @Part
    private static MessageExpanders messageExpanders;

    @PriorityParts(ErrorMessageTransformer.class)
    private static List<ErrorMessageTransformer> errorMessageTransformers;

    private final MessageLevel type;
    private final String html;

    /**
     * Provides a simple builder/helper to generate a message.
     */
    public static class Builder {

        private final MessageLevel type;

        protected Builder(MessageLevel type) {
            this.type = type;
        }

        /**
         * Specifies a text message to show.
         *
         * @param textMessage the text message to show
         * @return the generated message
         */
        public Message withTextMessage(String textMessage) {
            return new Message(type, ContentHelper.escapeXML(textMessage));
        }

        /**
         * Specifies an HTML message to show.
         *
         * @param htmlMessage the HTML message to show
         * @return the generated message
         */
        public Message withHTMLMessage(String htmlMessage) {
            return new Message(type, htmlMessage);
        }

        /**
         * Specifies a text message followed by a link.
         *
         * @param textMessage the plain text message to show
         * @param label       the label of the link to show
         * @param link        the target of the link. Use <tt>javascript:</tt> as prefix to invoke a JavaScript function
         * @param icon        the (optional) icon to show. This is most probably a fontawesome icon like <tt>fa-refresh</tt>
         * @return the generated message
         */
        public Message withTextAndLink(String textMessage, String label, String link, @Nullable String icon) {
            if (Strings.isFilled(icon)) {
                return new Message(type,
                                   Strings.apply("<span>%s</span><a href=\"%s\"><i class=\"fa %s\"></i> %s</a>",
                                                 ContentHelper.escapeXML(textMessage),
                                                 ContentHelper.escapeXML(link),
                                                 icon,
                                                 ContentHelper.escapeXML(label)));
            } else {
                return new Message(type,
                                   Strings.apply("<span>%s</span><a href=\"%s\">%s</a>",
                                                 ContentHelper.escapeXML(textMessage),
                                                 ContentHelper.escapeXML(link),
                                                 ContentHelper.escapeXML(label)));
            }
        }

        /**
         * Specifies a text message followed by a link to an external target.
         * <p>
         * This behaves just like {@link #withTextAndLink(String, String, String, String)} but the link is opened
         * in a new browser tab or window.
         *
         * @param textMessage the plain text message to show
         * @param label       the label of the link to show
         * @param link        the target of the link. Use <tt>javascript:</tt> as prefix to invoke a JavaScript function
         * @param icon        the (optional) icon to show. This is most probably a fontawesome icon like <tt>fa-refresh</tt>
         * @return the generated message
         */
        public Message withTextAndExternalLink(String textMessage, String label, String link, @Nullable String icon) {
            if (Strings.isFilled(icon)) {
                return new Message(type,
                                   Strings.apply(
                                           "<span>%s</span><a href=\"%s\" target=\"_blank\"><i class=\"fa %s\"></i> %s</a>",
                                           ContentHelper.escapeXML(textMessage),
                                           ContentHelper.escapeXML(link),
                                           icon,
                                           ContentHelper.escapeXML(label)));
            } else {
                return new Message(type,
                                   Strings.apply("<span>%s</span><a href=\"%s\" target=\"_blank\">%s</a>",
                                                 ContentHelper.escapeXML(textMessage),
                                                 ContentHelper.escapeXML(link),
                                                 ContentHelper.escapeXML(label)));
            }
        }
    }

    /**
     * Factory method to create a success message
     *
     * @return a new message with SUCCESS as type
     */
    public static Builder success() {
        return new Builder(MessageLevel.SUCCESS);
    }

    /**
     * Factory method to create a success message
     *
     * @param textMessage the message content
     * @return a new message with the given content and SUCCESS as type
     */
    public static Message success(String textMessage) {
        return success().withTextMessage(textMessage);
    }

    /**
     * Factory method to create an info message
     *
     * @return a new message with INFO as type
     */
    public static Builder info() {
        return new Builder(MessageLevel.INFO);
    }

    /**
     * Factory method to create an info message
     *
     * @param textMessage the message content
     * @return a new message with the given content and INFO as type
     */
    public static Message info(String textMessage) {
        return info().withTextMessage(textMessage);
    }

    /**
     * Factory method to create a warning message.
     *
     * @return a new message with WARN as type
     */
    public static Builder warn() {
        return new Builder(MessageLevel.WARNING);
    }

    /**
     * Factory method to create a warning message
     *
     * @param textMessage the message content
     * @return a new message with the given content and WARN as type
     */
    public static Message warn(String textMessage) {
        return warn().withTextMessage(textMessage);
    }

    /**
     * Factory method to create an error message
     *
     * @return a new message with ERROR as type
     */
    public static Builder error() {
        return new Builder(MessageLevel.PROBLEM);
    }

    /**
     * Factory method to create an error message
     *
     * @param textMessage the message content
     * @return a new message with the given content and ERROR as type
     */
    public static Message error(String textMessage) {
        return error().withTextMessage(textMessage);
    }

    /**
     * Handles the given exception and creates an appropriate error message.
     * <p>
     * Note that this might utilize {@link ErrorMessageTransformer error message transformers} to yield an optimal
     * error message.
     *
     * @param exception the error to handle
     * @return the appropriate error message
     */
    public static Message error(Throwable exception) {
        HandledException handledException = Exceptions.handle(UserContext.LOG, exception);

        String message = ContentHelper.escapeXML(handledException.getMessage());
        for (ErrorMessageTransformer transformer : errorMessageTransformers) {
            message = transformer.transform(handledException, message);
        }

        return error().withHTMLMessage(message);
    }

    /**
     * Directly creates a new message with the given type and raw HTML content.
     *
     * @param type the severity of the message
     * @param html the HTML contents to show
     */
    public Message(MessageLevel type, String html) {
        this.type = type;
        this.html = messageExpanders.expand(html);
    }

    public MessageLevel getType() {
        return type;
    }

    public String getHtml() {
        return html;
    }
}
