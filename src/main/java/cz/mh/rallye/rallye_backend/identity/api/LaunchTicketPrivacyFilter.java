package cz.mh.rallye.rallye_backend.identity.api;

import java.io.IOException;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before Security and MVC/request logging. Only the launch controller can read the original input. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LaunchTicketPrivacyFilter extends OncePerRequestFilter {
    public static final String PATH="/api/v1/auth/external/mobile/launch";
    private static final String INPUT=LaunchTicketPrivacyFilter.class.getName()+".input";
    private record Input(String[] tickets) {
        @Override public String toString(){return "LaunchInput[redacted]";}
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath()+PATH) && request.getAttribute(INPUT)==null;
    }
    @Override protected boolean shouldNotFilterErrorDispatch(){return false;}
    @Override protected void doFilterNestedErrorDispatch(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        // Some containers restore the original request and clear response headers during sendError.
        doFilterInternal(request,response,chain);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
        if(request.getAttribute(INPUT)==null) {
            String[] values=request.getParameterValues("ticket");
            request.setAttribute(INPUT,new Input(values==null?null:values.clone()));
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getQueryString(){return null;}
            @Override public String getParameter(String name){return super.getParameter(name)==null?null:"[redacted]";}
            @Override public String[] getParameterValues(String name) {
                String[] values=super.getParameterValues(name);
                if(values==null)return null;
                String[] safe=new String[values.length];Arrays.fill(safe,"[redacted]");return safe;
            }
            @Override public Map<String,String[]> getParameterMap() {
                Map<String,String[]> safe=new LinkedHashMap<>();
                super.getParameterMap().forEach((key,value)->{String[] masked=new String[value.length];Arrays.fill(masked,"[redacted]");safe.put(key,masked);});
                return Collections.unmodifiableMap(safe);
            }
            @Override public String getHeader(String name) {
                return "Referer".equalsIgnoreCase(name) && super.getHeader(name)!=null?"[redacted]":super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                return "Referer".equalsIgnoreCase(name)?Collections.enumeration(super.getHeader(name)==null?List.of():List.of("[redacted]")):super.getHeaders(name);
            }
        },response);
    }
    static String ticket(HttpServletRequest request) {
        if(!(request.getAttribute(INPUT) instanceof Input input) || input.tickets()==null || input.tickets().length!=1)
            throw new AuthFailure(400,"INVALID_REQUEST");
        return input.tickets()[0];
    }
}
