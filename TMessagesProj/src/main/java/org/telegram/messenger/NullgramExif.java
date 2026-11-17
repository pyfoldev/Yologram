package org.telegram.messenger;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/** Sends JPEG files without location, camera and time metadata (a clean copy; the original is untouched). */
public class NullgramExif {

    private static final String[] TAGS = {
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp",
        "GPSDateStamp", "GPSProcessingMethod", "GPSAreaInformation", "GPSSpeed", "GPSImgDirection", "GPSDestLatitude", "GPSDestLongitude",
        "Make", "Model", "Software", "DateTime", "DateTimeOriginal", "DateTimeDigitized", "SubSecTime", "SubSecTimeOriginal",
        "SubSecTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized", "LensMake", "LensModel", "LensSerialNumber",
        "BodySerialNumber", "CameraOwnerName", "Artist", "Copyright", "ImageUniqueID", "UserComment", "MakerNote", "ImageDescription",
    };

    /** The path to send: a cleaned copy for JPEG files, otherwise the same path. */
    public static String clean(String path) {
        if (path == null || !NullgramConfig.stripExif()) {
            return path;
        }
        final String lower = path.toLowerCase(Locale.US);
        if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            return path;
        }
        try {
            final File src = new File(path);
            if (!src.exists()) {
                return path;
            }
            // same file name as the original, inside a folder of its own, so the recipient sees the real name
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "exif_clean/" + System.nanoTime());
            dir.mkdirs();
            final File out = new File(dir, src.getName());
            try (FileInputStream in = new FileInputStream(src); FileOutputStream o = new FileOutputStream(out)) {
                final byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    o.write(buf, 0, n);
                }
            }
            final ExifInterface exif = new ExifInterface(out.getAbsolutePath());
            for (String tag : TAGS) {
                exif.setAttribute(tag, null);
            }
            exif.saveAttributes();
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return path;
        }
    }
}
