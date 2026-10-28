package cz.mh.rallye.rallye_backend.identity.api;

import java.io.IOException;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ProblemDetail> auth(AuthFailure failure) {
        if(failure.status()>=500) logServerFailure(failure);
        var builder = ResponseEntity.status(failure.status());
        if (failure.status() == 429) builder.header("Retry-After", "900");
        return builder.body(problem(failure.status(), failure.code()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(problem(400, "INVALID_REQUEST"));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> oversized(Exception ignored,jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.status(413).body(problem(413,request.getRequestURI().substring(request.getContextPath().length()).matches("/api/v1/race-templates/[^/]+/files")?"FILE_TOO_LARGE":"PHOTO_TOO_LARGE"));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupported(Exception ignored) {
        return ResponseEntity.status(415).body(problem(415,"UNSUPPORTED_MEDIA_TYPE"));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException failure) {
        return ResponseEntity.status(405).headers(failure.getHeaders()).body(problem(405,"METHOD_NOT_ALLOWED"));
    }
    @ExceptionHandler({org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class})
    ResponseEntity<ProblemDetail> notFound(Exception ignored) {
        return ResponseEntity.status(404).body(problem(404,"NOT_FOUND"));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> notAcceptable(Exception ignored) {
        return ResponseEntity.status(406).body(problem(406,"NOT_ACCEPTABLE"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception failure) {
        // JDBC details and arbitrary exception messages can contain tokens, hashes or passwords.
        // Preserve exception types, SQLSTATE and stack frames, not potentially sensitive messages.
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Unexpected API failure",
                diagnostic(failure,java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())));
        return ResponseEntity.internalServerError().body(problem(500, "INTERNAL_ERROR"));
    }
    /** Shared with the authentication filter, which runs outside MVC exception handling. */
    public static void logServerFailure(Throwable failure) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("API server failure",
                diagnostic(failure,java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())));
    }
    private static Throwable diagnostic(Throwable failure,java.util.Set<Throwable> seen) {
        if(!seen.add(failure)) return new RuntimeException("Repeated exception reference");
        String type=failure.getClass().getName();
        if(failure instanceof java.sql.SQLException sql) {
            String state=sql.getSQLState();
            if(state!=null&&state.matches("[A-Za-z0-9]{5}")) type+=" [SQLSTATE="+state+"]";
        }
        var safe=new RuntimeException(type);
        safe.setStackTrace(failure.getStackTrace());
        if(failure.getCause()!=null) safe.initCause(diagnostic(failure.getCause(),seen));
        for(Throwable suppressed:failure.getSuppressed()) safe.addSuppressed(diagnostic(suppressed,seen));
        if(failure instanceof java.sql.SQLException sql&&sql.getNextException()!=null&&!seen.contains(sql.getNextException()))
            safe.addSuppressed(diagnostic(sql.getNextException(),seen));
        return safe;
    }
    private static ProblemDetail problem(int status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        String title = org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase();
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }
}
