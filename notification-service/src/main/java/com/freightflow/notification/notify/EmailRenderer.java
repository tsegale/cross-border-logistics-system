package com.freightflow.notification.notify;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders one email in the organisation's language from Thymeleaf templates (ADR 0027): {@code mail/html/*.html}
 * and a plain-text alternative {@code mail/text/*.txt}, with the words from {@code mail/messages_<lang>.properties}.
 * Every kind shares one layout; what differs per kind is its subject, its opening sentence and its facts.
 */
@Component
public class EmailRenderer {

    /** One labelled line of an email, e.g. "Betrag: 1.840,50 €". */
    public record Fact(String labelKey, String value) {
    }

    public record Email(String subject, String text, String html) {
    }

    private final SpringTemplateEngine engine;
    private final MessageSource messages;

    public EmailRenderer(MessageSource messages) {
        this.messages = messages;
        this.engine = new SpringTemplateEngine();
        engine.addTemplateResolver(resolver(".html", TemplateMode.HTML, "html/*", 1));
        engine.addTemplateResolver(resolver(".txt", TemplateMode.TEXT, "text/*", 2));
        engine.setTemplateEngineMessageSource(messages);
    }

    /**
     * @param kind  picks the subject {@code subject.<kind>} and opening {@code intro.<kind>}
     * @param args  the message arguments of both, already formatted for {@code locale}
     * @param facts the labelled lines below the opening, values already formatted
     */
    public Email render(NotificationKind kind, Locale locale, Object[] args, List<Fact> facts) {
        String subject = messages.getMessage("subject." + kind.code(), args, locale);
        Context context = new Context(locale, Map.of(
                "subject", subject,
                "intro", messages.getMessage("intro." + kind.code(), args, locale),
                "facts", facts.stream().map(fact -> Map.of(
                        "label", messages.getMessage(fact.labelKey(), null, locale),
                        "value", fact.value())).toList(),
                "lang", locale.getLanguage()));
        return new Email(subject, engine.process("text/notification", context),
                engine.process("html/notification", context));
    }

    private static ClassLoaderTemplateResolver resolver(String suffix, TemplateMode mode, String pattern, int order) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setResolvablePatterns(Set.of(pattern));
        resolver.setOrder(order);
        resolver.setCacheable(true);
        return resolver;
    }
}
