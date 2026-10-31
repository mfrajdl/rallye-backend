package cz.mh.rallye.rallye_backend.participation.api;
import java.io.IOException;
import jakarta.servlet.*;import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.core.Ordered;import org.springframework.core.annotation.Order;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;
import cz.mh.rallye.rallye_backend.participation.application.CrewStartAttempts;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.api.ApiErrors;
/** Source quota covers anonymous and malformed bodies once, before Security/MVC. Account quota is in the service. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+2)
public class CrewStartThrottleFilter extends OncePerRequestFilter {
 private final ObjectProvider<CrewStartAttempts> attempts;public CrewStartThrottleFilter(ObjectProvider<CrewStartAttempts> attempts){this.attempts=attempts;}
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !"POST".equals(r.getMethod())||!r.getRequestURI().substring(r.getContextPath().length()).matches("/api/v1/race-events/[^/]+/registrations/[^/]+/code-redemptions");}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{try{attempts.getObject().source(r.getRemoteAddr());}catch(AuthFailure failure){ApiErrors.write(response,failure.status(),failure.code());return;}chain.doFilter(r,response);}
}
