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
                            ?rs.getString("version"):"unsupported");
            if(!versions.equals(List.of("1","2","3","4","5","6"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9"))
                    && !versions.equals(List.of("1","2","3","4","5","6","7","8","9","10")))throw unavailable();
            return new Facts(false,false);
        } catch(org.springframework.dao.DataAccessException failure) {throw new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE",failure);}
    }
    private static AuthFailure unavailable() {return new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE");}
}
