package me.goddragon.teaseai.utils.libraries.ripme.ripper.rippers;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.goddragon.teaseai.utils.libraries.ripme.ripper.AlbumRipper;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import me.goddragon.teaseai.utils.libraries.ripme.ripper.DownloadThreadPool;
import me.goddragon.teaseai.utils.libraries.ripme.ui.RipStatusMessage.STATUS;
import me.goddragon.teaseai.utils.libraries.ripme.utils.Http;
import me.goddragon.teaseai.utils.libraries.ripme.utils.Utils;

public class PornhubRipper extends AlbumRipper {
    // All sleep times are in milliseconds
    private static final int IMAGE_SLEEP_TIME    = 1  * 1000;

    private static final String DOMAIN = "pornhub.com", HOST = "Pornhub";

    // Thread pool for finding direct image links from "image" pages (html)
    private DownloadThreadPool pornhubThreadPool = new DownloadThreadPool("pornhub");

    // Current HTML document
    private Document albumDoc = null;

    public PornhubRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return HOST;
    }

    public URL sanitizeURL(URL url) throws MalformedURLException {
        return url;
    }

    public String getAlbumTitle(URL url) throws MalformedURLException {
        try {
            // Attempt to use album title as GID
            if (albumDoc == null) {
                LOGGER.info("    Retrieving " + url.toExternalForm());
                sendUpdate(STATUS.LOADING_RESOURCE, url.toString());
                albumDoc = Http.url(url).get();
            }
            Elements elems = albumDoc.select(".photoAlbumTitleV2");
            return HOST + "_" + elems.get(0).text();
        } catch (Exception e) {
            // Fall back to default album naming convention
            LOGGER.warn("Failed to get album title from " + url, e);
        }
        return super.getAlbumTitle(url);
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p;
        Matcher m;

        p = Pattern.compile("^.*pornhub\\.com/album/([0-9]+)$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }

        throw new MalformedURLException(
                "Expected pornhub.com album format: "
                        + "http://www.pornhub.com/album/####"
                        + " Got: " + url);
    }

    @Override
    public void rip() throws IOException {
        int index = 0;
        String nextUrl = this.url.toExternalForm();

        if (albumDoc == null) {
            LOGGER.info("    Retrieving album page " + nextUrl);
            sendUpdate(STATUS.LOADING_RESOURCE, nextUrl);
            albumDoc = Http.url(nextUrl)
                           .referrer(this.url)
                           .get();
        }

        // Find thumbnails
        Elements thumbs = albumDoc.select(".photoBlockBox li");
        if (thumbs.size() == 0) {
            LOGGER.debug("albumDoc: " + albumDoc);
            LOGGER.debug("No images found at " + nextUrl);
            return;
        }

        // Iterate over images on page
        for (Element thumb : thumbs) {
            if (isStopped()) {
                break;
            }
            index++;
            String imagePageUrl = thumb.select(".photoAlbumListBlock > a").first().attr("href");
            URL imagePage = new URL(url, imagePageUrl);
            PornhubImageThread t = new PornhubImageThread(imagePage, index, this.workingDir);
            pornhubThreadPool.addThread(t);
            if (isThisATest()) {
                break;
            }
            try {
                Thread.sleep(IMAGE_SLEEP_TIME);
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting to load next image", e);
            }
        }

        pornhubThreadPool.waitForThreads();
        waitForThreads();
    }

    public boolean canRip(URL url) {
        return url.getHost().endsWith(DOMAIN) && url.getPath().startsWith("/album");
    }

    /**
     * Helper class to find and download images found on "image" pages
     *
     * Handles case when site has IP-banned the user.
     */
    private class PornhubImageThread extends Thread {
        private URL url;
        private int index;

        public PornhubImageThread(URL url, int index, File workingDir) {
            super();
            this.url = url;
            this.index = index;
        }

        @Override
        public void run() {
            fetchImage();
        }

        private void fetchImage() {
            try {
                Document doc = Http.url(this.url)
                                   .referrer(this.url)
                                   .get();

                // Find image
                Elements images = doc.select("#photoImageSection img");
                Element image = images.first();
                String imgsrc = image.attr("src");
                LOGGER.info("Found URL " + imgsrc + " via " + images.get(0));

                // Provide prefix and let the AbstractRipper "guess" the filename
                String prefix = "";
                if (Utils.getConfigBoolean("download.save_order", true)) {
                    prefix = String.format("%03d_", index);
                }

                URL imgurl = new URL(url, imgsrc);
                addURLToDownload(imgurl, prefix);

            } catch (IOException e) {
                LOGGER.error("[!] Exception while loading/parsing " + this.url, e);
            }
        }
    }
}