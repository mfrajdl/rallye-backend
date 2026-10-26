package cz.mh.rallye.rallye_backend.templates.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import cz.mh.rallye.rallye_backend.templates.application.TemplateReadiness;

@RestControllerAdvice(assignableTypes=TemplateController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TemplateErrors {
    @ExceptionHandler(TemplateReadiness.NotReady.class)
    public ResponseEntity<ProblemDetail> notReady(TemplateReadiness.NotReady failure){
        var problem=ProblemDetail.forStatus(409);problem.setProperty("code","TEMPLATE_NOT_READY");
        problem.setProperty("templateId",failure.report().templateId());problem.setProperty("version",failure.report().version());
        problem.setProperty("errors",failure.report().errors());
        return ResponseEntity.status(409).body(problem);
    }
}
