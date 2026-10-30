package cz.mh.rallye.rallye_backend.crews.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CrewStore {
    public record Crew(UUID id, UUID captainAccountId, String name, String registrationPlate, String description,
                       UUID photoId, String photoType, Integer photoSize, Integer photoWidth, Integer photoHeight,
                       Instant createdAt, Instant updatedAt, long version) { }
    public record ListedCrew(Crew crew,String captainDisplayName) {}
    private final JdbcTemplate jdbc;
    public CrewStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private Crew map(ResultSet r,int n) throws SQLException {
        return new Crew(r.getObject("id",UUID.class),r.getObject("captain_account_id",UUID.class),r.getString("name"),
                r.getString("registration_plate"),r.getString("description"),r.getObject("photo_id",UUID.class),r.getString("photo_type"),
                r.getObject("photo_size",Integer.class),r.getObject("photo_width",Integer.class),r.getObject("photo_height",Integer.class),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getLong("version"));
    }
    public Crew find(UUID id) {
        var rows=jdbc.query("SELECT * FROM crew WHERE id=? FOR UPDATE",this::map,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<ListedCrew> list(UUID owner, boolean all, boolean orphaned, int page, int size) {
        return jdbc.query("""
                SELECT c.*,a.display_name AS captain_display_name
                FROM crew c LEFT JOIN user_account a ON a.id=c.captain_account_id
                WHERE (? OR c.captain_account_id=?) AND (NOT ? OR c.captain_account_id IS NULL)
                ORDER BY lower(c.name) ASC,c.id ASC LIMIT ? OFFSET ?
                """,(r,n)->new ListedCrew(map(r,n),r.getString("captain_display_name")),all,owner,orphaned,size+1,(long)page*size);
    }
    public void insert(UUID id,UUID captain,String name,String plate,String description,Instant now) {
        jdbc.update("INSERT INTO crew(id,captain_account_id,name,registration_plate,description,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                id,captain,name,plate,description,Timestamp.from(now),Timestamp.from(now));
    }
    public void update(UUID id,String name,String plate,String description,Instant now) {
        jdbc.update("UPDATE crew SET name=?,registration_plate=?,description=?,updated_at=?,version=version+1 WHERE id=?",
                name,plate,description,Timestamp.from(now),id);
    }
    public void captain(UUID id,UUID captain,Instant now) {
        jdbc.update("UPDATE crew SET captain_account_id=?,updated_at=?,version=version+1 WHERE id=?",captain,Timestamp.from(now),id);
    }
    public void photo(UUID id,UUID photo,String type,Integer size,Integer width,Integer height,Instant now) {
        jdbc.update("UPDATE crew SET photo_id=?,photo_type=?,photo_size=?,photo_width=?,photo_height=?,updated_at=?,version=version+1 WHERE id=?",
                photo,type,size,width,height,Timestamp.from(now),id);
    }
    public boolean registrations(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crew_registration WHERE crew_id=?)",Boolean.class,id));}
    public void delete(UUID id) { jdbc.update("DELETE FROM crew WHERE id=?",id); }
    public boolean referenced(UUID photo) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crew WHERE photo_id=?)",Boolean.class,photo));
    }
}
