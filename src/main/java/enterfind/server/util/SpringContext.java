package enterfind.server.util;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Bridge that lets non-bean code reach Spring-managed beans.
 *
 * <p>The {@link enterfind.server.scan.Treatments} enum is a JVM singleton and cannot itself be a
 * Spring bean, but each constant delegates to a handler that is one. This holder captures the
 * {@link ApplicationContext} on refresh so the enum can resolve the current handler bean at call
 * time via {@link #getBean(Class)}.
 *
 * <p>The reference is looked up per call rather than cached: in tests several application contexts
 * may be built in one JVM, and {@code setApplicationContext} always leaves this pointing at the
 * most recently refreshed one, so callers never bind to a stale, closed context.
 */
@Component
public class SpringContext implements ApplicationContextAware {

    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> beanClass) {
        ApplicationContext ctx = context;
        if (ctx == null) {
            throw new IllegalStateException(
                "Spring ApplicationContext is not initialized yet; cannot resolve " + beanClass.getName());
        }
        return ctx.getBean(beanClass);
    }
}
