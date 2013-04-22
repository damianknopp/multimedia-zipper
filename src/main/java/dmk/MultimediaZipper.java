package dmk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given some html, containing img tags, download those images to a tmp dir and
 * zip the images with the html into one file
 */
public class MultimediaZipper {
	private static Logger logger = LoggerFactory
			.getLogger(MultimediaZipper.class);
	private final ExecutorService pool;

	public static void main(String argv[]) {
		try {
			MultimediaZipper zipper = new MultimediaZipper(4);

			final String html = "<html><head><link href=\"yc.css\" type=\"text/css\" rel=\"stylesheet\"><title>Y Combinator</title></head><body bgcolor=\"#ffffff\"><div style=\"position: relative; width: 500px; height: 350px;\"><img src=\"slideshow/2.jpg\" id=\"slideshow0\"><img width=\"500\" height=\"350\" style=\"position: absolute; top: 0px; left: 0px; opacity: 1;\" src=\"slideshow/1.jpg\" id=\"slideshow1\"></div></body></html>";
			final File f = zipper.fetchAndZip("http://ycombinator.com/", html);
			logger.debug("zip file = " + f.getAbsolutePath());
		} catch (Exception e) {
			System.out.println("Exception " + e);
		}
		System.exit(0);
	}

	public MultimediaZipper(final int poolSize) {
		this.pool = Executors.newFixedThreadPool(poolSize);
	}

	/**
	 * 
	 * @param rootUri
	 *            required String representing the root Uri for relative links
	 * @param html
	 * @return File representing the zip file, or null is nothing to do
	 * @throws IOException 
	 */
	public File fetchAndZip(final String rootUri, final String html) throws IOException {
		if (html == null || html.trim().isEmpty()) {
			return null;
		}
		final List<String> uris = this.parseForUris(rootUri, html);
		return this.fetchAndZip(uris);
	}

	/**
	 * parse html for uris
	 * 
	 * @param rootUri
	 * @param html
	 * @return
	 */
	private List<String> parseForUris(final String rootUri, final String html) {
		// parse html, get uris
		final List<String> uris = new LinkedList<String>();
		Document doc = Jsoup.parse(html);
		Elements media = doc.select("[src]");
		if (logger.isDebugEnabled()) {
			logger.debug(String.format("\nMedia found: (%d)", media.size()));
		}
		for (Element src : media) {
			if (src.tagName().equals("img")) {
				String uri = src.attr("src");
				if (logger.isDebugEnabled()) {
					logger.debug(String.format("found img tag src = %s", uri));
				}
				uri = this.normalizeUri(rootUri, uri);
				uris.add(uri);
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug(String.format(" * %s: <%s>", src.tagName(),
							src.attr("src")));
				}
			}
		}
		return uris;
	}

	/**
	 * 
	 * @param uris
	 *            required List of Strings representing URIs to download, these
	 *            URIs must have full paths
	 * @return File representing the zip file created
	 * @throws IOException 
	 */
	public File fetchAndZip(final List<String> uris) throws IOException {
		if (uris == null || uris.isEmpty()) {
			return null;
		}
		// fetch the files
		final List<File> downloaded = this.fetchUris(uris);

		// zip downloaded files
		return this.createZip(downloaded);
	}

	private File createZip(final List<File> files) throws IOException {
		final File tmpZip = File.createTempFile("dmk", ".zip");
		if(logger.isDebugEnabled()){
			logger.debug("creating zip file " + tmpZip.getAbsolutePath());
		}
		for (final File file : files) {
			FileInputStream in = null;
			ZipOutputStream out = null;
			try{
			// read file
			in = new FileInputStream(file);
			// output file
			out = new ZipOutputStream(new FileOutputStream(
					tmpZip));
			// name the file inside the zip file
			final String entryName = file.getAbsolutePath();
			if(logger.isDebugEnabled()){
				logger.debug("adding entry name " + entryName);
			}
			out.putNextEntry(new ZipEntry(entryName));
			byte[] b = new byte[1024];
			int count;
			while ((count = in.read(b)) > 0) {
				out.write(b, 0, count);
			}
			}finally{
				if(out != null){
					out.close();
				}
				if(in != null){
					in.close();				
				}
			}
		}
		return tmpZip;
	}

	/**
	 * fetch files at the following URIs
	 * 
	 * @param uris
	 * @return List<String> representing the URIs of files we downloaded
	 */
	public List<File> fetchUris(final List<String> uris) {
		final Set<Future<File>> set = new HashSet<Future<File>>();

		for (final String uri : uris) {
			final Callable<File> callable = new UriFetcher(uri);
			final Future<File> future = pool.submit(callable);
			set.add(future);
		}

		final List<File> downloaded = new ArrayList<File>(uris.size() * 2);
		for (final Future<File> future : set) {
			try {
				downloaded.add(future.get());
			} catch (InterruptedException e) {
				logger.warn(e.getMessage());
			} catch (ExecutionException e) {
				logger.warn(e.getMessage());
			}
		}
		return downloaded;
	}

	/**
	 * will add the root uri if it needs it
	 * 
	 * @param rootUri
	 * @param uri
	 */
	private String normalizeUri(final String rootUri, final String uri) {
		if (uri.startsWith("http:") || uri.startsWith("https:")) {
			return uri;
		}
		// add ending / to root
		String root = (!rootUri.endsWith("/")) ? rootUri + "/" : rootUri;

		if (uri.startsWith("/")) {
			return root + uri.substring(1, uri.length() - 1);
		} else {
			return root + uri;
		}
	}

	/**
	 * download content from given uri
	 */
	private static class UriFetcher implements Callable<File> {
		private static Logger logger = LoggerFactory
				.getLogger(UriFetcher.class);

		private String uri;

		/**
		 * 
		 * @param destDir
		 * @param uri
		 */
		public UriFetcher(final String uri) {
			this.uri = uri;
		}

		/**
		 * 
		 */
		public File call() {
			URL url;
			InputStream is = null;
			BufferedInputStream bis = null;
			File tmpFile = null;

			try {
				if (logger.isDebugEnabled()) {
					logger.debug("downloading file " + this.uri);
				}
				url = new URL(this.uri);
				is = url.openStream(); // throws an IOException
				bis = new BufferedInputStream(is);
				final byte[] byteArray = this.readAndClose(bis);
				ByteBuffer bytes = ByteBuffer.wrap(byteArray);
				tmpFile = File.createTempFile("dmk", ".tmp");
				this.writeAndClose(tmpFile, bytes);
			} catch (MalformedURLException mue) {
				mue.printStackTrace();
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
			return tmpFile;
		}

		private byte[] readAndClose(InputStream aInput) {
			byte[] bucket = new byte[16 * 1024];
			ByteArrayOutputStream result = null;
			try {
				try {
					result = new ByteArrayOutputStream(bucket.length);
					int bytesRead = 0;
					while (bytesRead != -1) {
						// aInput.read() returns -1, 0, or more :
						bytesRead = aInput.read(bucket);
						if (bytesRead > 0) {
							result.write(bucket, 0, bytesRead);
						}
					}
				} finally {
					if (aInput != null) {
						aInput.close();
					}
				}
			} catch (IOException ex) {
				logger.warn(ex.getMessage());
			}
			return result.toByteArray();
		}

		private void writeAndClose(final File f, ByteBuffer bytes)
				throws IOException {
			try {
				if (logger.isDebugEnabled()) {
					logger.debug("writing " + bytes.capacity() + " to "
							+ f.getAbsolutePath());
				}
				final FileChannel fc = new FileOutputStream(f).getChannel();
				fc.write(bytes, 0);
			} catch (FileNotFoundException fnfe) {
				logger.warn(fnfe.getMessage());
			}

		}
	}

}