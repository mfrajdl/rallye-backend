package cz.mh.rallye.rallye_backend.identity.persistence;

import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Temporary, deliberately bounded adapter. A race migration requires a real implementation. */
@Component
public class PreRaceAccountParticipationFacts implements AccountParticipationFacts {
    private final JdbcTemplate jdbc;
    public PreRaceAccountParticipationFacts(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Facts forAccount(UUID account) {
        try {
            var versions=jdbc.query("SELECT version,type,success,script FROM flyway_schema_history WHERE type<>'SCHEMA' ORDER BY installed_rank",
                    (rs,n)->rs.getBoolean("success") && "SQL".equals(rs.getString("type"))
                            && (!"7".equals(rs.getString("version")) || "V7__audit_export_and_retention.sql".equals(rs.getString("script")))
                            && (!"8".equals(rs.getString("version")) || "V8__external_auth_foundation.sql".equals(rs.getString("script")))
                            && (!"9".equals(rs.getString("version")) || "V9__external_web_registration.sql".equals(rs.getString("script")))
                            && (!"10".equals(rs.getString("version")) || "V10__single_immutable_identity.sql".equals(rs.getString("script")))
                            && (!"11".equals(rs.getString("version")) || "V11__external_mobile_login.sql".equals(rs.getString("script")))
                            && (!"12".equals(rs.getString("version")) || "V12__external_mobile_handoff.sql".equals(rs.getString("script")))
                            && (!"13".equals(rs.getString("version")) || "V13__apple_revocation_foundation.sql".equals(rs.getString("script")))
                            && (!"14".equals(rs.getString("version")) || "V14__apple_authorization_lifecycle.sql".equals(rs.getString("script")))
                            && (!"15".equals(rs.getString("version")) || "V15__apple_revocation_completion.sql".equals(rs.getString("script")))
                            && (!"16".equals(rs.getString("version")) || "V16__race_templates.sql".equals(rs.getString("script")))
                            && (!"17".equals(rs.getString("version")) || "V17__stage_configuration_and_readiness.sql".equals(rs.getString("script")))
                            && (!"18".equals(rs.getString("version")) || "V18__template_html_and_images.sql".equals(rs.getString("script")))
                            && (!"25".equals(rs.getString("version")) || "V25__simulation_race_lifecycle.sql".equals(rs.getString("script")))
                            && (!"24".equals(rs.getString("version")) || "V24__persistent_simulation_management.sql".equals(rs.getString("script")))
                            && (!"23".equals(rs.getString("version")) || "V23__race_simulations.sql".equals(rs.getString("script")))
                            && (!"22".equals(rs.getString("version")) || "V22__action_configuration.sql".equals(rs.getString("script")))
                            && (!"21".equals(rs.getString("version")) || "V21__planned_race_events.sql".equals(rs.getString("script")))
                            && (!"20".equals(rs.getString("version")) || "V20__template_file_library.sql".equals(rs.getString("script")))
                            && (!"19".equals(rs.getString("version")) || "V19__template_markdown_and_named_images.sql".equals(rs.getString("script")))
                            ?rs.getString("version"):"unsupported");
            if(!versions.equals(java.util.stream.IntStream.rangeClosed(1,25).mapToObj(Integer::toString).toList())
                    && !versions.equals(java.util.stream.IntStream.rangeClosed(1,24).mapToObj(Integer::toString).toList())
                    && !versions.equals(java.util.stream.IntStream.rangeClosed(1,23).mapToObj(Integer::toString).toList())
                    && !versions.equals(java.util.stream.IntStream.rangeClosed(1,22).mapToObj(Integer::toString).toList())
                    && !versions.equals(java.util.stream.IntStream.rangeClosed(1,21).mapToObj(Integer::toString).toList())
                    && !versions.equals(List.of("1","2","3","4","5","6"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20")))throw unavailable();
            return new Facts(false,false);
        } catch(org.springframework.dao.DataAccessException failure) {throw new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE",failure);}
    }
    private static AuthFailure unavailable() {return new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE");}
}
