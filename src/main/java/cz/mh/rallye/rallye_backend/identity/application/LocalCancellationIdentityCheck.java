package cz.mh.rallye.rallye_backend.identity.application;

import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore.Account;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class LocalCancellationIdentityCheck implements CancellationIdentityCheck {
    private final PasswordEncoder passwords;
    public LocalCancellationIdentityCheck(PasswordEncoder passwords) {this.passwords=passwords;}
    public void verify(Account account,String currentPassword) {
        if(account.passwordHash()==null || currentPassword==null || !passwords.matches(currentPassword,account.passwordHash()))
            throw AuthFailure.credentials();
    }
}
