package cz.mh.rallye.rallye_backend.identity.application;

import java.util.UUID;

/** Must use permanent historical captain identity, independent of current crew ownership. */
public interface AccountParticipationFacts {
    record Facts(boolean historical,boolean running) { }
    Facts forAccount(UUID account);
}
