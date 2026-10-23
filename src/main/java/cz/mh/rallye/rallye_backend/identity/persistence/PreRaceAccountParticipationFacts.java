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
            var versions=jdbc.query("SELECT version,type,success FROM flyway_schema_history WHERE type<>'SCHEMA' ORDER BY installed_rank",
                    (rs,n)->rs.getBoolean("success") && "SQL".equals(rs.getString("type"))?rs.getString("version"):"unsupported");
            if(!versions.equals(List.of("1","2","3","4","5","6")))throw unavailable();
            return new Facts(false,false);
        } catch(org.springframework.dao.DataAccessException failure) {throw new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE",failure);}
    }
    private static AuthFailure unavailable() {return new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE");}
}
