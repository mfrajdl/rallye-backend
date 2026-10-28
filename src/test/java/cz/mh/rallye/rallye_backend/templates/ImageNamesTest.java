package cz.mh.rallye.rallye_backend.templates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cz.mh.rallye.rallye_backend.templates.application.ImageNames;
import static org.junit.jupiter.api.Assertions.*;
class ImageNamesTest {
 @Test void normalizesUnicodeAndComparesCaseConsistently(){assertEquals("česká mapa.png",ImageNames.validate("c\u030ceska\u0301 mapa.png"));assertEquals(ImageNames.key("ČESKÁ MAPA.PNG"),ImageNames.key("česká mapa.png"));assertNotEquals(ImageNames.key("mapa.png"),ImageNames.key("mapa2.png"));}
 @ParameterizedTest @ValueSource(strings={"../x.png","/x.png","C:\\x.png","a/b.png","a\\b.png","CON.png","nul","a:b.png","x.png ",".hidden","x.","a%20.png","a?.png","a#b.png","a\nb.png","a\u202eb.png"})void rejectsUnsafeNames(String name){assertThrows(RuntimeException.class,()->ImageNames.validate(name));}
}
