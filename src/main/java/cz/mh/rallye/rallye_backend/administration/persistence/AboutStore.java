package cz.mh.rallye.rallye_backend.administration.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

@Repository
public class AboutStore {
    public record Database(String type,String version,String migrationVersion,String migrationDescription,String migrationState){}
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    public AboutStore(JdbcTemplate jdbc,Flyway flyway){this.jdbc=jdbc;this.flyway=flyway;}
    private static String identifier(String value){return "\""+value.replace("\"","\"\"")+"\"";}
    public Database read(){
        try{return jdbc.execute((ConnectionCallback<Database>) connection->{
            if(!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))throw new AuthFailure(503,"ABOUT_DATABASE_UNAVAILABLE");
            String version;
            try(var query=connection.prepareStatement("SELECT current_setting('server_version')");var rows=query.executeQuery()){rows.next();version=rows.getString(1);}
            var config=flyway.getConfiguration();String schema=config.getDefaultSchema();
            if(schema==null){var schemas=config.getSchemas();schema=schemas.length==0?connection.getSchema():schemas[0];}
            if(schema==null||config.getTable()==null)throw new AuthFailure(503,"ABOUT_DATABASE_UNAVAILABLE");
            String table=identifier(schema)+"."+identifier(config.getTable());
            try(var query=connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")){
                query.setString(1,table);try(var rows=query.executeQuery()){rows.next();if(!rows.getBoolean(1))return new Database("PostgreSQL",version,null,null,"NONE");}
            }
            // Actual installed versioned history, not filenames/resolved pending migrations or a hardcoded V27.
            try(var query=connection.prepareStatement("SELECT version,description,success FROM "+table+" WHERE version IS NOT NULL AND type NOT IN ('SCHEMA','DELETE') ORDER BY installed_rank DESC LIMIT 1");var rows=query.executeQuery()){
                if(!rows.next())return new Database("PostgreSQL",version,null,null,"NONE");
                return new Database("PostgreSQL",version,rows.getString(1),rows.getString(2),rows.getBoolean(3)?"SUCCESS":"FAILED");
            }
        });}catch(DataAccessException failure){throw new AuthFailure(503,"ABOUT_DATABASE_UNAVAILABLE",failure);}
    }
}
