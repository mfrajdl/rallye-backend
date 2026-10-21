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
        var builder = ResponseEntity.status(failure.status());
        if (failure.status() == 429) builder.header("Retry-After", "900");
        return builder.body(problem(failure.status(), failure.code()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(problem(400, "INVALID_REQUEST"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ignored) {
        return ResponseEntity.internalServerError().body(problem(500, "INTERNAL_ERROR"));
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
