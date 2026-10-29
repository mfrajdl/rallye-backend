package cz.mh.rallye.rallye_backend.identity.api;

import org.apache.catalina.valves.AbstractAccessLogValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Fail closed rather than silently allow common/combined access logs to record bootstrap URLs. */
@Component
public class LaunchLoggingConfiguration implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>,Ordered {
    public static final String SAFE_PATTERN="%h %l %u %t \"%m %U %H\" %s %b";
    @Override public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
    @Override public void customize(TomcatServletWebServerFactory factory) {
        var valves=new java.util.ArrayList<>(factory.getEngineValves());valves.addAll(factory.getContextValves());
        for(var valve:valves)if(valve instanceof AbstractAccessLogValve access && !SAFE_PATTERN.equals(access.getPattern()))
            throw new IllegalStateException("Unsafe Tomcat access log pattern. Use the documented path-only pattern; query, request line and header logging are prohibited.");
        var sensitive=java.util.List.of("org.apache.coyote","org.apache.tomcat.util.net","org.apache.tomcat.util.http","org.apache.catalina.connector",
                "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",
                "org.springframework.web.filter.CommonsRequestLoggingFilter",
                "org.springframework.jdbc.core.StatementCreatorUtils");
        for(String name:sensitive)
            if(org.slf4j.LoggerFactory.getLogger(name).isDebugEnabled())
                throw new IllegalStateException("HTTP payload/exception DEBUG/TRACE logging is prohibited for authentication privacy.");
        // Explicit child logger settings must not bypass the parent-level checks.
        if(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context)
            for(var logger:context.getLoggerList())
                if(sensitive.stream().anyMatch(name->logger.getName().equals(name)||logger.getName().startsWith(name+".")) && logger.isDebugEnabled())
                    throw new IllegalStateException("HTTP payload/exception DEBUG/TRACE logging is prohibited for authentication privacy.");
    }
}
