package cz.mh.rallye.rallye_backend.templates.application;

import java.util.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Template;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Stage;

/** Read-only validation; no attempt evaluation or score calculations. Messages never include input values. */
public final class TemplateReadiness {
    private TemplateReadiness(){}
    public record Error(UUID stageId,String field,String code,String message) {}
    public record Report(UUID templateId,long version,boolean ready,List<Error> errors) {
        public Report {errors=List.copyOf(errors);}
    }
    public static final class NotReady extends RuntimeException {
        private final Report report;
        public NotReady(Report report){super("TEMPLATE_NOT_READY");this.report=report;}
        public Report report(){return report;}
    }
    public static Report check(Template template,List<Stage> stages){
        var errors=new ArrayList<Error>();
        if(template.name()==null || template.name().isBlank())required(errors,null,"name");
        else if(template.name().codePointCount(0,template.name().length())>100)invalid(errors,null,"name");
        if(template.description()!=null && template.description().codePointCount(0,template.description().length())>2000)invalid(errors,null,"description");
        if(stages.isEmpty())required(errors,null,"stages");
        for(int i=0;i<stages.size();i++){
            var stage=stages.get(i);UUID id=stage.id();var config=stage.configuration();
            if(stage.sequenceNo()!=i+1)invalid(errors,id,"sequenceNo");
            if(stage.name()==null || stage.name().isBlank())required(errors,id,"name");
            else if(stage.name().codePointCount(0,stage.name().length())>100)invalid(errors,id,"name");
            if(!InstructionsHtml.hasContent(stage.instructionsHtml()))required(errors,id,"instructionsHtml");
            if(stage.expectedDurationMs()==null)required(errors,id,"expectedDurationMs");
            else if(stage.expectedDurationMs()<0)invalid(errors,id,"expectedDurationMs");
            if(stage.maxPenaltyPoints()==null)required(errors,id,"maxPenaltyPoints");
            else if(stage.maxPenaltyPoints()<0)invalid(errors,id,"maxPenaltyPoints");
            for(String field:config.invalidFields())invalid(errors,id,field);
            if(!config.gps().enabled() && !config.code().enabled())required(errors,id,"checks");
            if(config.gps().enabled()){
                if(config.gps().latitude()==null)required(errors,id,"gps.latitude");
                if(config.gps().longitude()==null)required(errors,id,"gps.longitude");
                if(config.gps().radiusMeters()==null)required(errors,id,"gps.radiusMeters");
                if(config.gps().outsidePenaltyPoints()==null)required(errors,id,"gps.outsidePenaltyPoints");
                if(config.gps().spoofingPenaltyPoints()==null)required(errors,id,"gps.spoofingPenaltyPoints");
            }
            // AND: a configured GPS never excuses missing parameters of an enabled CODE, or vice versa.
            if(config.code().enabled()){
                if(config.code().hash()==null)required(errors,id,"code.codeConfigured");
                if(config.code().wrongAttemptPenaltyPoints()==null)required(errors,id,"code.wrongAttemptPenaltyPoints");
            }
            if(config.timePenalty().enabled()){
                if(config.timePenalty().intervalMs()==null)required(errors,id,"timePenalty.intervalSeconds");
                if(config.timePenalty().pointsPerInterval()==null)required(errors,id,"timePenalty.pointsPerInterval");
            }
            if(config.delayed().enabled()){
                if(config.delayed().minimumDurationMs()==null)required(errors,id,"delayed.minimumDurationMs");
                if(config.delayed().pointsPerSecond()==null)required(errors,id,"delayed.pointsPerSecond");
            }
        }
        return new Report(template.id(),template.version(),errors.isEmpty(),errors);
    }
    private static void required(List<Error> errors,UUID id,String field){errors.add(new Error(id,field,"REQUIRED","Doplňte povinné pole: "+field+"."));}
    private static void invalid(List<Error> errors,UUID id,String field){errors.add(new Error(id,field,"INVALID","Neplatná hodnota pole: "+field+"."));}
}
