package cz.mh.rallye.rallye_backend.identity.application;

import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore.Account;

/** LOCAL password verification. External D10 confirmation is checked by the cancellation service. */
public interface CancellationIdentityCheck {
    void verify(Account account,String currentPassword);
}
