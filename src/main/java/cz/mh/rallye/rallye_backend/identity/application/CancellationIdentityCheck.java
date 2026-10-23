package cz.mh.rallye.rallye_backend.identity.application;

import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore.Account;

/** Future external reauthentication belongs behind this boundary, never in the command ledger. */
public interface CancellationIdentityCheck {
    void verify(Account account,String currentPassword);
}
