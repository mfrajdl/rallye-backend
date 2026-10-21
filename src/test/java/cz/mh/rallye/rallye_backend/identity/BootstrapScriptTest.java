package cz.mh.rallye.rallye_backend.identity;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapScriptTest {
    @Test void postgresIntegrationTestsUseActualBootstrapBodyWithoutPsqlDirectivesOrOwnCommit() throws Exception {
        UUID target=UUID.randomUUID();
        String sql=AdministrationIT.bootstrapBody(target);
        assertFalse(sql.lines().anyMatch(line -> line.startsWith("\\") || line.equals("BEGIN;") || line.equals("COMMIT;")));
        assertFalse(sql.contains(":'account_id'")); assertTrue(sql.contains("'"+target+"'"));
        assertTrue(sql.contains("FOR UPDATE")); assertTrue(sql.contains("ADMIN_BOOTSTRAPPED"));
        assertTrue(sql.contains("DO $$")); assertTrue(sql.contains("END;\n$$;"));
    }
}
