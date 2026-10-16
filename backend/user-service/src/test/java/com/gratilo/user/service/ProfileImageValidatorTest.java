package com.gratilo.user.service;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ProfileImageValidatorTest {
    @Test
    void acceptsOptionalImageAndValidThumbnail() throws Exception {
        assertTrue(ProfileImageValidator.isValid(null));
        assertTrue(ProfileImageValidator.isValid(image(256)));
    }
    @Test
    void rejectsInvalidContentAndOversizedDimensions() throws Exception {
        assertFalse(ProfileImageValidator.isValid("data:image/jpeg;base64,bm90LWFuLWltYWdl"));
        assertFalse(ProfileImageValidator.isValid("https://example.com/photo.jpg"));
        assertFalse(ProfileImageValidator.isValid(image(512)));
        assertFalse(ProfileImageValidator.isValid("data:image/jpeg;base64," + "A".repeat(140000)));
    }
    private String image(int size) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
