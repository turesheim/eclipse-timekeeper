package net.resheim.eclipse.timekeeper.domain;

import java.util.List;
import java.util.Locale;

/** Initial labels shared by embedded and server deployments. */
public final class DefaultLabels {
    private DefaultLabels() { }

    public record Definition(String name, int red, int green, int blue) {
        public String rgbColor() { return red + "," + green + "," + blue; }
        public String hexColor() { return String.format(Locale.ROOT,"#%02X%02X%02X",red,green,blue); }
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    private static final List<Definition> DEFINITIONS=List.of(
            new Definition("Production issue",244,103,88),
            new Definition("Testing",245,166,81),
            new Definition("Prototyping",246,208,90),
            new Definition("Planning",255,0,255),
            new Definition("Programming",87,206,105),
            new Definition("Debugging",177,111,209),
            new Definition("Communication",66,136,243),
            new Definition("Meeting",156,156,160));
}
