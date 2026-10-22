package cz.mh.rallye.rallye_backend.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Plain text only; secrets are confined to the client's URL fragment. */
final class CrewTransferMessage {
    static String text(String page,String name,UUID id,String token,Instant expires) {
        String safePage=SmtpAccountMailSender.validateBaseUrl(page);
        return "Byli jste požádáni o převzetí kapitánství posádky: "+name+"\n"
                +"Žádost platí 7 dní od založení, nejpozději do "+expires+" (UTC).\n"
                +"Přihlaste se ke svému účtu a převod přijměte nebo odmítněte. Samotné otevření odkazu převod neprovede.\n"
                +safePage+"#transferId="+id+"&token="+token+"\n";
    }
}
