package com.gratilo.user.service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;

public final class ProfileImageValidator {
    private static final String PREFIX = "data:image/jpeg;base64,";

    private ProfileImageValidator() { }

    public static boolean isValid(String value) {
        if (value == null) return true;
        if (value.length() > 140000 || !value.startsWith(PREFIX)) return false;
        try {
            byte[] bytes = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return false;
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    return reader.getFormatName().equalsIgnoreCase("JPEG")
                            && reader.getWidth(0) == 256 && reader.getHeight(0) == 256
                            && reader.read(0) != null;
                } finally { reader.dispose(); }
            }
        } catch (Exception exception) { return false; }
    }
}
