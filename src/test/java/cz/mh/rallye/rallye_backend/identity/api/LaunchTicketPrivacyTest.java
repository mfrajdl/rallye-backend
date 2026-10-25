package cz.mh.rallye.rallye_backend.identity.api;

import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.catalina.valves.AccessLogValve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.mock.web.*;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import static org.junit.jupiter.api.Assertions.*;

class LaunchTicketPrivacyTest {
    @TempDir Path temp;
    @Test void detailedRequestLoggingSeesOnlyRedactedQueryParametersRefererAndAttributes() throws Exception {
        String ticket="synthetic-private-ticket";
        var request=new MockHttpServletRequest("GET",LaunchTicketPrivacyFilter.PATH);
        request.setQueryString("ticket="+ticket);request.addParameter("ticket",ticket);
        request.addHeader("Referer","https://backend.example.test"+LaunchTicketPrivacyFilter.PATH+"?ticket="+ticket);
        StringBuilder messages=new StringBuilder();
        var logging=new CommonsRequestLoggingFilter() {
            @Override protected boolean shouldLog(HttpServletRequest r){return true;}
            @Override protected void beforeRequest(HttpServletRequest r,String message){messages.append(message);}
            @Override protected void afterRequest(HttpServletRequest r,String message){messages.append(message);}
        };
        logging.setIncludeQueryString(true);logging.setIncludeHeaders(true);logging.setIncludeClientInfo(true);logging.setIncludePayload(true);
        new LaunchTicketPrivacyFilter().doFilter(request,new MockHttpServletResponse(),(safe,response)->{
            var http=(HttpServletRequest)safe;
            assertEquals(ticket,LaunchTicketPrivacyFilter.ticket(http));
            assertNull(http.getQueryString());assertEquals("[redacted]",http.getParameter("ticket"));
            assertEquals("[redacted]",http.getParameterValues("ticket")[0]);
            assertEquals("[redacted]",http.getParameterMap().get("ticket")[0]);
            for(String key:java.util.Collections.list(http.getAttributeNames()))assertFalse(String.valueOf(http.getAttribute(key)).contains(ticket));
            logging.doFilter(safe,response,(r,s)->{});
        });
        assertFalse(messages.toString().contains(ticket));assertTrue(messages.toString().contains("[redacted]"));
    }
    @Test void earlyHeadersSurviveMissingTicketAndDownstreamRejection() throws Exception {
        var request=new MockHttpServletRequest("GET",LaunchTicketPrivacyFilter.PATH);var response=new MockHttpServletResponse();
        assertThrows(ServletException.class,()->new LaunchTicketPrivacyFilter().doFilter(request,response,(r,s)->{throw new ServletException("injected");}));
        assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));
    }
    @Test void nestedErrorDispatchReappliesHeadersAndRequestRedaction() throws Exception {
        var request=new MockHttpServletRequest("GET",LaunchTicketPrivacyFilter.PATH);var response=new MockHttpServletResponse();
        request.setDispatcherType(DispatcherType.ERROR);request.setAttribute("jakarta.servlet.error.request_uri",LaunchTicketPrivacyFilter.PATH);
        request.setAttribute(LaunchTicketPrivacyFilter.class.getName()+".FILTERED",true);
        request.addParameter("ticket","synthetic-nested-error-ticket");request.setQueryString("ticket=synthetic-nested-error-ticket");
        new LaunchTicketPrivacyFilter().doFilter(request,response,(safe,r)->{
            assertNull(((HttpServletRequest)safe).getQueryString());assertEquals("[redacted]",safe.getParameter("ticket"));
        });
        assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));
    }
    @Test void unsafeAccessLogFormatsCannotStart() {
        for(String format:java.util.List.of("common","combined","%U%q","%{Referer}i","%{Location}o")) {
            var factory=new TomcatServletWebServerFactory(0);var access=new AccessLogValve();access.setPattern(format);factory.addEngineValves(access);
            assertThrows(IllegalStateException.class,()->new LaunchLoggingConfiguration().customize(factory));
        }
    }
    @Test void explicitConnectorWireLoggingCannotBypassSafeParentLevel() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.apache.coyote.http11.Http11InputBuffer");
        var before=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {assertThrows(IllegalStateException.class,()->new LaunchLoggingConfiguration().customize(new TomcatServletWebServerFactory(0)));}
        finally {logger.setLevel(before);}
    }
    @Test void unsafeMvcExceptionAndPayloadDebugLoggingCannotStart() {
        for(String name:java.util.List.of("org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",
                "org.springframework.web.filter.CommonsRequestLoggingFilter")) {
            var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(name);
            var before=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            try {assertThrows(IllegalStateException.class,()->new LaunchLoggingConfiguration().customize(new TomcatServletWebServerFactory(0)));}
            finally {logger.setLevel(before);}
        }
    }
    @Test void realEmbeddedTomcatAccessLogOmitsQueryAndReferer() throws Exception {
        String ticket="synthetic-tomcat-private-ticket";
        var factory=new TomcatServletWebServerFactory(0);factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setBaseDirectory(temp.resolve("tomcat").toFile());
        var access=new AccessLogValve();access.setDirectory(temp.resolve("access").toString());access.setPattern(LaunchLoggingConfiguration.SAFE_PATTERN);
        access.setBuffered(false);access.setPrefix("access");access.setSuffix(".log");access.setFileDateFormat("");factory.addEngineValves(access);
        new LaunchLoggingConfiguration().customize(factory);
        var server=factory.getWebServer(context->{
            var filter=context.addFilter("privacy",new LaunchTicketPrivacyFilter());
            filter.addMappingForUrlPatterns(java.util.EnumSet.of(DispatcherType.REQUEST,DispatcherType.ERROR),false,"/*");
            var servlet=context.addServlet("launch-fixture",new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request,HttpServletResponse response) {
                    assertEquals(ticket,LaunchTicketPrivacyFilter.ticket(request));response.setStatus(303);response.setHeader("Location","https://provider.example.test/authorize?state=synthetic-state");
                }
            });servlet.addMapping(LaunchTicketPrivacyFilter.PATH);
        });
        try {
            server.start();String url="http://127.0.0.1:"+server.getPort()+LaunchTicketPrivacyFilter.PATH;
            var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            var response=client.send(HttpRequest.newBuilder(URI.create(url+"?ticket="+ticket)).header("Referer",url+"?ticket="+ticket).GET().build(),HttpResponse.BodyHandlers.discarding());
            assertEquals(303,response.statusCode());assertEquals("no-referrer",response.headers().firstValue("Referrer-Policy").orElseThrow());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            assertFalse(response.headers().firstValue("Location").orElseThrow().contains(ticket));
        } finally {server.stop();server.destroy();}
        String log=Files.readString(temp.resolve("access/access.log"));
        assertTrue(log.contains(LaunchTicketPrivacyFilter.PATH));assertFalse(log.contains(ticket));assertFalse(log.contains("?ticket="));
    }
}
