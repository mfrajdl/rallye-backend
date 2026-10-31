package cz.mh.rallye.rallye_backend.participation.api;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Privacy headers also cover Security/CSRF/MVC error paths before controllers run. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+1)
public class StartCodePrivacyFilter extends OncePerRequestFilter {
 @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=request.getRequestURI().substring(request.getContextPath().length());return !path.startsWith("/api/v1/race-participations/")&&!path.matches("/api/v1/race-events/[^/]+/registrations/[^/]+/(start-codes(?:/[^/]+)?|replacement-codes|start-status|code-redemptions|devices/[^/]+/revocations)");}
 @Override protected boolean shouldNotFilterErrorDispatch(){return false;}
 @Override protected void doFilterNestedErrorDispatch(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{doFilterInternal(request,response,chain);}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");chain.doFilter(request,response);}
}
