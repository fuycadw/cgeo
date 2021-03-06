package cgeo.geocaching.network;

import cgeo.geocaching.CgeoApplication;
import cgeo.geocaching.R;
import cgeo.geocaching.compatibility.Compatibility;
import cgeo.geocaching.connector.ConnectorFactory;
import cgeo.geocaching.files.LocalStorage;
import cgeo.geocaching.list.StoredList;
import cgeo.geocaching.utils.FileUtils;
import cgeo.geocaching.utils.ImageUtils;
import cgeo.geocaching.utils.Log;

import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.androidextra.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.Nullable;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.text.Html;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

public class HtmlImage implements Html.ImageGetter {

    private static final String[] BLOCKED = new String[] {
            "gccounter.de",
            "gccounter.com",
            "cachercounter/?",
            "gccounter/imgcount.php",
            "flagcounter.com",
            "compteur-blog.net",
            "counter.digits.com",
            "andyhoppe",
            "besucherzaehler-homepage.de",
            "hitwebcounter.com",
            "kostenloser-counter.eu",
            "trendcounter.com",
            "hit-counter-download.com",
            "gcwetterau.de/counter"
    };
    public static final String SHARED = "shared";

    final private String geocode;
    /**
     * on error: return large error image, if <code>true</code>, otherwise empty 1x1 image
     */
    final private boolean returnErrorImage;
    final private int listId;
    final private boolean onlySave;
    final private BitmapFactory.Options bfOptions;
    final private int maxWidth;
    final private int maxHeight;
    final private Resources resources;

    public HtmlImage(final String geocode, final boolean returnErrorImage, final int listId, final boolean onlySave) {
        this.geocode = geocode;
        this.returnErrorImage = returnErrorImage;
        this.listId = listId;
        this.onlySave = onlySave;

        bfOptions = new BitmapFactory.Options();
        bfOptions.inTempStorage = new byte[16 * 1024];
        bfOptions.inPreferredConfig = Bitmap.Config.RGB_565;

        Point displaySize = Compatibility.getDisplaySize();
        this.maxWidth = displaySize.x - 25;
        this.maxHeight = displaySize.y - 25;
        this.resources = CgeoApplication.getInstance().getResources();
    }

    @Nullable
    @Override
    public BitmapDrawable getDrawable(final String url) {
        // Reject empty and counter images URL
        if (StringUtils.isBlank(url) || isCounter(url)) {
            return getTransparent1x1Image(resources);
        }

        final boolean shared = url.contains("/images/icons/icon_");
        final String pseudoGeocode = shared ? SHARED : geocode;

        Bitmap image = loadImageFromStorage(url, pseudoGeocode, shared);

        // Download image and save it to the cache
        if (image == null) {
            final File file = LocalStorage.getStorageFile(pseudoGeocode, url, true, true);
            if (url.startsWith("data:image/")) {
                if (url.contains(";base64,")) {
                    saveBase64ToFile(url, file);
                } else {
                    Log.e("HtmlImage.getDrawable: unable to decode non-base64 inline image");
                    return null;
                }
            } else {
                downloadOrRefreshCopy(url, file);
            }
        }

        if (onlySave) {
            return null;
        }

        // now load the newly downloaded image
        if (image == null) {
            image = loadImageFromStorage(url, pseudoGeocode, shared);
        }

        // get image and return
        if (image == null) {
            Log.d("HtmlImage.getDrawable: Failed to obtain image");

            return returnErrorImage
                    ? new BitmapDrawable(resources, BitmapFactory.decodeResource(resources, R.drawable.image_not_loaded))
                    : getTransparent1x1Image(resources);
        }

        return ImageUtils.scaleBitmapToFitDisplay(image);
    }

    private void downloadOrRefreshCopy(final String url, final File file) {
        final String absoluteURL = makeAbsoluteURL(url);

        if (absoluteURL != null) {
            try {
                final HttpResponse httpResponse = Network.getRequest(absoluteURL, null, file);
                if (httpResponse != null) {
                    final int statusCode = httpResponse.getStatusLine().getStatusCode();
                    if (statusCode == 200) {
                        LocalStorage.saveEntityToFile(httpResponse, file);
                    } else if (statusCode == 304) {
                        if (!file.setLastModified(System.currentTimeMillis())) {
                            makeFreshCopy(file);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("HtmlImage.downloadOrRefreshCopy", e);
            }
        }
    }

    private static void saveBase64ToFile(final String url, final File file) {
        // TODO: when we use SDK level 8 or above, we can use the streaming version of the base64
        // Android utilities.
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(Base64.decode(StringUtils.substringAfter(url, ";base64,"), Base64.DEFAULT));
        } catch (final IOException e) {
            Log.e("HtmlImage.saveBase64ToFile: cannot write file for decoded inline image", e);
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * Make a fresh copy of the file to reset its timestamp. On some storage, it is impossible
     * to modify the modified time after the fact, in which case a brand new file must be
     * created if we want to be able to use the time as validity hint.
     *
     * See Android issue 1699.
     *
     * @param file the file to refresh
     */
    private static void makeFreshCopy(final File file) {
        final File tempFile = new File(file.getParentFile(), file.getName() + "-temp");
        if (file.renameTo(tempFile)) {
            LocalStorage.copy(tempFile, file);
            FileUtils.deleteIgnoringFailure(tempFile);
        }
        else {
            Log.e("Could not reset timestamp of file " + file.getAbsolutePath());
        }
    }

    private BitmapDrawable getTransparent1x1Image(final Resources res) {
        return new BitmapDrawable(res, BitmapFactory.decodeResource(resources, R.drawable.image_no_placement));
    }

    @Nullable
    private Bitmap loadImageFromStorage(final String url, final String pseudoGeocode, final boolean forceKeep) {
        try {
            final File file = LocalStorage.getStorageFile(pseudoGeocode, url, true, false);
            final Bitmap image = loadCachedImage(file, forceKeep);
            if (image != null) {
                return image;
            }
            final File fileSec = LocalStorage.getStorageSecFile(pseudoGeocode, url, true);
            return loadCachedImage(fileSec, forceKeep);
        } catch (Exception e) {
            Log.w("HtmlImage.loadImageFromStorage", e);
        }
        return null;
    }

    @Nullable
    private String makeAbsoluteURL(final String url) {
        // Check if uri is absolute or not, if not attach the connector hostname
        // FIXME: that should also include the scheme
        if (Uri.parse(url).isAbsolute()) {
            return url;
        }

        final String host = ConnectorFactory.getConnector(geocode).getHost();
        if (StringUtils.isNotEmpty(host)) {
            final StringBuilder builder = new StringBuilder("http://");
            builder.append(host);
            if (!StringUtils.startsWith(url, "/")) {
                // FIXME: explain why the result URL would be valid if the path does not start with
                // a '/', or signal an error.
                builder.append('/');
            }
            builder.append(url);
            return builder.toString();
        }

        return null;
    }

    @Nullable
    private Bitmap loadCachedImage(final File file, final boolean forceKeep) {
        if (file.exists()) {
            if (listId >= StoredList.STANDARD_LIST_ID || file.lastModified() > (new Date().getTime() - (24 * 60 * 60 * 1000)) || forceKeep) {
                setSampleSize(file);
                final Bitmap image = BitmapFactory.decodeFile(file.getPath(), bfOptions);
                if (image == null) {
                    Log.e("Cannot decode bitmap from " + file.getPath());
                }
                return image;
            }
        }
        return null;
    }

    private void setSampleSize(final File file) {
        //Decode image size only
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        BufferedInputStream stream = null;
        try {
            stream = new BufferedInputStream(new FileInputStream(file));
            BitmapFactory.decodeStream(stream, null, options);
        } catch (FileNotFoundException e) {
            Log.e("HtmlImage.setSampleSize", e);
        } finally {
            IOUtils.closeQuietly(stream);
        }

        int scale = 1;
        if (options.outHeight > maxHeight || options.outWidth > maxWidth) {
            scale = Math.max(options.outHeight / maxHeight, options.outWidth / maxWidth);
        }
        bfOptions.inSampleSize = scale;
    }

    private static boolean isCounter(final String url) {
        for (String entry : BLOCKED) {
            if (StringUtils.containsIgnoreCase(url, entry)) {
                return true;
            }
        }
        return false;
    }
}
