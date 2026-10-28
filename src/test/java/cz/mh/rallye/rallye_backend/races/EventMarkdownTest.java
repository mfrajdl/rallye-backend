package cz.mh.rallye.rallye_backend.races;
import java.util.*;import org.junit.jupiter.api.Test;
import cz.mh.rallye.rallye_backend.templates.application.*;
import static org.junit.jupiter.api.Assertions.*;
class EventMarkdownTest {
 final UUID source=UUID.randomUUID(),event=UUID.randomUUID();
 @Test void snapshotRewritesExplicitDestinationsAndPreservesRelativeOnes(){String relative="![Map](map.png) [Guide](guide.md#part)";assertEquals(relative,InstructionsMarkdown.copyToEvent(source,event,relative));String explicit="![Map]("+ImageNames.url(source,"map.png")+") [Guide]("+ImageNames.fileUrl(source,"guide.md")+"#part)";String copied=InstructionsMarkdown.copyToEvent(source,event,explicit);assertFalse(copied.contains(source.toString()));assertTrue(copied.contains(ImageNames.eventUrl(event,"map.png")));assertTrue(copied.contains(ImageNames.eventUrl(event,"guide.md")+"#part"));assertDoesNotThrow(()->InstructionsMarkdown.analyzeEvent(event,copied));}
 @Test void renderedRelativeLinksStayInEventLibraryAndAreSanitized(){String html=InstructionsMarkdown.renderEvent(event,"![Map](map.png) [Guide](guide.md) <script>bad()</script>",Map.of("MAP.PNG","Map.png","GUIDE.MD","guide.md"));assertTrue(html.contains(ImageNames.eventUrl(event,"Map.png")));assertFalse(html.contains("/race-templates/"));assertFalse(html.contains("<script>"));assertTrue(html.contains("noopener noreferrer"));}
 @Test void crossEventImagesAndFilePathsAreRejected(){assertThrows(RuntimeException.class,()->InstructionsMarkdown.analyzeEvent(event,"![Other]("+ImageNames.eventUrl(source,"map.png")+")"));assertThrows(RuntimeException.class,()->InstructionsMarkdown.analyzeEvent(event,"![Other]("+ImageNames.url(source,"map.png")+")"));assertThrows(RuntimeException.class,()->InstructionsMarkdown.analyzeEvent(event,"[Other](../guide.md)"));}
 @Test void referenceDefinitionsAreRewrittenWithoutLosingSurroundingMarkdown(){String text="# Heading\n\n[Guide][ref]\n\n[ref]: "+ImageNames.fileUrl(source,"guide.md");String copied=InstructionsMarkdown.copyToEvent(source,event,text);assertTrue(copied.contains("# Heading"));assertFalse(copied.contains(source.toString()));assertTrue(copied.contains(event.toString()));}
}
