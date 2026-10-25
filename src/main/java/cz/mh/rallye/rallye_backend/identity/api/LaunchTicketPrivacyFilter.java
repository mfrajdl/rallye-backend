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

/** Runs before Security and MVC logging for launch and both provider callbacks. Controllers read original input explicitly. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LaunchTicketPrivacyFilter extends OncePerRequestFilter {
    public static final String EXCHANGE="/api/v1/auth/external/mobile/exchanges";
    public static final String PATH="/api/v1/auth/external/mobile/launch";
    private static final String INPUT=LaunchTicketPrivacyFilter.class.getName()+".input";
    private record Input(Map<String,String[]> parameters) {
        @Override public String toString(){return "LaunchInput[redacted]";}
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(PATH) && !path.equals(EXCHANGE) && !path.equals("/api/v1/auth/external/GOOGLE/callback")
                && !path.equals("/api/v1/auth/external/APPLE/callback") && request.getAttribute(INPUT)==null;
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
        if(request.getRequestURI().equals(request.getContextPath()+EXCHANGE) && "POST".equals(request.getMethod())
                && (!"mobile".equals(request.getHeader("X-Rallye-Client")) || AuthCookies.present(request) || request.getHeader("Authorization")!=null)) {
            ApiErrors.write(response,400,"INVALID_REQUEST");return;
        }
        if(request.getAttribute(INPUT)==null) {
            Map<String,String[]> original=new LinkedHashMap<>();
            request.getParameterMap().forEach((key,value)->original.put(key,value.clone()));
            request.setAttribute(INPUT,new Input(Collections.unmodifiableMap(original)));
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
                return ("Referer".equalsIgnoreCase(name)||"Cookie".equalsIgnoreCase(name)) && super.getHeader(name)!=null?"[redacted]":super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                return ("Referer".equalsIgnoreCase(name)||"Cookie".equalsIgnoreCase(name)||"Authorization".equalsIgnoreCase(name))
                    ?Collections.enumeration(super.getHeader(name)==null?List.of():List.of("[redacted]")):super.getHeaders(name);
            }
        },new HttpServletResponseWrapper(response) {
            @Override public String getHeader(String name) {
                return sensitive(name) && super.getHeader(name)!=null?"[redacted]":super.getHeader(name);
            }
            @Override public Collection<String> getHeaders(String name) {
                return sensitive(name) && !super.getHeaders(name).isEmpty()?List.of("[redacted]"):super.getHeaders(name);
            }
            private boolean sensitive(String name){return "Location".equalsIgnoreCase(name)||"Set-Cookie".equalsIgnoreCase(name);}
        });
    }
    static String parameter(HttpServletRequest request,String name) {
        if(!(request.getAttribute(INPUT) instanceof Input input))throw new AuthFailure(400,"INVALID_REQUEST");
        String[] values=input.parameters().get(name);return values==null||values.length==0?null:values[0];
    }
    static String ticket(HttpServletRequest request) {
        String ticket=parameter(request,"ticket");
        if(ticket==null || request.getParameterValues("ticket").length!=1)throw new AuthFailure(400,"INVALID_REQUEST");
        return ticket;
    }
}
